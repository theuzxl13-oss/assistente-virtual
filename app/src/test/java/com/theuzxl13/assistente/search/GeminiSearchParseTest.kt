package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.domain.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSearchParseTest {

    @Test
    fun `separa resumo, detalhes e fontes`() {
        val json = """
            {"candidates":[{"content":{"parts":[{"text":"**Bata** cenoura, ovos e óleo.\nDETALHES: Misture a farinha. Asse por 40 minutos."}]},
             "groundingMetadata":{"groundingChunks":[
               {"web":{"uri":"https://a","title":"tudogostoso.com.br"}},
               {"web":{"uri":"https://b","title":"tudogostoso.com.br"}},
               {"web":{"uri":"https://c","title":"receitas.globo.com"}}]}}]}
        """.trimIndent()
        val result = GeminiSearch.parse(json) as SearchResult.Success
        assertEquals("Bata cenoura, ovos e óleo.", result.answer.summary)
        assertEquals("Misture a farinha. Asse por 40 minutos.", result.answer.details)
        assertEquals(listOf("tudogostoso.com.br", "receitas.globo.com"), result.answer.sources.map { it.title })
    }

    @Test
    fun `resposta sem fontes continua valida mas sem citacao`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"Não encontrei informação confiável."}]}}]}"""
        val result = GeminiSearch.parse(json) as SearchResult.Success
        assertTrue(result.answer.sources.isEmpty())
        assertEquals("", result.answer.details)
    }

    @Test
    fun `sem texto retorna nulo`() {
        assertNull(GeminiSearch.parse("""{"candidates":[]}"""))
    }
}
