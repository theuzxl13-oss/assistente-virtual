package com.theuzxl13.assistente.search

/** Deixa textos da web bons para serem falados. */
object TextCleanup {

    fun stripHtml(html: String): String = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Separa as primeiras [count] frases do resto. */
    fun splitSentences(text: String, count: Int): Pair<String, String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        var end = 0
        var found = 0
        val boundary = Regex("[.!?](?=\\s+[A-ZÁÉÍÓÚÂÊÔÃÕÇ0-9\"“])")
        for (match in boundary.findAll(clean)) {
            // Ignora abreviações comuns ("a.C.", "Sr.", "Dr.") olhando a palavra antes do ponto.
            val before = clean.substring(0, match.range.first).substringAfterLast(' ')
            if (before.length <= 2 || before in ABBREVIATIONS) continue
            end = match.range.last + 1
            if (++found == count) break
        }
        if (found < count || end >= clean.length) return clean to ""
        return clean.substring(0, end).trim() to clean.substring(end).trim()
    }

    private val ABBREVIATIONS = setOf("Sr", "Sra", "Dr", "Dra", "a.C", "d.C", "etc", "séc", "pág", "aprox")
}
