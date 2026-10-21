package com.theuzxl13.assistente.domain

/**
 * Prepara o que foi falado para os serviços de pesquisa: "Bela, pesquise no Google como usar o
 * WhatsApp" vira a pergunta "como usar o WhatsApp".
 */
data class SearchQuery(
    /** Pergunta sem o comando ("pesquise...") — vai para a pesquisa geral. */
    val question: String,
    /** Assunto curto para enciclopédia: "quem foi Moisés" vira "Moisés". */
    val topic: String,
    /** Pedido de notícias? */
    val isNews: Boolean,
    /** Assunto das notícias ("" = principais manchetes). */
    val newsTopic: String
) {
    companion object {
        private val COMMAND = Regex(
            "^(?:(?:por favor|voc[eê] pode|pode|me)\\s+)?(?:pesquis[ae]r?|procur[ae]r?|busc[ae]r?|busque|ach[ae]r?|ve[jr]a?|fal[ae]r?(?: sobre)?|diga|me diga|quero saber|gostaria de saber)\\b\\s*",
            RegexOption.IGNORE_CASE
        )
        private val WHERE = Regex(
            "^(?:no google|na internet|na web|no navegador|pra mim|para mim|sobre)\\b\\s*",
            RegexOption.IGNORE_CASE
        )
        private val QUESTION_WORDS = Regex(
            "^(?:quem (?:foi|é|era|são|foram)|o que (?:é|foi|são|significa)|qual (?:é|foi|era)|quais (?:são|foram)|onde (?:fica|é|está)|quando (?:foi|é)|me fale (?:sobre|de)|fale (?:sobre|de)|sobre)\\s+(?:(?:o|a|os|as|um|uma)\\s+)?",
            RegexOption.IGNORE_CASE
        )
        private val NEWS = Regex("\\b(?:not[ií]cias?|manchetes?|jornal|aconteceu hoje)\\b", RegexOption.IGNORE_CASE)
        private val NEWS_FILLER = Regex(
            "\\b(?:as |os )?(?:[uú]ltimas |principais )?(?:not[ií]cias?|manchetes?)\\b|\\b(?:de |do |da |dos |das |sobre )?hoje\\b|\\bdo dia\\b|^\\s*(?:de|do|da|dos|das|sobre)\\b",
            RegexOption.IGNORE_CASE
        )

        fun from(spoken: String): SearchQuery {
            var question = IntentParser.stripWakeWordFromRaw(spoken).trim().trimEnd('.', '!', '?', ',')
            repeat(2) {
                question = question.replace(COMMAND, "").replace(WHERE, "").trim()
            }
            if (question.isBlank()) question = spoken.trim()

            val topic = question.replace(QUESTION_WORDS, "").trim().ifBlank { question }
            val isNews = NEWS.containsMatchIn(question)
            val newsTopic = if (isNews) {
                question.replace(NEWS_FILLER, " ").replace(Regex("\\s+"), " ").trim()
                    .replace(Regex("^(?:de|do|da|dos|das|sobre)\\s+", RegexOption.IGNORE_CASE), "")
            } else ""
            return SearchQuery(question, topic, isNews, newsTopic)
        }
    }
}
