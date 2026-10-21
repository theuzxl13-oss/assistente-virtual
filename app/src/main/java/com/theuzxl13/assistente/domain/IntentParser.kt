package com.theuzxl13.assistente.domain

/**
 * Interpreta a frase reconhecida em português brasileiro e devolve a intenção.
 *
 * Funciona 100% no aparelho (sem internet) e aceita variações naturais ("liga pro João",
 * "quero falar com a Maria", "telefona pra minha filha"). O que não se encaixa em nenhum comando
 * vira [BelaIntent.Ask], que é tratado como pergunta/pesquisa — então ela nunca precisa decorar
 * uma frase exata.
 */
object IntentParser {

    /**
     * @param expectingChoice true quando a Bela acabou de ler uma lista ("o primeiro é..., o
     *   segundo é...") — só então "um", "dois", "o primeiro" viram [BelaIntent.Choice].
     */
    fun parse(raw: String, expectingChoice: Boolean = false): BelaIntent {
        val s = clean(raw)
        if (s.isEmpty()) return BelaIntent.Empty

        // A ordem importa: controles da conversa e respostas curtas primeiro, depois comandos
        // específicos (que horas, volume) antes dos genéricos (tocar, ligar), e por último a
        // pergunta livre.
        if (expectingChoice) parseChoice(s)?.let { return it }

        return when {
            STOP.matches(s) -> BelaIntent.Stop
            CANCEL.matches(s) -> BelaIntent.Cancel
            REPEAT.matches(s) -> BelaIntent.Repeat
            NOT_UNDERSTOOD.containsMatchIn(s) -> BelaIntent.NotUnderstood
            SLOWER.containsMatchIn(s) -> BelaIntent.SpeakSlower
            FASTER.containsMatchIn(s) -> BelaIntent.SpeakFaster
            BACK.matches(s) -> BelaIntent.Back
            GOODBYE.matches(s) -> BelaIntent.Goodbye
            YES.matches(s) -> BelaIntent.Yes
            NO.matches(s) -> BelaIntent.No
            ANOTHER.matches(s) -> BelaIntent.Another
            DETAILS.matches(s) -> BelaIntent.Details
            OPEN_IN_BROWSER.matches(s) -> BelaIntent.OpenInBrowser
            RETRY.matches(s) -> BelaIntent.Retry

            TIME.matches(s) -> BelaIntent.Time
            DATE.containsMatchIn(s) -> BelaIntent.Date
            VOLUME_UP.containsMatchIn(s) -> BelaIntent.VolumeUp
            VOLUME_DOWN.containsMatchIn(s) -> BelaIntent.VolumeDown
            CAPABILITIES.containsMatchIn(s) -> BelaIntent.Capabilities
            HELP.matches(s) -> BelaIntent.Help

            READ_SHARED.matches(s) -> BelaIntent.ReadShared
            WHO_IS_CALLING.containsMatchIn(s) -> BelaIntent.WhoIsCalling
            ANSWER.matches(s) -> BelaIntent.AnswerCall
            DECLINE.matches(s) -> BelaIntent.DeclineCall
            STOP_MEDIA.containsMatchIn(s) -> BelaIntent.StopMedia
            HANG_UP.matches(s) -> BelaIntent.HangUp
            PAUSE_MEDIA.matches(s) -> BelaIntent.PauseMedia
            RESUME_MEDIA.matches(s) -> BelaIntent.ResumeMedia
            NEXT_MEDIA.matches(s) -> BelaIntent.NextMedia

            else -> parseEmergency(s)
                ?: parseCall(s)
                ?: parseMedia(s)
                ?: parseOpenApp(s)
                ?: BelaIntent.Ask(stripWakeWordFromRaw(raw))
        }
    }

