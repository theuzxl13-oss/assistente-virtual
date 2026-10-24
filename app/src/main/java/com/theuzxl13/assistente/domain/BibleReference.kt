package com.theuzxl13.assistente.domain

/** "1 Pedro 5,7" → livro 1PE, capítulo 5, versículos 7 a 7. Sem versículo = capítulo inteiro. */
data class BibleReference(val book: BibleBook, val chapter: Int, val verseStart: Int? = null, val verseEnd: Int? = null) {

    /** "Primeira carta de Pedro, capítulo 5, versículo 7" */
    fun spoken(): String {
        val chapterName = if (book.id == "PSA") "Salmo $chapter" else "${book.spokenName}, capítulo $chapter"
        return when {
            verseStart == null -> chapterName
            verseEnd == null || verseEnd == verseStart -> "$chapterName, versículo $verseStart"
            else -> "$chapterName, versículos $verseStart a $verseEnd"
        }
    }
}

/**
 * Entende referências bíblicas faladas ou digitadas:
 * "1 Pedro 5,7", "primeira de Pedro capítulo cinco versículo sete", "João 3:16", "Salmo 23",
 * "Mateus 5 do 3 ao 10", "I Coríntios 13".
 */
object BibleReferenceParser {

    fun parse(raw: String): BibleReference? {
        var s = TextNormalizer.normalize(raw)
        s = s.replace(Regex("(\\d)([a-z])"), "$1 $2")      // "1pedro" -> "1 pedro"
        s = wordsToNumbers(s)

        for ((alias, book) in BibleBooks.aliasIndex) {
            val match = Regex("(?:^|\\s)${Regex.escape(alias)}\\s+(?:capitulo\\s+|cap\\s+)?(\\d{1,3})\\b(.*)$").find(s) ?: continue
            val chapter = match.groupValues[1].toInt()
            if (chapter < 1 || chapter > book.chapters) return null
            val (start, end) = parseVerses(match.groupValues[2])
            return BibleReference(book, chapter, start, end)
        }
        return null
    }

    private fun parseVerses(rest: String): Pair<Int?, Int?> {
        val m = Regex(
            "^\\s*(?:e\\s+)?(?:(?:versiculos?|vers|v)\\s+)?(?:(?:do|de)\\s+)?(?:versiculo\\s+)?(\\d{1,3})" +
                "(?:\\s*(?:a|ao|ate|e)\\s+(?:o\\s+)?(?:versiculo\\s+)?(\\d{1,3}))?\\b"
        ).find(rest) ?: return null to null
        val start = m.groupValues[1].toInt()
        val end = m.groupValues[2].toIntOrNull()?.takeIf { it >= start }
        return start to end
    }

    private val UNITS = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5,
        "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9
    )
    private val TEENS = mapOf(
        "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14,
        "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19
    )
    private val TENS = mapOf(
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60,
        "setenta" to 70, "oitenta" to 80, "noventa" to 90
    )
    private val HUNDREDS = mapOf("cem" to 100, "cento" to 100)

    private fun value(word: String): Int? = UNITS[word] ?: TEENS[word] ?: TENS[word] ?: HUNDREDS[word]

    /** "cento e dezenove" -> "119", "vinte e tres" -> "23". Mantém o resto do texto. */
    internal fun wordsToNumbers(text: String): String {
        val words = text.split(" ")
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val first = value(words[i])
            if (first == null) {
                out += words[i]; i++; continue
            }
            var total = first
            var j = i + 1
            // junta "e <número>" enquanto fizer sentido (centena + dezena + unidade)
            while (j + 1 < words.size && words[j] == "e") {
                val next = value(words[j + 1]) ?: break
                val lastPlace = when {
                    total % 100 == 0 && total >= 100 -> 100
                    total % 10 == 0 && total >= 20 -> 10
                    else -> 1
                }
                if (next >= lastPlace) break
                total += next
                j += 2
            }
            out += total.toString()
            i = j
        }
        return out.joinToString(" ")
    }
}

/**
 * A Almeida de 1911 usa a grafia da época ("Christo", "elle", "fructo", "Psalmo"). Para o
 * sintetizador de voz pronunciar certo, a Bela fala com a grafia atualizada; o texto bíblico
 * guardado no app não é alterado.
 */
object OldSpelling {

    private val WORDS = mapOf(
        "deos" to "Deus", "jehovah" to "Jeová", "moysés" to "Moisés", "moyses" to "Moisés",
        "psalmo" to "salmo", "psalmos" to "salmos", "psalmista" to "salmista", "egypto" to "Egito",
        "baptismo" to "batismo", "baptizar" to "batizar", "sancto" to "santo", "sancta" to "santa",
        "sanctos" to "santos", "sanctas" to "santas", "sanctificou" to "santificou", "fructo" to "fruto",
        "fructos" to "frutos", "fructifera" to "frutífera", "actos" to "atos", "logar" to "lugar", "logares" to "lugares",
        "similhança" to "semelhança", "similhante" to "semelhante", "herva" to "erva", "hervas" to "ervas",
        "creou" to "criou", "creados" to "criados", "creado" to "criado", "creação" to "criação",
        "creatura" to "criatura", "creaturas" to "criaturas", "vasia" to "vazia", "abysmo" to "abismo",
        "poz" to "pôs", "elle" to "ele", "ella" to "ela", "elles" to "eles", "ellas" to "elas",
        "aquelle" to "aquele", "aquella" to "aquela", "aquelles" to "aqueles", "aquellas" to "aquelas",
        "n'elle" to "nele", "n'ella" to "nela", "d'elle" to "dele", "d'ella" to "dela", "d'este" to "deste",
        "d'esta" to "desta", "n'este" to "neste", "n'esta" to "nesta", "n'um" to "num", "n'uma" to "numa"
    )

    fun forSpeech(text: String): String {
        val normalizedQuotes = text.replace('’', '\'')
        return Regex("[\\p{L}']+").replace(normalizedQuotes) { m ->
            val word = m.value
            val lower = word.lowercase()
            val replaced = WORDS[lower] ?: modernize(lower)
            if (replaced == lower) word else matchCase(word, replaced)
        }
    }

    private fun modernize(w: String): String {
        var s = w
        if (s.startsWith("ps")) s = s.substring(1)
        s = s.replace("ph", "f").replace("th", "t")
            .replace(Regex("ch(?=[rl])"), "c")
            .replace("mm", "m").replace("nn", "n").replace("ll", "l").replace("tt", "t")
            .replace("pp", "p").replace("ff", "f").replace("dd", "d").replace("gg", "g").replace("bb", "b")
            .replace("y", "i")
        return s
    }

    private fun matchCase(original: String, replacement: String): String = when {
        original.all { !it.isLetter() || it.isUpperCase() } && original.length > 1 -> replacement.uppercase()
        original.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }
}
