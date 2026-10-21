package com.theuzxl13.assistente.search

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
        val answer = GeminiSearch.parse(json)!!
        assertEquals("Bata cenoura, ovos e óleo.", answer.summary)
        assertEquals("Misture a farinha. Asse por 40 minutos.", answer.details)
        assertEquals(listOf("tudogostoso.com.br", "receitas.globo.com"), answer.sources.map { it.title })
        assertEquals("https://a", answer.results.first().url)
    }

    @Test
    fun `resposta sem fontes continua valida mas sem citacao`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"Não encontrei informação confiável."}]}}]}"""
        val answer = GeminiSearch.parse(json)!!
        assertTrue(answer.sources.isEmpty())
        assertEquals("", answer.details)
    }

    @Test
    fun `sem texto retorna nulo`() {
        assertNull(GeminiSearch.parse("""{"candidates":[]}"""))
    }

    @Test
    fun `escolhe modelo da lista da chave`() {
        // Lista real devolvida para a chave do projeto (diagnóstico de 21/10/2026), resumida.
        val names = listOf(
            "gemini-2.5-flash", "gemini-2.5-flash-preview-tts", "gemini-flash-lite-latest", "gemini-2.5-flash-image",
            "gemini-3-flash-preview", "gemini-3.1-flash-lite", "gemini-3.5-flash", "gemini-3.7-flash", "gemini-3.8-flash",
            "gemini-3.8-flash-tts", "gemini-omni-flash-preview"
        )
        val json = """{"models":[${names.joinToString(",") { """{"name":"models/$it","supportedGenerationMethods":["generateContent"]}""" }}]}"""
        assertEquals("gemini-3.8-flash", GeminiSearch.chooseModel(json, exclude = "gemini-flash-latest"))
        val withAlias = json.replace("\"models\":[", "\"models\":[{\"name\":\"models/gemini-flash-latest\",\"supportedGenerationMethods\":[\"generateContent\"]},")
        assertEquals("gemini-flash-latest", GeminiSearch.chooseModel(withAlias, exclude = "gemini-2.5-flash"))
    }
}
