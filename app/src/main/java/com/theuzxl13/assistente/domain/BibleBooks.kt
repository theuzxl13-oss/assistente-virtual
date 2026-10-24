package com.theuzxl13.assistente.domain

/**
 * Livro da Bíblia (cânone evangélico, 66 livros, na ordem da Almeida).
 * @param id código curto usado nos arquivos (padrão USFM: GEN, 1PE...).
 * @param spokenName como a Bela fala o livro ("Primeira carta de Pedro").
 * @param aliases jeitos de falar o nome, já normalizados (sem acento, minúsculas).
 */
data class BibleBook(
    val id: String,
    val spokenName: String,
    val chapters: Int,
    val aliases: List<String>
)

object BibleBooks {

    private fun b(id: String, spoken: String, chapters: Int, vararg aliases: String) =
        BibleBook(id, spoken, chapters, aliases.toList())

    // Livros numerados: aceita "1 pedro", "primeira de pedro", "primeira carta de pedro", "i pedro".
    private fun numbered(n: Int, feminine: Boolean, base: String): Array<String> {
        val ordinal = when (n) {
            1 -> if (feminine) listOf("primeira", "primeiro", "1a", "1o") else listOf("primeiro", "primeira", "1o", "1a")
            2 -> if (feminine) listOf("segunda", "segundo", "2a", "2o") else listOf("segundo", "segunda", "2o", "2a")
            else -> if (feminine) listOf("terceira", "terceiro", "3a", "3o") else listOf("terceiro", "terceira", "3o", "3a")
        }
        val roman = listOf("i", "ii", "iii")[n - 1]
        val prefixes = ordinal + listOf("$n", roman)
        val connectors = listOf("", "de ", "do ", "carta de ", "epistola de ", "livro de ", "livro dos ", "dos ", "das ", "aos ", "carta aos ", "epistola aos ")
        return prefixes.flatMap { p -> connectors.map { c -> "$p $c$base" } }.toTypedArray()
    }