    /** Normaliza, tira o "Bela" e as palavras de cortesia que não mudam o sentido do pedido. */
    internal fun clean(raw: String): String {
        var s = TextNormalizer.stripWakeWord(TextNormalizer.normalize(raw))
        s = s.replace(Regex("\\b(?:por favor|porfavor|pra mim|para mim)\\b"), " ")
        s = s.replace(Regex("\\s+(?:bela|bella|ai|viu|ta|tá)$"), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Tira o "Bela," do começo do texto original, mantendo acentos para a pesquisa. */
    internal fun stripWakeWordFromRaw(raw: String): String {
        return raw.replace(
            Regex("^\\s*(?:(?:oi|olá|ola|ei|hey)\\s*,?\\s*)?bel?la\\b\\s*[,!.:]?\\s*", RegexOption.IGNORE_CASE),
            ""
        ).trim()
    }

    private fun parseChoice(s: String): BelaIntent? {
        val match = CHOICE.matchEntire(s) ?: return null
        val index = when (match.groupValues[1]) {
            "primeiro", "primeira", "um", "uma", "1" -> 0
            "segundo", "segunda", "dois", "duas", "2" -> 1
            "terceiro", "terceira", "tres", "3" -> 2
            "quarto", "quarta", "quatro", "4" -> 3
            "quinto", "quinta", "cinco", "5" -> 4
            "ultimo", "ultima" -> -1
            else -> return null
        }
        return BelaIntent.Choice(index)
    }

    private fun parseEmergency(s: String): BelaIntent? {
        val match = EMERGENCY.find(s) ?: return null
        return when (match.groupValues[1]) {
            "policia", "190" -> BelaIntent.CallEmergency("190", "a polícia")
            "bombeiro", "bombeiros", "193" -> BelaIntent.CallEmergency("193", "os bombeiros")
            else -> BelaIntent.CallEmergency("192", "o SAMU")
        }
    }

    private fun parseCall(s: String): BelaIntent? {
        val match = CALL.matchEntire(s) ?: return null
        val name = match.groupValues[1]
            .replace(Regex("^(?:(?:para|pra|pro|com|ao|a|o|os|as|na|no)\\s+)+"), "")
            .replace(Regex("\\s+(?:no|pelo|pelo numero do|do) (?:celular|telefone|fixo|whatsapp|zap)$"), "")
            .replace(Regex("\\s+agora$"), "")
            .trim()
        return if (name.isEmpty()) BelaIntent.CallWithoutName else BelaIntent.Call(name)
    }

    private fun parseMedia(s: String): BelaIntent? {
        val query = PLAY.matchEntire(s)?.groupValues?.get(1)
            ?: SEARCH_MEDIA.matchEntire(s)?.groupValues?.get(1)
            ?: if (YOUTUBE.containsMatchIn(s) && !OPEN_APP.matches(s)) s else null
        query ?: return null
        // "ouvir as notícias" é pedido de notícia, não de música.
        if (Regex("\\bnoticias?\\b").containsMatchIn(query)) return null
        return BelaIntent.PlayMedia(cleanMediaQuery(query))
    }

    internal fun cleanMediaQuery(query: String): String {
        return query
            .replace(YOUTUBE, " ")
            .replace(Regex("\\b(?:abra|abre|abrir|procure|procura|pesquise|pesquisa|busque|busca)\\b"), " ")
            .replace(Regex("^\\s*(?:um|uma|uns|umas|o|a|os|as|algum|alguma|alguns|algumas)\\s+"), " ")
            .replace(Regex("^\\s*(?:musica|musicas|video|videos|cancao|cancoes|som)\\s+(?:de|do|da|dos|das)\\s+"), " ")
            .replace(Regex("^\\s*(?:musica|musicas|video|videos|cancao|cancoes|som)\\s*$"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseOpenApp(s: String): BelaIntent? {
        val match = OPEN_APP.matchEntire(s) ?: return null
        val name = match.groupValues[1].trim()
        return if (name.isEmpty()) null else BelaIntent.OpenApp(name)
    }

    // Prefixos educados que podem vir antes de um comando: "você pode ligar...", "eu quero...".
    private const val POLITE =
        "(?:(?:voce pode|voce poderia|voce consegue|pode|poderia|consegue|da pra|da para|eu quero|quero|queria|eu queria|eu preciso|preciso|gostaria de|eu gostaria de|vamos)\\s+)?"

    private val STOP = Regex(
        "(?:pare|para|parar|chega|silencio|quieta|fica quieta|pare de falar|para de falar|cala a boca|calada|stop|psiu)"
    )
    private val CANCEL = Regex(
        "(?:cancel(?:a|e|ar)|deixa (?:pra|para) la|esquec(?:e|a)|nao quero mais|desisto|nada|deixa)(?: isso| tudo)?"
    )
    private val REPEAT = Regex(
        "(?:pode )?(?:rep(?:e|i)t(?:e|a|ir)|fal(?:a|e) de novo|di(?:z|ga) de novo|de novo|outra vez|como e|como|hein|o que voce (?:disse|falou)|nao ouvi)(?: isso| de novo| outra vez)?"
    )
    private val NOT_UNDERSTOOD = Regex(
        "^(?:eu )?(?:nao entendi|nao compreendi|nao ficou claro|explic(?:a|e) (?:de novo|melhor|outra vez))"
    )
    private val SLOWER = Regex("\\b(?:mais devagar|mais lento|mais lenta|fal(?:a|e) devagar|^devagar$)")
    private val FASTER = Regex("\\b(?:mais rapido|mais rapida|mais depressa|fal(?:a|e) rapido|mais ligeiro)\\b")
    private val BACK = Regex("(?:volt(?:a|e|ar)|volta pro inicio|voltar ao inicio|inicio|comecar de novo|recomec(?:a|ar))")
    private val GOODBYE = Regex(
        "(?:tchau|obrigad[oa]|muito obrigad[oa]|brigad[oa]|valeu|ate logo|ate mais|ate depois|so isso|era so isso|e so isso)"
    )
    private val YES = Regex(
        "(?:sim|pode|pode sim|isso|isso mesmo|isso ai|claro|com certeza|quero|quero sim|confirmo|confirma|correto|certo|exato|ok|okay|beleza|pode ligar|pode ser|esse|essa|esse mesmo|essa mesma|esse ai|essa ai|aham|uhum|positivo|manda|vai)"
    )
    private val NO = Regex(
        "(?:nao|nao quero|nao obrigad[oa]|negativo|errado|nao e esse|nao e essa|nao e|nenhum|nenhuma|nenhum deles|nao e nenhum)"
    )
    private val ANOTHER = Regex(
        "(?:(?:o |a )?proxim[oa] (?:resultado|noticia|fonte|site)|(?:o |a )?proxim[oa]|outr[oa] (?:resultado|noticia|fonte)|outr[oa]|quero (?:ouvir |escutar )?outr[oa]|outra (?:musica|opcao)|outro (?:video|louvor|hino)|mais um|mais uma|tem outr[oa])"
    )
    private val DETAILS = Regex(
        "$POLITE(?:mais detalhes|detalhes|leia tudo|le tudo|leitura completa|a leitura completa|completa|tudo|leia mais|fala mais|fale mais|quero saber mais|continue lendo|continua lendo|explica melhor)(?: por favor)?"
    )
    private val OPEN_IN_BROWSER = Regex(
        "$POLITE(?:abr(?:a|e|ir)|mostr(?:a|e|ar)|ve[jr]a?)(?: (?:isso|esse|essa|este|esta|ele|ela|o resultado|a pagina|o site|a noticia|a pesquisa|os resultados))? (?:no|pelo|na) (?:navegador|chrome|google|internet|tela)|abr(?:a|e|ir) (?:a pagina|o site|o link|a noticia|o resultado|os resultados)"
    )
    private val RETRY = Regex(
        "$POLITE(?:tent(?:a|e|ar) (?:de novo|novamente|outra vez)|pesquis(?:a|e|ar) (?:de novo|novamente|outra vez))"
    )
    private val CHOICE = Regex(
        "(?:(?:o|a|opcao|numero|o numero|a opcao)\\s+)?(primeiro|primeira|segundo|segunda|terceiro|terceira|quarto|quarta|quinto|quinta|ultimo|ultima|um|uma|1|dois|duas|2|tres|3|quatro|4|cinco|5)(?:\\s+(?:opcao|contato|video|musica|resultado|ai|mesmo|mesma|numero))*"
    )

    private val TIME = Regex(
        "(?:(?:voce )?(?:sabe |pode me dizer |me diz |me diga )?(?:que|quantas) horas?(?: (?:sao|e|tem))?|horas|hora certa|me (?:diz|diga|fala|fale) (?:a|as) horas?)(?: agora)?"
    )
    private val DATE = Regex(
        "\\b(?:que dia (?:e|eh) hoje|hoje e que dia|qual (?:e )?a data|data de hoje|dia de hoje|que dia da semana|dia da semana e hoje|em que dia estamos|que dia estamos|que dia e hoje)\\b"
    )
    private val VOLUME_UP = Regex(
        "\\b(?:(?:aument(?:a|e|ar)|sob(?:e|ir)|subir)(?: o)? (?:volume|som)|volume mais alto|fal(?:a|e) mais alto)\\b"
    )
    private val VOLUME_DOWN = Regex(
        "\\b(?:(?:diminu(?:i|a|ir)|abaix(?:a|e|ar)|baix(?:a|e|ar))(?: o)? (?:volume|som)|volume mais baixo|fal(?:a|e) mais baixo)\\b"
    )
    private val CAPABILITIES = Regex(
        "\\b(?:o que|que) (?:voce |vc )?(?:pode|sabe|consegue) fazer|\\bo que (?:eu )?posso (?:fazer|pedir|falar|dizer)|\\bquais (?:sao )?(?:os )?(?:seus )?comandos|\\bo que voce faz\\b|\\bcomo (?:eu )?(?:uso|usar) (?:voce|a bela)"
    )
    private val HELP = Regex(
        "(?:me )?(?:ajud(?:a|e|ar)|socorro)(?: me)?|preciso de ajuda|como funciona|nao sei o que fazer|(?:estou|to|tou) perdida|(?:estou|to|tou) perdido"
    )

    private val READ_SHARED = Regex(
        "$POLITE(?:le|leia|ler)(?: pra mim)? (?:essa|esta|esse|este|o|a|aquela|aquele) (?:noticia|texto|mensagem|documento|artigo)(?: que (?:eu )?(?:mandei|compartilhei|enviei))?"
    )
    private val WHO_IS_CALLING = Regex(
        "^quem (?:e que )?(?:esta|ta|tá)? ?(?:me )?(?:ligando|chamando)|^quem (?:e|eh) que (?:esta|ta) (?:me )?ligando|(?:de quem e|quem e) (?:essa|esta|a) (?:ligacao|chamada)|^quem (?:esta|ta) no telefone"
    )
    private val ANSWER = Regex(
        "$POLITE(?:atend(?:e|a|er)|atende ai)(?: (?:a|essa|esta) (?:ligacao|chamada)| o telefone| ele| ela)?"
    )
    private val DECLINE = Regex(
        "$POLITE(?:recus(?:a|e|ar)|rejeit(?:a|e|ar)|nao (?:vou |quero )?atender|nao atende|ignor(?:a|e|ar))(?: (?:a|essa|esta) (?:ligacao|chamada))?"
    )
    private val HANG_UP = Regex(
        "$POLITE(?:deslig(?:a|ue|ar)|encerr(?:a|e|ar)|termin(?:a|e|ar)|finaliz(?:a|e|ar)|bot(?:a|e) no gancho)(?: (?:a|essa|esta) (?:ligacao|chamada)| o telefone)?(?: agora)?"
    )

    private val MEDIA_WORD = "(?:musica|som|video|louvor|hino|youtube)"
    private val STOP_MEDIA = Regex(
        "\\b(?:par(?:a|e|ar)|deslig(?:a|ue|ar)|desativ(?:a|e|ar)|cancel(?:a|e|ar)|tir(?:a|e|ar)|fech(?:a|e|ar)) (?:a |o |essa |esse |esta |este )?$MEDIA_WORD\\b"
    )
    private val PAUSE_MEDIA = Regex(
        "$POLITE(?:paus(?:a|e|ar)|da (?:um|uma) pausa)(?: (?:a |o |essa |esse )?$MEDIA_WORD)?"
    )
    private val RESUME_MEDIA = Regex(
        "$POLITE(?:continu(?:a|e|ar)|despaus(?:a|e|ar)|volt(?:a|e) a tocar|toca de novo|play|solt(?:a|e))(?: (?:a |o )?$MEDIA_WORD| tocando)?"
    )
    private val NEXT_MEDIA = Regex(
        "$POLITE(?:proxim[oa](?: (?:musica|video|louvor|hino))?|pul(?:a|e|ar)|pass(?:a|e|ar)|avanc(?:a|e|ar))(?: (?:essa|esta|a|o|esse|este) (?:musica|video|louvor|hino))?"
    )

    private val EMERGENCY = Regex(
        "^$POLITE(?:(?:lig(?:a|ue|ar)|cham(?:a|e|ar)|telefon(?:a|e|ar)|disc(?:a|ar)|disque)\\s+(?:(?:para|pra|pro|a|o|os|as)\\s+)*)?(?:(?:o|a|os|as)\\s+)?(samu|ambulancia|policia|bombeiros?|emergencia|190|192|193)\\b"
    )
    private val CALL = Regex(
        "^$POLITE(?:lig(?:a|ue|ar)|telefon(?:a|e|ar)|disc(?:a|ar)|disque|cham(?:a|e|ar)|fa(?:z|ca|zer) uma ligacao|falar com|fala com|ligacao (?:para|pra|pro))\\b\\s*(.*)$"
    )
    private val PLAY = Regex(
        "^$POLITE(?:ouvir|escutar|assistir|toca|tocar|toque|coloca|colocar|coloque|bota|botar|bote|poe|ponha|reproduz|reproduza|reproduzir)\\s+(.+)$"
    )
    private val SEARCH_MEDIA = Regex(
        "^$POLITE(?:procur(?:a|e|ar)|pesquis(?:a|e|ar)|busc(?:a|ar)|busque|ach(?:a|e|ar))\\s+(.*\\b(?:musica|musicas|hino|hinos|louvor|louvores|video|videos|clipe|cancao|cancoes|youtube|harpa)\\b.*)$"
    )
    private val YOUTUBE = Regex("\\b(?:(?:no|do|pelo|pro) )?(?:youtube|you tube|iutubi)\\b")
    private val OPEN_APP = Regex(
        "^$POLITE(?:abr(?:a|e|ir)|entr(?:a|e|ar) (?:no|na)|inici(?:a|e|ar)|execut(?:a|e|ar))\\s+(?:o |a |no |na )?(?:app |aplicativo |programa )?(?:do |da )?(.+)$"
    )
}
