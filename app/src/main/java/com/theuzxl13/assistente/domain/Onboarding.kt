package com.theuzxl13.assistente.domain

import kotlinx.coroutines.CancellationException

/**
 * Configuração inicial guiada só por voz. Cada etapa explica o que vai acontecer antes de abrir
 * qualquer janela do Android, e em qualquer pergunta ela pode dizer "repita" ou "me ajude".
 *
 * Retorna true se chegou ao fim (a configuração não precisa ser repetida).
 */
class Onboarding(
    private val speech: SpeechOutput,
    private val input: SpeechInput,
    private val sounds: SoundFeedback,
    private val permissions: PermissionPort,
    private val device: DevicePort,
    private val prefs: PreferencesPort
) {

    var onStateChanged: (ConversationState) -> Unit = {}

    suspend fun run(): Boolean {
        try {
            say(
                "Olá! Eu sou a Bela, sua assistente para o dia a dia. Vou ajudar você a usar seu celular por voz. " +
                    "Vamos configurar tudo juntas? São poucos passos, e em qualquer pergunta você pode dizer " +
                    "repita para ouvir de novo."
            )
            if (!microphoneStep()) return false
            voiceTestStep()
            speedStep()
            callsStep()
            internetStep()
            talkBackStep()
            activationStep()
            say(
                "Pronto, terminamos! Para falar comigo, toque em qualquer lugar da tela do aplicativo Bela " +
                    "e espere o bipe. Se minha voz estiver baixa, diga: Bela, aumente o volume. Até já!"
            )
            prefs.onboardingDone = true
            return true
        } catch (e: CancellationException) {
            speech.stop()
            input.cancel()
            throw e
        } finally {
            onStateChanged(ConversationState.INATIVA)
        }
    }

    private suspend fun microphoneStep(): Boolean {
        if (permissions.has(PermissionGroup.MICROPHONE)) return true
        say(
            "Primeiro, preciso de permissão para usar o microfone, para conseguir ouvir o que você fala. " +
                "Vai aparecer uma janela do Android. O botão de permitir fica na parte de baixo da janela. " +
                "Se você usa o TalkBack, deslize até Permitir durante o uso do app e toque duas vezes."
        )
        if (permissions.request(PermissionGroup.MICROPHONE)) {
            say("Obrigada, agora consigo ouvir você.")
            return true
        }
        sounds.error()
        say(
            "Sem o microfone eu não consigo ouvir você, e não dá para continuar. Peça para alguém permitir o " +
                "microfone nas configurações do aplicativo Bela, e depois abra o aplicativo de novo."
        )
        return false
    }

    private suspend fun voiceTestStep() {
        repeat(2) { attempt ->
            val prompt = if (attempt == 0) {
                "Agora vamos testar se eu ouço você bem. Depois do bipe, diga: olá, Bela."
            } else {
                "Vamos tentar mais uma vez. Depois do bipe, diga: olá, Bela."
            }
            say(prompt)
            when (val heard = listen()) {
                is Heard.Text -> {
                    say("Ouvi você dizer: ${heard.alternatives.first()}. Perfeito, estou ouvindo bem.")
                    return
                }
                is Heard.Failure -> if (heard.reason == ListenFailure.NO_NETWORK) {
                    say(
                        "O reconhecimento de voz deste celular está precisando de internet e a conexão parece ter caído. " +
                            "Vamos continuar, e você testa de novo depois."
                    )
                    return
                }
                Heard.Silence -> Unit
            }
        }
        say("Não consegui ouvir bem. Sem problema, vamos continuar; depois você tenta de novo falando perto do celular.")
    }

    private suspend fun speedStep() {
        var question = "Esta é a velocidade da minha voz. Você quer que eu fale mais devagar, mais rápido, ou está bom assim?"
        repeat(MAX_SPEED_ROUNDS) {
            say(question)
            val heard = listen()
            if (heard !is Heard.Text) return
            when (IntentParser.parse(heard.alternatives.first())) {
                BelaIntent.SpeakSlower -> adjust(-ConversationEngine.RATE_STEP)
                BelaIntent.SpeakFaster -> adjust(ConversationEngine.RATE_STEP)
                BelaIntent.Repeat, BelaIntent.Help, BelaIntent.NotUnderstood -> Unit
                else -> {
                    say("Combinado. Se quiser mudar depois, é só me pedir para falar mais devagar ou mais rápido.")
                    return
                }
            }
            question = "Assim está melhor? Diga mais devagar, mais rápido, ou está bom."
        }
    }

    private fun adjust(delta: Float) {
        val rate = (speech.rate + delta).coerceIn(ConversationEngine.MIN_RATE, ConversationEngine.MAX_RATE)
        speech.rate = rate
        prefs.speechRate = rate
    }

    private suspend fun callsStep() {
        val wants = askYesNo(
            "Agora, as ligações. Eu posso procurar seus contatos, fazer ligações, atender, recusar, " +
                "e dizer quem está ligando. Para isso, o Android vai pedir algumas autorizações. Vamos fazer isso agora?"
        )
        if (wants != true) {
            say("Tudo bem. Quando você me pedir uma ligação, eu peço essas autorizações.")
            return
        }
        grant(
            PermissionGroup.CONTACTS,
            "A primeira é para acessar a agenda de contatos, para eu encontrar as pessoas pelo nome.",
            "Agenda liberada."
        )
        grant(
            PermissionGroup.PHONE,
            "A segunda é para fazer e gerenciar ligações. É ela que me deixa ligar, atender e desligar quando você pedir.",
            "Ligações liberadas."
        )
        grant(
            PermissionGroup.CALL_LOG,
            "A última é para ver o número de quem está ligando, para eu dizer o nome da pessoa. " +
                "Eu não guardo esse número e não envio para lugar nenhum.",
            "Pronto, vou conseguir dizer quem está ligando."
        )
    }

    private suspend fun grant(group: PermissionGroup, explanation: String, success: String) {
        if (permissions.has(group)) return
        say("$explanation Vai aparecer uma janela do Android. O botão de permitir fica na parte de baixo.")
        if (permissions.request(group)) {
            say(success)
        } else {
            say("Tudo bem, essa ficou sem permissão. Dá para liberar depois nas configurações do aplicativo.")
        }
    }

    private suspend fun internetStep() {
        if (device.isOnline()) {
            say("O celular está conectado à internet, então pesquisas e músicas vão funcionar.")
        } else {
            say(
                "O celular está sem internet agora. Pesquisas e músicas precisam de internet, mas ligações, " +
                    "horas e aplicativos funcionam sem ela."
            )
        }
    }

    private suspend fun talkBackStep() {
        if (device.isTalkBackOn()) {
            say("Vi que o TalkBack, o leitor de tela, está ligado. Ótimo, eu funciono junto com ele.")
            return
        }
        say(
            "O TalkBack, o leitor de tela do Android, está desligado. Ele lê em voz alta tudo o que aparece na tela " +
                "e ajuda a usar o resto do celular. Para ligar, peça ajuda para entrar em Configurações, Acessibilidade, " +
                "TalkBack. Lá também dá para ativar o atalho de segurar as duas teclas de volume para ligar e desligar o TalkBack."
        )
    }

    private suspend fun activationStep() {
        if (device.isDefaultAssistant()) {
            say(
                "Eu já sou a assistente do celular. Então, em qualquer tela, até com o celular bloqueado, " +
                    "segure o botão de ligar, na lateral, e eu apareço pronta para ouvir."
            )
            return
        }
        val wants = askYesNo(
            "Para você me chamar de qualquer tela, segurando o botão de ligar, eu preciso ser escolhida como " +
                "assistente digital do celular, no lugar do Google Assistente. Quer que eu abra essa configuração? " +
                "Ela tem itens visuais, então pode ser bom ter alguém para ajudar."
        )
        if (wants == true) {
            if (device.openAssistantSettings()) {
                say(
                    "Abri a configuração. Escolha App assistente digital, e depois Bela. Quando terminar, volte para o " +
                        "aplicativo Bela."
                )
            } else {
                say("Não consegui abrir essa configuração neste celular. Ela fica em Configurações, Apps, Apps padrão.")
            }
        } else {
            say("Tudo bem. Enquanto isso, você pode me chamar pelo ícone da Bela ou pelo atalho nas configurações rápidas.")
        }
    }

    /** Pergunta sim ou não; trata "repita" e "me ajude". Retorna null se ela não respondeu. */
    private suspend fun askYesNo(question: String): Boolean? {
        say(question)
        repeat(MAX_QUESTION_ROUNDS) {
            val heard = listen()
            if (heard !is Heard.Text) return null
            when (IntentParser.parse(heard.alternatives.first())) {
                BelaIntent.Yes -> return true
                BelaIntent.No, BelaIntent.Cancel -> return false
                BelaIntent.Help -> say("Estou esperando você dizer sim ou não. $question")
                else -> say("Desculpe, não entendi. $question Responda sim ou não.")
            }
        }
        return null
    }

    private suspend fun listen(): Heard {
        onStateChanged(ConversationState.OUVINDO)
        sounds.listening()
        return input.listen().also { onStateChanged(ConversationState.PROCESSANDO) }
    }

    private suspend fun say(text: String) {
        onStateChanged(ConversationState.FALANDO)
        speech.speak(text)
    }

    private companion object {
        const val MAX_SPEED_ROUNDS = 4
        const val MAX_QUESTION_ROUNDS = 3
    }
}