    val all: List<BibleBook> = listOf(
        b("GEN", "Gênesis", 50, "genesis", "genese"),
        b("EXO", "Êxodo", 40, "exodo"),
        b("LEV", "Levítico", 27, "levitico"),
        b("NUM", "Números", 36, "numeros"),
        b("DEU", "Deuteronômio", 34, "deuteronomio"),
        b("JOS", "Josué", 24, "josue"),
        b("JDG", "Juízes", 21, "juizes"),
        b("RUT", "Rute", 4, "rute", "ruth"),
        b("1SA", "Primeiro livro de Samuel", 31, *numbered(1, false, "samuel")),
        b("2SA", "Segundo livro de Samuel", 24, *numbered(2, false, "samuel")),
        b("1KI", "Primeiro livro dos Reis", 22, *numbered(1, false, "reis")),
        b("2KI", "Segundo livro dos Reis", 25, *numbered(2, false, "reis")),
        b("1CH", "Primeiro livro das Crônicas", 29, *numbered(1, false, "cronicas")),
        b("2CH", "Segundo livro das Crônicas", 36, *numbered(2, false, "cronicas")),
        b("EZR", "Esdras", 10, "esdras"),
        b("NEH", "Neemias", 13, "neemias"),
        b("EST", "Ester", 10, "ester", "esther"),
        b("JOB", "Jó", 42, "jo", "job"),
        b("PSA", "Salmos", 150, "salmos", "salmo", "psalmos"),
        b("PRO", "Provérbios", 31, "proverbios", "proverbio"),
        b("ECC", "Eclesiastes", 12, "eclesiastes"),
        b("SNG", "Cânticos", 8, "canticos", "cantares", "cantico dos canticos", "canticos dos canticos", "cantico"),
        b("ISA", "Isaías", 66, "isaias"),
        b("JER", "Jeremias", 52, "jeremias"),
        b("LAM", "Lamentações", 5, "lamentacoes", "lamentacoes de jeremias"),
        b("EZK", "Ezequiel", 48, "ezequiel"),
        b("DAN", "Daniel", 12, "daniel"),
        b("HOS", "Oséias", 14, "oseias"),
        b("JOL", "Joel", 3, "joel"),
        b("AMO", "Amós", 9, "amos"),
        b("OBA", "Obadias", 1, "obadias"),
        b("JON", "Jonas", 4, "jonas"),
        b("MIC", "Miquéias", 7, "miqueias"),
        b("NAM", "Naum", 3, "naum"),
        b("HAB", "Habacuque", 3, "habacuque", "habacuc"),
        b("ZEP", "Sofonias", 3, "sofonias"),
        b("HAG", "Ageu", 2, "ageu"),
        b("ZEC", "Zacarias", 14, "zacarias"),
        b("MAL", "Malaquias", 4, "malaquias"),
        b("MAT", "Mateus", 28, "mateus", "evangelho de mateus", "sao mateus"),
        b("MRK", "Marcos", 16, "marcos", "evangelho de marcos", "sao marcos"),
        b("LUK", "Lucas", 24, "lucas", "evangelho de lucas", "sao lucas"),
        b("JHN", "João", 21, "joao", "evangelho de joao", "sao joao"),
        b("ACT", "Atos dos Apóstolos", 28, "atos", "atos dos apostolos"),
        b("ROM", "Romanos", 16, "romanos", "carta aos romanos"),
        b("1CO", "Primeira carta aos Coríntios", 16, *numbered(1, true, "corintios")),
        b("2CO", "Segunda carta aos Coríntios", 13, *numbered(2, true, "corintios")),
        b("GAL", "Gálatas", 6, "galatas", "carta aos galatas"),
        b("EPH", "Efésios", 6, "efesios", "carta aos efesios"),
        b("PHP", "Filipenses", 4, "filipenses", "carta aos filipenses"),
        b("COL", "Colossenses", 4, "colossenses", "carta aos colossenses"),
        b("1TH", "Primeira carta aos Tessalonicenses", 5, *numbered(1, true, "tessalonicenses")),
        b("2TH", "Segunda carta aos Tessalonicenses", 3, *numbered(2, true, "tessalonicenses")),
        b("1TI", "Primeira carta a Timóteo", 6, *numbered(1, true, "timoteo"), "1 a timoteo", "primeira a timoteo", "primeira carta a timoteo"),
        b("2TI", "Segunda carta a Timóteo", 4, *numbered(2, true, "timoteo"), "2 a timoteo", "segunda a timoteo", "segunda carta a timoteo"),
        b("TIT", "Tito", 3, "tito", "carta a tito"),
        b("PHM", "Filemom", 1, "filemom", "filemon", "carta a filemom"),
        b("HEB", "Hebreus", 13, "hebreus", "carta aos hebreus"),
        b("JAS", "Tiago", 5, "tiago", "carta de tiago"),
        b("1PE", "Primeira carta de Pedro", 5, *numbered(1, true, "pedro")),
        b("2PE", "Segunda carta de Pedro", 3, *numbered(2, true, "pedro")),
        b("1JN", "Primeira carta de João", 5, *numbered(1, true, "joao")),
        b("2JN", "Segunda carta de João", 1, *numbered(2, true, "joao")),
        b("3JN", "Terceira carta de João", 1, *numbered(3, true, "joao")),
        b("JUD", "Judas", 1, "judas", "carta de judas"),
        b("REV", "Apocalipse", 22, "apocalipse", "revelacao", "revelacoes")
    )

    /** Apelidos ordenados do mais longo para o mais curto, para "primeira de joao" ganhar de "joao". */
    internal val aliasIndex: List<Pair<String, BibleBook>> =
        all.flatMap { book -> book.aliases.map { it to book } }.sortedByDescending { it.first.length }

    fun byId(id: String): BibleBook? = all.firstOrNull { it.id == id }
}
