package com.theuzxl13.assistente.domain

/** O que a usuária pediu, já interpretado. Cada tipo corresponde a uma ação real do app. */
sealed class BelaIntent {
    // Controle da conversa
    data object Stop : BelaIntent()
    data object Cancel : BelaIntent()
    data object Repeat : BelaIntent()
    data object NotUnderstood : BelaIntent()
    data object SpeakSlower : BelaIntent()
    data object SpeakFaster : BelaIntent()
    data object Help : BelaIntent()
    data object Back : BelaIntent()
    data object Capabilities : BelaIntent()
    data object Goodbye : BelaIntent()

    // Respostas a perguntas da Bela
    data object Yes : BelaIntent()
    data object No : BelaIntent()
    /** Escolha numa lista lida pela Bela. Índice a partir de 0; -1 significa "o último". */
    data class Choice(val index: Int) : BelaIntent()
    data object Another : BelaIntent()

    // Ligações
    data class Call(val name: String) : BelaIntent()
    data object CallWithoutName : BelaIntent()
    data class CallEmergency(val number: String, val service: String) : BelaIntent()
    data object AnswerCall : BelaIntent()
    data object DeclineCall : BelaIntent()
    data object HangUp : BelaIntent()
    data object WhoIsCalling : BelaIntent()

    // Mídia
    data class PlayMedia(val query: String) : BelaIntent()
    data object PauseMedia : BelaIntent()
    data object ResumeMedia : BelaIntent()
    data object StopMedia : BelaIntent()
    data object NextMedia : BelaIntent()

    // Dia a dia
    data object Time : BelaIntent()
    data object Date : BelaIntent()
    data object VolumeUp : BelaIntent()
    data object VolumeDown : BelaIntent()
    data class OpenApp(val name: String) : BelaIntent()
    /** "Leia esse texto": lê o que foi compartilhado com a Bela por outro app. */
    data object ReadShared : BelaIntent()

    /** Qualquer outra coisa: vira uma pergunta/pesquisa na internet. */
    data class Ask(val question: String) : BelaIntent()
    data object Empty : BelaIntent()
}
