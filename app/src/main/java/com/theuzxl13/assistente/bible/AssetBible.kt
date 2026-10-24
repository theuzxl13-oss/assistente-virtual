package com.theuzxl13.assistente.bible

import android.content.Context
import com.theuzxl13.assistente.domain.BiblePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * Bíblia guardada dentro do app (funciona sem internet): Almeida, Edição Revista e Corrigida,
 * Lisboa 1911, domínio público. Um arquivo por livro em assets/biblia/almeida1911/<LIVRO>.txt,
 * com linhas "capitulo<TAB>versiculo<TAB>texto" (gerados por tools/biblia/converter.py).
 */
class AssetBible(private val context: Context) : BiblePort {

    override val translationName = "Almeida, edição Revista e Corrigida de 1911"

    private var cachedBook: String? = null
    private var cachedChapters: Map<Int, List<String>> = emptyMap()

    override suspend fun chapter(bookId: String, chapter: Int): List<String>? = withContext(Dispatchers.IO) {
        if (cachedBook != bookId) {
            cachedChapters = load(bookId) ?: return@withContext null
            cachedBook = bookId
        }
        cachedChapters[chapter]
    }

    private fun load(bookId: String): Map<Int, List<String>>? = try {
        context.assets.open("$DIR/$bookId.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size == 3) parts[0].toInt() to parts[2] else null
            }.groupBy({ it.first }, { it.second })
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private companion object {
        const val DIR = "biblia/almeida1911"
    }
}
