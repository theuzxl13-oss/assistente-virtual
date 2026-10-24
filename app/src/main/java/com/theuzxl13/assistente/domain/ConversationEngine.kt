package com.theuzxl13.assistente.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

enum class ConversationState {
    INATIVA,
    OUVINDO,
    PROCESSANDO,
    FALANDO,
    AGUARDANDO_CONFIRMACAO,
    EXECUTANDO_ACAO,
    ERRO,
    CANCELADA
}

/**
 * Cérebro da Bela: ouve, interpreta, executa e fala o resultado, mantendo o contexto da conversa
 * (perguntas de confirmação, listas para escolher, última resposta para repetir).
 *
 * Regras que valem em todo o fluxo:
 * - Nunca diz que algo deu certo sem verificar (estado da chamada, retorno do Android).
 * - Ligações sempre passam por confirmação ou escolha explícita, para evitar ligar sem querer.
 * - O microfone só abre depois que a fala termina (SpeechInput e SpeechOutput nunca ao mesmo tempo).
 */
class ConversationEngine(
    private val speech: SpeechOutput,
    private val input: SpeechInput,
    private val sounds: SoundFeedback,
    private val permissions: PermissionPort,
    private val contacts: ContactsPort,
    private val phone: PhonePort,
    private val media: MediaPort,
    private val search: SearchPort,
    private val apps: AppsPort,
    private val device: DevicePort,
    private val prefs: PreferencesPort,
    private val bible: BiblePort = NoBible,
    private val explainer: BibleExplainerPort = NoBibleExplainer,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {

    var onStateChanged: (ConversationState) -> Unit = {}
    var state: ConversationState = ConversationState.INATIVA
        private set

    /** Texto recebido de outro app pelo "Compartilhar", para "leia esse texto". */
    var sharedText: String? = null

    private var lastSpoken = ""
    private var pending: Pending? = null
    private val history = mutableListOf<Turn>()
    private var lastMedia: MediaList? = null
    /** Última pesquisa, para "próximo resultado", "abra no navegador" e "tente de novo". */
    private var lastSearch: SearchSession? = null
    /** Último trecho bíblico lido, para "explica", "próximo versículo", "próximo capítulo". */
    private var lastBible: BibleSession? = null
    private var bibleTranslationAnnounced = false

    private sealed class Pending(val question: String) {
        class ConfirmCall(val contact: Contact, question: String) : Pending(question)
        class ChooseContact(val options: List<Contact>, question: String) : Pending(question)
        class AskCallName(question: String) : Pending(question)
        class ChooseMedia(val list: MediaList, question: String) : Pending(question)
        class AskMediaQuery(question: String) : Pending(question)
        class ChooseApp(val options: List<InstalledApp>, question: String) : Pending(question)
        class ConfirmAnswer(question: String) : Pending(question)
        class SearchResults(val search: SearchSession, question: String) : Pending(question)
        class SearchFailed(val spokenQuestion: String, question: String) : Pending(question)
        class BibleReading(val session: BibleSession, question: String) : Pending(question)
        class BibleContinue(val session: BibleSession, question: String) : Pending(question)
        class ContinueReading(val remaining: String, question: String) : Pending(question)
    }

    private class MediaList(val items: List<MediaItem>, var batchStart: Int = 0, var playedIndex: Int = -1)

    /** Trecho bíblico sendo lido: versículos [first]..[last] do capítulo. */
    private class BibleSession(
        val book: BibleBook,
        var chapter: Int,
        var verses: List<String>,
        var first: Int,
        var last: Int,
        var explanationDetails: String = ""
    ) {
        val reference: BibleReference get() = BibleReference(book, chapter, first, last)
        val wholeChapter: Boolean get() = first == 1 && last == verses.size
    }

    /** Resultados de uma pesquisa e qual deles está sendo lido. */
    private class SearchSession(val spokenQuestion: String, val answer: Answer, var index: Int = 0, var detailsRead: Boolean = false) {
        val current: WebResult? get() = answer.results.getOrNull(index)
    }

    private enum class Next { LISTEN, END }

    /**
     * Uma conversa: ouve, responde e continua ouvindo enquanto ela tiver o que pedir. Termina em
     * silêncio, quando uma ação leva para outro app (ligação, YouTube) ou quando ela se despede.
     *
     * @param start pedido já conhecido para tratar antes de ouvir (ex.: texto compartilhado).
     */
    suspend fun runSession(start: BelaIntent? = null) {
        var remindedAfterSilence = false
        var busyRetries = 0
        try {
            if (start != null && dispatch(start) == Next.END) return

            while (true) {
                setState(if (pending != null) ConversationState.AGUARDANDO_CONFIRMACAO else ConversationState.OUVINDO)
                sounds.listening()
                when (val heard = input.listen()) {
                    is Heard.Silence -> {
                        val waiting = pending
                        if (waiting != null && !remindedAfterSilence) {
                            remindedAfterSilence = true
                            say(waiting.question)
                            continue
                        }
                        pending = null
                        sounds.done()
                        return
                    }

                    is Heard.Failure -> {
                        if (heard.reason == ListenFailure.BUSY && busyRetries++ < 1) {
                            wait(600)
                            continue
                        }
                        if (handleListenFailure(heard.reason) == Next.END) return
                    }

                    is Heard.Text -> {
                        remindedAfterSilence = false
                        setState(ConversationState.PROCESSANDO)
                        if (handle(heard.alternatives) == Next.END) return
                    }
                }
            }
        } catch (e: CancellationException) {
            speech.stop()
            input.cancel()
            pending = null
            setState(ConversationState.CANCELADA)
            throw e
        } finally {
            if (state != ConversationState.ERRO) setState(ConversationState.INATIVA)
        }
    }

    // ------------------------------------------------------------------ interpretação

    private suspend fun handle(alternatives: List<String>): Next {
        val text = alternatives.firstOrNull().orEmpty()
        val waiting = pending

        // Respostas pelo nome numa lista: "Matheus Marcelino", "o WhatsApp".
        if (waiting is Pending.ChooseContact) {
            matchOption(alternatives, waiting.options.map { it.name })?.let { index ->
                pending = null
                return executeCall(waiting.options[index])
            }
        }
        if (waiting is Pending.ChooseApp) {
            matchOption(alternatives, waiting.options.map { it.label })?.let { index ->
                pending = null
                return launchApp(waiting.options[index])
            }
        }

        val expectingChoice = waiting is Pending.ChooseContact || waiting is Pending.ChooseMedia ||
            waiting is Pending.ChooseApp
        var intent = IntentParser.parse(text, expectingChoice)
        if (intent is BelaIntent.Ask) {
            // O reconhecedor às vezes acerta o comando só na 2ª ou 3ª alternativa.
            intent = alternatives.drop(1)
                .map { IntentParser.parse(it, expectingChoice) }
                .firstOrNull { it !is BelaIntent.Ask && it !is BelaIntent.Empty }
                ?: intent
        }

        if (isConversationControl(intent) || waiting == null) return dispatch(intent)
        return handlePending(waiting, intent, text)
    }

    private fun isConversationControl(intent: BelaIntent) = when (intent) {
        BelaIntent.Stop, BelaIntent.Cancel, BelaIntent.Repeat, BelaIntent.NotUnderstood,
        BelaIntent.SpeakSlower, BelaIntent.SpeakFaster, BelaIntent.Help, BelaIntent.Back,
        BelaIntent.Goodbye, BelaIntent.Capabilities -> true
        else -> false
    }

    private suspend fun handlePending(waiting: Pending, intent: BelaIntent, text: String): Next {
        return when (waiting) {
            is Pending.ConfirmCall -> when (intent) {
                // "liga", "ligue" respondendo à pergunta também confirmam.
                BelaIntent.Yes, BelaIntent.CallWithoutName -> { pending = null; executeCall(waiting.contact) }
                BelaIntent.No -> { pending = null; say("Tudo bem, não vou ligar."); Next.END }
                is BelaIntent.Ask -> askAgain(waiting, "Não entendi.")
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.ChooseContact -> when (intent) {
                is BelaIntent.Choice -> pick(waiting.options, intent.index, waiting)?.let {
                    pending = null
                    executeCall(it)
                } ?: Next.LISTEN
                BelaIntent.No -> { pending = null; say("Tudo bem, não vou ligar."); Next.END }
                is BelaIntent.Ask -> askAgain(waiting, "Não entendi qual pessoa.")
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.AskCallName -> when (intent) {
                BelaIntent.No -> { pending = null; say("Tudo bem, não vou ligar."); Next.END }
                is BelaIntent.Call -> { pending = null; startCall(intent.name) }
                is BelaIntent.Ask -> { pending = null; startCall(IntentParser.clean(text)) }
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.ChooseMedia -> when (intent) {
                is BelaIntent.Choice -> {
                    val batch = currentBatch(waiting.list)
                    pick(batch, intent.index, waiting)?.let { item ->
                        pending = null
                        playItem(waiting.list, waiting.list.items.indexOf(item))
                    } ?: Next.LISTEN
                }
                BelaIntent.Yes -> { pending = null; playItem(waiting.list, waiting.list.batchStart) }
                BelaIntent.Another, BelaIntent.NextMedia -> {
                    pending = null
                    waiting.list.batchStart += BATCH_SIZE
                    offerBatch(waiting.list)
                }
                BelaIntent.No -> { pending = null; say("Tudo bem."); Next.END }
                is BelaIntent.Ask -> askAgain(waiting, "Não entendi qual.")
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.AskMediaQuery -> when (intent) {
                BelaIntent.No -> { pending = null; say("Tudo bem."); Next.END }
                is BelaIntent.PlayMedia -> { pending = null; playMedia(intent.query) }
                is BelaIntent.Ask -> {
                    pending = null
                    playMedia(IntentParser.cleanMediaQuery(IntentParser.clean(text)))
                }
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.ChooseApp -> when (intent) {
                is BelaIntent.Choice -> pick(waiting.options, intent.index, waiting)?.let {
                    pending = null
                    launchApp(it)
                } ?: Next.LISTEN
                BelaIntent.No -> { pending = null; say("Tudo bem."); Next.END }
                is BelaIntent.Ask -> askAgain(waiting, "Não entendi qual aplicativo.")
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.ConfirmAnswer -> when (intent) {
                BelaIntent.Yes, BelaIntent.AnswerCall -> { pending = null; answerCall() }
                BelaIntent.No, BelaIntent.DeclineCall -> { pending = null; declineCall() }
                is BelaIntent.Ask -> askAgain(waiting, "Não entendi.")
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.SearchResults -> when (intent) {
                BelaIntent.Yes, BelaIntent.Details -> { pending = null; readSearchDetails(waiting.search) }
                BelaIntent.Another, BelaIntent.NextMedia -> { pending = null; readNextResult(waiting.search) }
                BelaIntent.OpenInBrowser -> { pending = null; openSearchInBrowser(waiting.search) }
                BelaIntent.No -> { pending = null; say("Tudo bem. Se quiser, faça outra pergunta."); Next.LISTEN }
                else -> { pending = null; dispatch(intent) } // pergunta de acompanhamento
            }

            is Pending.BibleReading -> when (intent) {
                BelaIntent.Explain, BelaIntent.Yes -> { pending = null; explainBible(waiting.session) }
                BelaIntent.Another, BelaIntent.NextMedia -> { pending = null; nextVerse(waiting.session) }
                BelaIntent.Details -> {
                    pending = null
                    val session = waiting.session
                    if (session.explanationDetails.isNotBlank()) {
                        say(session.explanationDetails)
                        session.explanationDetails = ""
                        ask(Pending.BibleReading(session, bibleFollowUp(session)))
                    } else {
                        readBibleChapter(session.book, session.chapter, session.verses, from = 1)
                    }
                }
                BelaIntent.NextChapter -> { pending = null; nextChapter(waiting.session) }
                BelaIntent.No -> { pending = null; say("Tudo bem."); Next.LISTEN }
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.BibleContinue -> when (intent) {
                BelaIntent.Yes, BelaIntent.Another, BelaIntent.Details, BelaIntent.ResumeMedia -> {
                    pending = null
                    val s = waiting.session
                    readBibleChapter(s.book, s.chapter, s.verses, from = s.last + 1)
                }
                BelaIntent.No -> {
                    pending = null
                    ask(Pending.BibleReading(waiting.session, "Tudo bem, parei. " + bibleFollowUp(waiting.session)))
                }
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.SearchFailed -> when (intent) {
                BelaIntent.Yes, BelaIntent.Retry -> { pending = null; answerQuestion(waiting.spokenQuestion) }
                BelaIntent.OpenInBrowser -> { pending = null; openWebSearch(waiting.spokenQuestion) }
                BelaIntent.No -> { pending = null; say("Tudo bem."); Next.END }
                else -> { pending = null; dispatch(intent) }
            }

            is Pending.ContinueReading -> when (intent) {
                BelaIntent.Yes -> { pending = null; readChunked(waiting.remaining) }
                BelaIntent.No -> { pending = null; say("Tudo bem, parei a leitura."); Next.END }
                else -> { pending = null; dispatch(intent) }
            }
        }
    }

    private suspend fun dispatch(intent: BelaIntent): Next = when (intent) {
        BelaIntent.Stop -> { speech.stop(); pending = null; Next.END }
        BelaIntent.Cancel -> { pending = null; say("Tudo bem, cancelei."); Next.END }
        BelaIntent.Repeat -> repeatLast(slower = false)
        BelaIntent.NotUnderstood -> repeatLast(slower = true)
        BelaIntent.SpeakSlower -> changeRate(-RATE_STEP)
        BelaIntent.SpeakFaster -> changeRate(RATE_STEP)
        BelaIntent.Help -> help()
        BelaIntent.Back -> {
            pending = null
            say("Tudo bem, voltei ao começo. O que você quer fazer?")
            Next.LISTEN
        }
        BelaIntent.Capabilities -> { say(CAPABILITIES_TEXT); Next.LISTEN }
        BelaIntent.Goodbye -> { pending = null; say("De nada! Quando precisar, é só me chamar."); Next.END }

        BelaIntent.Yes, BelaIntent.No, is BelaIntent.Choice -> {
            say("Não tenho nenhuma pergunta esperando resposta. O que você quer fazer?")
            Next.LISTEN
        }
        BelaIntent.Another -> playAnother()
        BelaIntent.Details -> lastSearch?.let { readSearchDetails(it) }
            ?: run { say("Não tenho nada para detalhar agora. O que você quer saber?"); Next.LISTEN }
        BelaIntent.OpenInBrowser -> lastSearch?.let { openSearchInBrowser(it) }
            ?: run { say("Ainda não fiz nenhuma pesquisa para abrir. O que você quer pesquisar?"); Next.LISTEN }
        BelaIntent.Retry -> lastSearch?.let { answerQuestion(it.spokenQuestion) }
            ?: run { say("O que você quer que eu tente de novo?"); Next.LISTEN }

        is BelaIntent.Call -> startCall(intent.name)
        BelaIntent.CallWithoutName -> ask(Pending.AskCallName("Para quem você quer ligar?"))
        is BelaIntent.CallEmergency -> ask(
            Pending.ConfirmCall(
                Contact(intent.service, intent.number),
                "Quer que eu ligue para ${intent.service}, número ${PtBr.spellDigits(intent.number)}?"
            )
        )
        BelaIntent.AnswerCall -> answerCall()
        BelaIntent.DeclineCall -> declineCall()
        BelaIntent.HangUp -> hangUp()
        BelaIntent.WhoIsCalling -> whoIsCalling()

        is BelaIntent.PlayMedia -> playMedia(intent.query)
        BelaIntent.PauseMedia -> { media.pause(); say("Pedi para pausar."); Next.END }
        BelaIntent.ResumeMedia -> { say("Vou continuar."); media.resume(); Next.END }
        BelaIntent.StopMedia -> { media.stop(); say("Pedi para parar."); Next.END }
        BelaIntent.NextMedia -> playAnother()

        BelaIntent.Time -> {
            val t = now()
            say(PtBr.timePhrase(t.hour, t.minute))
            Next.LISTEN
        }
        BelaIntent.Date -> { say(PtBr.datePhrase(now().toLocalDate())); Next.LISTEN }
        BelaIntent.VolumeUp -> {
            val level = device.raiseVolume()
            say(if (level >= 100) "O volume já está no máximo." else "Aumentei. O volume está em $level por cento.")
            Next.LISTEN
        }
        BelaIntent.VolumeDown -> {
            val level = device.lowerVolume()
            say(if (level <= 0) "O volume está no mínimo." else "Diminuí. O volume está em $level por cento.")
            Next.LISTEN
        }
        is BelaIntent.OpenApp -> openApp(intent.name)
        is BelaIntent.ReadBible -> readBible(intent.reference)
        BelaIntent.Explain -> lastBible?.let { explainBible(it) }
            ?: run { say("O que você quer que eu explique? Peça um versículo, por exemplo: leia João 3, 16."); Next.LISTEN }
        BelaIntent.NextChapter -> lastBible?.let { nextChapter(it) }
            ?: run { say("Qual livro e capítulo da Bíblia você quer ouvir?"); Next.LISTEN }
        BelaIntent.ReadShared -> readShared()

        is BelaIntent.Ask -> answerQuestion(intent.question)
        BelaIntent.Empty -> { say("Não ouvi direito. Pode repetir?"); Next.LISTEN }
    }

    // ------------------------------------------------------------------ conversa

    private suspend fun repeatLast(slower: Boolean): Next {
        if (lastSpoken.isBlank()) {
            say("Ainda não falei nada nesta conversa. Em que posso ajudar?")
        } else {
            say(lastSpoken, remember = false, rate = if (slower) 0.8f else 1f)
        }
        return Next.LISTEN
    }

    private suspend fun changeRate(delta: Float): Next {
        val newRate = (speech.rate + delta).coerceIn(MIN_RATE, MAX_RATE)
        if (newRate == speech.rate) {
            say(if (delta < 0) "Essa já é a minha fala mais devagar." else "Essa já é a minha fala mais rápida.")
            return Next.LISTEN
        }
        speech.rate = newRate
        prefs.speechRate = newRate
        say(if (delta < 0) "Combinado, vou falar mais devagar." else "Combinado, vou falar mais rápido.")
        return Next.LISTEN
    }

    private suspend fun help(): Next {
        val waiting = pending
        if (waiting != null) {
            say("Eu fiz uma pergunta e estou esperando sua resposta. ${waiting.question} Se quiser desistir, diga cancelar.")
            return Next.LISTEN
        }
        say(
            "Claro. Você pode falar comigo do seu jeito. Por exemplo: ligue para alguém, que horas são, " +
                "quero ouvir um louvor, ou fazer qualquer pergunta. Para me interromper, toque na tela. " +
                "Para ouvir de novo, diga repita. O que você quer fazer?"
        )
        return Next.LISTEN
    }

    private suspend fun ask(question: Pending): Next {
        pending = question
        say(question.question)
        return Next.LISTEN
    }

    private suspend fun askAgain(waiting: Pending, prefix: String): Next {
        say("$prefix ${waiting.question}")
        return Next.LISTEN
    }

    private suspend fun <T> pick(options: List<T>, index: Int, waiting: Pending): T? {
        val resolved = if (index == -1) options.lastIndex else index
        val option = options.getOrNull(resolved)
        if (option == null) {
            say("Só tenho ${options.size} opções. ${waiting.question}")
        }
        return option
    }

    /** Procura qual opção da lista foi dita pelo nome (ex.: "Matheus Marcelino"). */
    private fun matchOption(alternatives: List<String>, options: List<String>): Int? {
        val normalizedOptions = options.map { TextNormalizer.normalize(it) }
        for (alternative in alternatives) {
            val said = IntentParser.clean(alternative)
            if (said.length < 3) continue
            val exact = normalizedOptions.indexOfFirst { it == said }
            if (exact >= 0) return exact
            val contained = normalizedOptions.withIndex().filter { (_, option) ->
                option.contains(said) || (said.contains(option) && option.length >= 3)
            }
            if (contained.size == 1) return contained.first().index
        }
        return null
    }

    // ------------------------------------------------------------------ ligações

    private suspend fun startCall(name: String): Next {
        if (name.isBlank()) return ask(Pending.AskCallName("Para quem você quer ligar?"))
        if (!ensurePermission(PermissionGroup.CONTACTS, "Para procurar seus contatos, preciso de permissão para acessar a agenda.")) {
            return Next.END
        }
        setState(ConversationState.EXECUTANDO_ACAO)
        var found = contacts.findByName(name)
        if (found.isEmpty()) {
            val simpler = name.replace(Regex("^(?:meu|minha|seu|sua|o|a|dona|senhor|senhora)\\s+"), "")
            if (simpler != name && simpler.isNotBlank()) found = contacts.findByName(simpler)
        }
        return when {
            found.isEmpty() -> ask(
                Pending.AskCallName("Não encontrei ninguém chamado $name na sua agenda. Para quem você quer ligar?")
            )
            found.size == 1 -> ask(
                Pending.ConfirmCall(found[0], "Encontrei ${describe(found[0], found)}. Quer que eu ligue?")
            )
            found.size <= MAX_SPOKEN_OPTIONS -> {
                val list = found.mapIndexed { i, c -> "o ${PtBr.ordinal(i)} é ${describe(c, found)}" }
                ask(
                    Pending.ChooseContact(
                        found,
                        "Encontrei ${found.size} contatos. ${PtBr.joinSpoken(list).replaceFirstChar { it.uppercase() }}. Qual você quer chamar?"
                    )
                )
            }
            else -> ask(
                Pending.AskCallName("Encontrei muitos contatos com o nome $name. Fale o nome completo da pessoa.")
            )
        }
    }

    /** Nome do contato; se houver nomes repetidos, acrescenta o tipo ou o final do número. */
    private fun describe(contact: Contact, all: List<Contact>): String {
        val sameName = all.count { it.name.equals(contact.name, ignoreCase = true) } > 1
        if (!sameName) return contact.name
        val sameLabel = all.count { it.name.equals(contact.name, ignoreCase = true) && it.label == contact.label } > 1
        return if (contact.label != null && !sameLabel) {
            "${contact.name}, ${contact.label}"
        } else {
            "${contact.name}, número terminado em ${PtBr.spellDigits(contact.numberEnding)}"
        }
    }

    private suspend fun executeCall(contact: Contact): Next {
        if (!ensurePermission(PermissionGroup.PHONE, "Para fazer a ligação, preciso de permissão para fazer e gerenciar chamadas.")) {
            return Next.END
        }
        say("Vou ligar para ${contact.name}.")
        setState(ConversationState.EXECUTANDO_ACAO)
        when (phone.placeCall(contact.number)) {
            PlaceCallResult.STARTED -> {
                val started = phone.awaitState(CallState.OFFHOOK, CALL_START_TIMEOUT_MS)
                if (!started && phone.callState() != CallState.UNKNOWN) {
                    say(
                        "Não consegui confirmar que a ligação começou. Se o discador abriu, o botão de ligar " +
                            "fica na parte de baixo da tela, no meio."
                    )
                }
            }
            PlaceCallResult.OPENED_DIALER -> say(
                "Não consegui ligar direto, então abri o discador com o número de ${contact.name}. " +
                    "O botão de ligar fica na parte de baixo da tela, no meio."
            )
            PlaceCallResult.FAILED -> return fail("Não consegui fazer a ligação. Tente de novo daqui a pouco.")
        }
        return Next.END
    }

    private suspend fun answerCall(): Next {
        if (!ensurePermission(PermissionGroup.PHONE, "Para atender ligações, preciso de permissão para gerenciar chamadas.")) {
            return Next.END
        }
        return when (phone.callState()) {
            CallState.RINGING -> {
                setState(ConversationState.EXECUTANDO_ACAO)
                phone.answer()
                if (!phone.awaitState(CallState.OFFHOOK, ANSWER_TIMEOUT_MS)) {
                    say("Não consegui atender por aqui. Você pode atender pela tela do telefone.")
                }
                Next.END
            }
            CallState.OFFHOOK -> { say("Você já está em uma ligação."); Next.END }
            CallState.IDLE -> { say("Não tem nenhuma ligação tocando agora."); Next.END }
            CallState.UNKNOWN -> fail("Não consegui verificar se tem uma ligação tocando.")
        }
    }

    private suspend fun declineCall(): Next {
        if (!ensurePermission(PermissionGroup.PHONE, "Para recusar ligações, preciso de permissão para gerenciar chamadas.")) {
            return Next.END
        }
        if (phone.callState() != CallState.RINGING) {
            say("Não tem nenhuma ligação tocando agora.")
            return Next.END
        }
        setState(ConversationState.EXECUTANDO_ACAO)
        phone.endCall()
        say(
            if (phone.awaitState(CallState.IDLE, ANSWER_TIMEOUT_MS)) "Recusei a ligação."
            else "Não consegui recusar a ligação por aqui."
        )
        return Next.END
    }

    private suspend fun hangUp(): Next {
        if (!ensurePermission(PermissionGroup.PHONE, "Para desligar ligações, preciso de permissão para gerenciar chamadas.")) {
            return Next.END
        }
        return when (phone.callState()) {
            CallState.RINGING -> declineCall()
            CallState.OFFHOOK -> {
                setState(ConversationState.EXECUTANDO_ACAO)
                phone.endCall()
                say(
                    if (phone.awaitState(CallState.IDLE, ANSWER_TIMEOUT_MS)) "Ligação encerrada."
                    else "Não consegui desligar por aqui. Você pode desligar pela tela do telefone."
                )
                Next.END
            }
            CallState.IDLE -> { say("Não tem nenhuma ligação em andamento."); Next.END }
            CallState.UNKNOWN -> fail("Não consegui verificar se tem uma ligação em andamento.")
        }
    }

    private suspend fun whoIsCalling(): Next {
        if (!ensurePermission(PermissionGroup.PHONE, "Para saber das ligações, preciso de permissão de telefone.")) {
            return Next.END
        }
        if (phone.callState() != CallState.RINGING) {
            say("Não tem nenhuma ligação chegando agora.")
            return Next.END
        }
        val number = phone.ringingNumber()
        val who = when {
            number == null && !permissions.has(PermissionGroup.CALL_LOG) ->
                "Está chegando uma ligação, mas eu não tenho permissão para ver o número."
            number == null -> "Está chegando uma ligação de número oculto ou que não consegui identificar."
            !permissions.has(PermissionGroup.CONTACTS) ->
                "Está chegando uma ligação do número terminado em ${PtBr.spellDigits(number.filter { it.isDigit() }.takeLast(4))}."
            else -> contacts.nameForNumber(number)?.let { "Quem está ligando é $it." }
                ?: "É um número que não está na sua agenda, terminado em ${PtBr.spellDigits(number.filter { it.isDigit() }.takeLast(4))}."
        }
        return ask(Pending.ConfirmAnswer("$who Quer atender? Diga sim para atender, ou não para recusar."))
    }

    // ------------------------------------------------------------------ mídia

    private suspend fun playMedia(query: String): Next {
        if (query.isBlank()) {
            return ask(Pending.AskMediaQuery("O que você quer ouvir? Pode dizer uma música, um cantor ou um louvor."))
        }
        if (!device.isOnline()) {
            say("Para tocar músicas eu preciso de internet, e o celular parece estar sem conexão.")
            return Next.END
        }
        setState(ConversationState.EXECUTANDO_ACAO)
        return when (val result = media.search(query)) {
            is MediaSearchResult.Found -> {
                if (result.items.isEmpty()) {
                    ask(Pending.AskMediaQuery("Não encontrei nada para $query. Quer tentar com outras palavras?"))
                } else {
                    val list = MediaList(result.items)
                    lastMedia = list
                    offerBatch(list)
                }
            }
            MediaSearchResult.NotConfigured -> openYoutubeSearch(
                query,
                "A pesquisa do YouTube ainda não está configurada no aplicativo, então eu não consigo ler os resultados."
            )
            MediaSearchResult.Failure -> openYoutubeSearch(query, "Não consegui pesquisar os resultados agora.")
        }
    }

    private fun currentBatch(list: MediaList): List<MediaItem> =
        list.items.drop(list.batchStart).take(BATCH_SIZE)

    private suspend fun offerBatch(list: MediaList): Next {
        val batch = currentBatch(list)
        if (batch.isEmpty()) {
            return ask(Pending.AskMediaQuery("Não tenho mais opções para essa busca. O que mais você quer ouvir?"))
        }
        val spoken = batch.mapIndexed { i, item -> "${PtBr.ordinal(i)}: ${item.title}, do canal ${item.channel}" }
        return ask(
            Pending.ChooseMedia(
                list,
                "Encontrei. ${spoken.joinToString(". ")}. Qual você quer ouvir? Pode dizer o primeiro, o segundo, ou pedir outros."
            )
        )
    }

    private suspend fun playItem(list: MediaList, index: Int): Next {
        val item = list.items.getOrNull(index) ?: return fail("Não encontrei essa opção.")
        list.playedIndex = index
        say("Vou tocar ${item.title}.")
        setState(ConversationState.EXECUTANDO_ACAO)
        if (!media.play(item)) return fail("Não consegui abrir o YouTube.")
        wait(PLAYBACK_CHECK_DELAY_MS)
        if (!media.isPlaying()) {
            say("O vídeo abriu, mas ainda não começou a tocar. Pode ser preciso tocar uma vez no meio da tela.")
        }
        return Next.END
    }

    private suspend fun playAnother(): Next {
        val list = lastMedia
        if (list != null && list.playedIndex >= 0) {
            val next = list.playedIndex + 1
            if (next < list.items.size) return playItem(list, next)
            return ask(Pending.AskMediaQuery("Já toquei todas as opções dessa busca. O que mais você quer ouvir?"))
        }
        media.next()
        say("Pedi para passar para a próxima.")
        return Next.END
    }

    private suspend fun openYoutubeSearch(query: String, reason: String): Next {
        say("$reason Vou abrir a busca do YouTube por $query; os resultados vão aparecer na tela.")
        if (!media.openSearch(query)) return fail("Não consegui abrir o YouTube.")
        return Next.END
    }

    // ------------------------------------------------------------------ apps, leitura, pesquisa

    private suspend fun openApp(name: String): Next {
        setState(ConversationState.EXECUTANDO_ACAO)
        val found = apps.find(name)
        return when {
            found.isEmpty() -> { say("Não encontrei um aplicativo chamado $name."); Next.LISTEN }
            found.size == 1 -> launchApp(found[0])
            else -> {
                val options = found.take(MAX_SPOKEN_OPTIONS)
                val list = options.mapIndexed { i, app -> "o ${PtBr.ordinal(i)} é ${app.label}" }
                ask(
                    Pending.ChooseApp(
                        options,
                        "Encontrei ${options.size} aplicativos. ${PtBr.joinSpoken(list).replaceFirstChar { it.uppercase() }}. Qual você quer abrir?"
                    )
                )
            }
        }
    }

    private suspend fun launchApp(app: InstalledApp): Next {
        say("Vou abrir o ${app.label}.")
        if (!apps.launch(app)) return fail("Não consegui abrir o ${app.label}.")
        return Next.END
    }

    private suspend fun readShared(): Next {
        val text = sharedText
        if (text.isNullOrBlank()) {
            say(
                "Ainda não recebi nenhum texto. Para eu ler uma mensagem ou notícia, no outro aplicativo " +
                    "toque em Compartilhar e escolha Bela."
            )
            return Next.LISTEN
        }
        return readChunked(text)
    }

    private suspend fun readChunked(text: String): Next {
        val (chunk, rest) = splitChunk(text, READ_CHUNK_CHARS)
        say(chunk)
        if (rest.isBlank()) {
            say("Fim do texto.")
            return Next.LISTEN
        }
        return ask(Pending.ContinueReading(rest, "Quer que eu continue lendo?"))
    }

    private suspend fun answerQuestion(question: String): Next {
        if (!device.isOnline()) {
            say(
                "Para pesquisar eu preciso de internet, e o celular não está conectado a nenhuma rede agora. " +
                    "Sem internet, eu ainda posso dizer as horas, fazer ligações e abrir aplicativos."
            )
            return Next.END
        }
        say("Vou pesquisar. Só um instante.")
        setState(ConversationState.PROCESSANDO)
        return when (val result = search.ask(question, history.takeLast(HISTORY_TURNS))) {
            is SearchResult.Success -> {
                val session = SearchSession(question, result.answer)
                lastSearch = session
                history += Turn(question, result.answer.summary)
                say(firstReading(result.answer))
                ask(Pending.SearchResults(session, followUpQuestion(session)))
            }
            SearchResult.NotConfigured -> searchFailed(
                question,
                "O serviço de pesquisa precisa ser configurado neste aplicativo.",
                allowRetry = false
            )
            is SearchResult.Failure -> searchFailed(question, errorMessage(result.error), allowRetry = result.error.retryable)
        }
    }

    private fun firstReading(answer: Answer): String = when (answer.provider) {
        SearchProvider.GEMINI -> {
            val source = answer.sources.firstOrNull()
                ?.let { " A informação é de ${it.title}." }
                ?: " Essa resposta não veio de uma fonte que eu tenha conferido."
            answer.summary + source
        }
        SearchProvider.WIKIPEDIA -> {
            val title = answer.results.firstOrNull()?.title
            "Encontrei na Wikipédia" + (title?.let { ", em $it" } ?: "") + ". " + answer.summary
        }
        SearchProvider.NEWS -> {
            val first = answer.results.first()
            "Estas são notícias do Google Notícias. A primeira: ${first.title}" +
                (if (first.site.isNotBlank()) ", de ${first.site}." else ".")
        }
    }

    private fun followUpQuestion(session: SearchSession): String {
        val options = mutableListOf<String>()
        if (!session.detailsRead && session.answer.details.isNotBlank()) options += "mais detalhes"
        if (session.index + 1 < session.answer.results.size) {
            options += if (session.answer.provider == SearchProvider.NEWS) "a próxima notícia" else "o próximo resultado"
        }
        if (session.current?.url?.isNotBlank() == true) options += "abrir no navegador"
        if (options.isEmpty()) return "Quer perguntar mais alguma coisa?"
        return "Quer ouvir ${PtBr.joinSpoken(options).replace(" e abrir", ", ou abrir").replace(" e ", " ou ")}?"
    }

    private suspend fun readSearchDetails(session: SearchSession): Next {
        val details = session.answer.details
        val snippet = session.current?.snippet.orEmpty()
        when {
            details.isNotBlank() && !session.detailsRead -> {
                session.detailsRead = true
                val (chunk, rest) = splitChunk(details, READ_CHUNK_CHARS)
                if (rest.isNotBlank()) return readChunked(details)
                say(chunk)
            }
            snippet.isNotBlank() -> say(snippet)
            else -> say("Não tenho mais detalhes desse resultado. Posso abrir a página no navegador, para o TalkBack ler.")
        }
        return ask(Pending.SearchResults(session, followUpQuestion(session)))
    }

    private suspend fun readNextResult(session: SearchSession): Next {
        if (session.index + 1 >= session.answer.results.size) {
            say("Não tenho mais resultados para essa pesquisa.")
            return ask(Pending.SearchResults(session, followUpQuestion(session)))
        }
        session.index++
        session.detailsRead = true // detalhes longos eram do primeiro resultado
        val result = session.current!!
        val label = if (session.answer.provider == SearchProvider.NEWS) "Próxima notícia" else "Resultado ${session.index + 1}"
        val from = if (result.site.isNotBlank() && result.site != result.title) ", de ${result.site}" else ""
        val snippet = if (session.answer.provider == SearchProvider.NEWS || result.snippet.isBlank()) "" else " ${result.snippet}"
        say("$label: ${result.title}$from.$snippet")
        return ask(Pending.SearchResults(session, followUpQuestion(session)))
    }

    private suspend fun openSearchInBrowser(session: SearchSession): Next {
        val url = session.current?.url
        if (url.isNullOrBlank()) return openWebSearch(session.spokenQuestion)
        say("Vou abrir a página no navegador. Para voltar a falar comigo, toque na tela da Bela ou segure o botão de ligar.")
        if (!device.openUrl(url)) return fail("Não consegui abrir o navegador.")
        return Next.END
    }

    /** Alternativa quando a pesquisa falha: os resultados aparecem no navegador (não foram lidos pela Bela). */
    private suspend fun openWebSearch(spokenQuestion: String): Next {
        val query = SearchQuery.from(spokenQuestion).question
        say(
            "Vou abrir a pesquisa do Google no navegador. Os resultados vão aparecer na tela; eu não li esses resultados, " +
                "mas o TalkBack pode ler para você."
        )
        if (!device.openUrl(googleSearchUrl(query))) return fail("Não consegui abrir o navegador.")
        return Next.END
    }

    private suspend fun searchFailed(question: String, message: String, allowRetry: Boolean): Next {
        sounds.error()
        val offer = if (allowRetry) {
            "Quer que eu tente de novo, ou que eu abra a pesquisa no navegador?"
        } else {
            "Quer que eu abra a pesquisa no navegador?"
        }
        say(message)
        return ask(Pending.SearchFailed(question, offer))
    }

    // ------------------------------------------------------------------ Bíblia

    private suspend fun readBible(ref: BibleReference): Next {
        val verses = bible.chapter(ref.book.id, ref.chapter)
            ?: return fail("Não consegui abrir esse trecho da Bíblia.")
        val start = ref.verseStart ?: return readBibleChapter(ref.book, ref.chapter, verses, from = 1)
        if (start > verses.size) {
            say("${BibleReference(ref.book, ref.chapter).spoken()} tem ${verses.size} versículos. Qual versículo você quer ouvir?")
            return Next.LISTEN
        }
        val end = minOf(ref.verseEnd ?: start, verses.size)
        val session = BibleSession(ref.book, ref.chapter, verses, start, end)
        lastBible = session
        say(translationIntro() + BibleReference(ref.book, ref.chapter, start, end).spoken() + ".")
        say(versesForSpeech(verses, start, end))
        return ask(Pending.BibleReading(session, bibleFollowUp(session)))
    }

    /** Lê o capítulo em partes de [BIBLE_CHUNK_VERSES] versículos, perguntando se continua. */
    private suspend fun readBibleChapter(book: BibleBook, chapter: Int, verses: List<String>, from: Int): Next {
        val to = minOf(from + BIBLE_CHUNK_VERSES - 1, verses.size)
        val session = BibleSession(book, chapter, verses, from, to)
        lastBible = session
        if (from == 1) say(translationIntro() + BibleReference(book, chapter).spoken() + ".")
        say(versesForSpeech(verses, from, to, numbered = true))
        if (to < verses.size) return ask(Pending.BibleContinue(session, "Quer que eu continue lendo?"))
        session.first = 1
        say("Fim do capítulo.")
        return ask(Pending.BibleReading(session, bibleFollowUp(session)))
    }

    private suspend fun nextVerse(session: BibleSession): Next {
        if (session.last >= session.verses.size) return nextChapter(session)
        val n = session.last + 1
        val next = BibleSession(session.book, session.chapter, session.verses, n, n)
        lastBible = next
        say("Versículo $n. " + versesForSpeech(session.verses, n, n))
        return ask(Pending.BibleReading(next, bibleFollowUp(next)))
    }

    private suspend fun nextChapter(session: BibleSession): Next {
        val chapter = session.chapter + 1
        if (chapter > session.book.chapters) {
            say("Esse é o último capítulo de ${session.book.spokenName}. Qual livro você quer ouvir agora?")
            return Next.LISTEN
        }
        val verses = bible.chapter(session.book.id, chapter) ?: return fail("Não consegui abrir esse trecho da Bíblia.")
        return readBibleChapter(session.book, chapter, verses, from = 1)
    }

    private suspend fun explainBible(session: BibleSession): Next {
        if (!device.isOnline()) {
            say("Para buscar uma explicação eu preciso de internet. A leitura da Bíblia funciona sem internet.")
            return ask(Pending.BibleReading(session, bibleFollowUp(session)))
        }
        say("Vou buscar uma explicação. Só um instante.")
        setState(ConversationState.PROCESSANDO)
        val text = (session.first..session.last).joinToString(" ") { "$it ${session.verses[it - 1]}" }
        when (val result = explainer.explain(session.reference.spoken(), text)) {
            is SearchResult.Success -> {
                say("Esta explicação foi feita por inteligência artificial; não faz parte da Bíblia. " + result.answer.summary)
                session.explanationDetails = result.answer.details
            }
            SearchResult.NotConfigured -> say("A explicação precisa do serviço de inteligência artificial, que não está configurado neste aplicativo.")
            is SearchResult.Failure -> {
                sounds.error()
                say(explainErrorMessage(result.error))
            }
        }
        return ask(Pending.BibleReading(session, bibleFollowUp(session)))
    }

    private fun bibleFollowUp(session: BibleSession): String {
        val options = mutableListOf("uma explicação")
        if (session.explanationDetails.isNotBlank()) options.add(0, "mais detalhes da explicação")
        if (session.last < session.verses.size) options += "o próximo versículo"
        if (!session.wholeChapter) options += "o capítulo inteiro"
        if (session.wholeChapter || session.last >= session.verses.size) options += "o próximo capítulo"
        return "Quer ${PtBr.joinSpoken(options).replace(" e ", " ou ")}?"
    }

    private fun translationIntro(): String {
        if (bibleTranslationAnnounced || bible.translationName.isBlank()) return ""
        bibleTranslationAnnounced = true
        return "Na tradução de ${bible.translationName}. "
    }

    private fun versesForSpeech(verses: List<String>, from: Int, to: Int, numbered: Boolean = from != to): String =
        (from..to).joinToString(" ") { n ->
            val text = OldSpelling.forSpeech(verses[n - 1])
            if (numbered) "$n. $text" else text
        }

    // ------------------------------------------------------------------ utilidades

    private suspend fun ensurePermission(group: PermissionGroup, reason: String): Boolean {
        if (permissions.has(group)) return true
        say("$reason Vai aparecer uma janela do Android pedindo autorização. O botão de permitir fica na parte de baixo da janela.")
        val granted = permissions.request(group)
        if (!granted) {
            say("Sem essa permissão eu não consigo fazer isso. Se mudar de ideia, ela pode ser dada nas configurações do aplicativo Bela.")
        }
        return granted
    }

    private suspend fun handleListenFailure(reason: ListenFailure): Next {
        return when (reason) {
            ListenFailure.NO_PERMISSION -> {
                if (ensurePermission(PermissionGroup.MICROPHONE, "Para ouvir você, preciso de permissão para usar o microfone.")) {
                    Next.LISTEN
                } else {
                    setState(ConversationState.ERRO)
                    Next.END
                }
            }
            ListenFailure.NO_NETWORK -> fail(
                "O reconhecimento de voz deste celular precisa de internet, e a conexão parece ter caído. Tente de novo daqui a pouco."
            )
            ListenFailure.UNAVAILABLE -> fail(
                "O reconhecimento de voz não está disponível neste celular. É preciso ter o aplicativo Google instalado e atualizado."
            )
            ListenFailure.BUSY, ListenFailure.OTHER -> fail("Não consegui ouvir direito. Toque na tela para tentar de novo.")
        }
    }

    private suspend fun fail(message: String): Next {
        pending = null
        sounds.error()
        say(message)
        setState(ConversationState.ERRO)
        return Next.END
    }

    private suspend fun say(text: String, remember: Boolean = true, rate: Float = 1f) {
        setState(ConversationState.FALANDO)
        if (remember) lastSpoken = text
        speech.speak(text, rate)
    }

    private fun setState(newState: ConversationState) {
        if (state == newState) return
        state = newState
        onStateChanged(newState)
    }

    companion object {
        const val BATCH_SIZE = 3
        const val MAX_SPOKEN_OPTIONS = 5
        const val RATE_STEP = 0.15f
        const val MIN_RATE = 0.55f
        const val MAX_RATE = 1.75f
        const val CALL_START_TIMEOUT_MS = 6_000L
        const val ANSWER_TIMEOUT_MS = 3_000L
        const val PLAYBACK_CHECK_DELAY_MS = 6_000L
        const val READ_CHUNK_CHARS = 600
        const val HISTORY_TURNS = 4

        val CAPABILITIES_TEXT =
            "Eu posso: ligar para seus contatos, atender ou recusar ligações e dizer quem está ligando; " +
                "tocar músicas e louvores do YouTube; pesquisar na internet e ler a resposta; dizer as horas e a data; " +
                "abrir aplicativos; aumentar ou diminuir o volume; ler a Bíblia, como: leia João 3, 16; e ler textos que você compartilhar comigo. " +
                "Também posso repetir, falar mais devagar ou mais rápido. O que você quer fazer?"

        const val BIBLE_CHUNK_VERSES = 10

        internal fun explainErrorMessage(error: SearchError): String = when (error) {
            SearchError.NO_CONNECTION, SearchError.DNS -> "Não consegui me conectar ao serviço de explicações."
            SearchError.TIMEOUT -> "A explicação demorou demais."
            SearchError.UNAUTHORIZED -> "O serviço de explicações recusou a chave de acesso do aplicativo."
            SearchError.MODEL_UNAVAILABLE -> "O serviço de explicações não está disponível para esta chave."
            SearchError.RATE_LIMITED -> "O limite de uso do serviço de explicações foi atingido."
            else -> "Não consegui buscar uma explicação agora."
        }

        /** Mensagem falada para cada erro real da pesquisa. */
        internal fun errorMessage(error: SearchError): String = when (error) {
            SearchError.NO_CONNECTION -> "Não consegui me conectar ao serviço de pesquisa. A internet do celular parece estar fora do ar."
            SearchError.DNS -> "Não consegui encontrar o endereço do serviço de pesquisa. Pode ser a rede ou o DNS do celular."
            SearchError.TIMEOUT -> "A pesquisa demorou demais."
            SearchError.UNAUTHORIZED -> "O serviço de pesquisa recusou a chave de acesso do aplicativo. A chave precisa ser conferida."
            SearchError.MODEL_UNAVAILABLE -> "O serviço de pesquisa configurado não está mais disponível para esta chave. O aplicativo precisa ser atualizado."
            SearchError.RATE_LIMITED -> "O limite de pesquisas do serviço foi atingido. Daqui a pouco deve voltar a funcionar."
            SearchError.SERVER -> "O serviço de pesquisa está com problemas agora."
            SearchError.BAD_REQUEST -> "O serviço de pesquisa não aceitou o pedido."
            SearchError.EMPTY -> "Não encontrei resultados para essa pergunta."
            SearchError.INVALID_RESPONSE -> "O serviço de pesquisa respondeu de um jeito que eu não entendi."
            SearchError.UNEXPECTED -> "Aconteceu um erro inesperado na pesquisa."
        }

        internal val SearchError.retryable: Boolean
            get() = this in setOf(
                SearchError.NO_CONNECTION, SearchError.DNS, SearchError.TIMEOUT, SearchError.RATE_LIMITED,
                SearchError.SERVER, SearchError.EMPTY, SearchError.INVALID_RESPONSE, SearchError.UNEXPECTED
            )

        internal fun googleSearchUrl(query: String): String =
            "https://www.google.com/search?hl=pt-BR&q=" + java.net.URLEncoder.encode(query, "UTF-8")

        /** Divide no fim de uma frase perto de [limit] caracteres. */
        internal fun splitChunk(text: String, limit: Int): Pair<String, String> {
            val trimmed = text.trim()
            if (trimmed.length <= limit) return trimmed to ""
            val window = trimmed.substring(0, limit)
            val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "), window.lastIndexOf('\n'))
            val end = if (cut > limit / 3) cut + 1 else window.lastIndexOf(' ').takeIf { it > 0 } ?: limit
            return trimmed.substring(0, end).trim() to trimmed.substring(end).trim()
        }
    }
}
