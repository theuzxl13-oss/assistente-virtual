package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.BuildConfig
import com.theuzxl13.assistente.domain.Answer
import com.theuzxl13.assistente.domain.SearchPort
import com.theuzxl13.assistente.domain.SearchResult
import com.theuzxl13.assistente.domain.Source
import com.theuzxl13.assistente.domain.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Pesquisa na internet com a API do Gemini usando a ferramenta oficial "Google Search grounding":
 * o modelo faz buscas reais no Google e a resposta volta com a lista de sites usados, que a Bela
 * cita ("a informação é de..."). Quando não há fonte, a Bela avisa que a resposta não foi conferida.
 */
class GeminiSearch(private val apiKey: String = BuildConfig.GEMINI_API_KEY) : SearchPort {

    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    override suspend fun ask(question: String, history: List<Turn>): SearchResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SearchResult.NotConfigured
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(buildBody(question, history).toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext SearchResult.Failure
                parse(response.body?.string().orEmpty()) ?: SearchResult.Failure
            }
        } catch (_: Exception) {
            SearchResult.Failure
        }
    }

    private fun buildBody(question: String, history: List<Turn>): JSONObject {
        val contents = JSONArray()
        for (turn in history) {
            contents.put(message("user", turn.question))
            contents.put(message("model", turn.answer))
        }
        contents.put(message("user", question))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.3)
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            )
    }

    private fun message(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    companion object {
        /** Modelo estável com suporte a Google Search grounding. */
        const val MODEL = "gemini-2.5-flash"

        private val SYSTEM_PROMPT = """
            Você é a Bela, assistente de voz de uma senhora com cegueira total. Responda em português do Brasil.
            Seu texto será lido em voz alta: não use markdown, asteriscos, listas com símbolos, links, emojis ou tabelas.
            Formato obrigatório: primeiro um resumo de no máximo duas frases curtas e diretas. Se houver mais informação
            útil (passos de uma receita, detalhes de uma notícia), escreva uma linha começando com DETALHES: seguida de
            até oito frases curtas, em ordem.
            Use a Pesquisa Google para fatos atuais: notícias, telefones, endereços, horários, preços.
            Se não encontrar informação confiável, diga claramente que não encontrou. Nunca invente telefones, endereços,
            notícias ou referências.
            Nunca peça senhas, códigos de verificação ou dados bancários.
            Trate a usuária com respeito e carinho, como adulta, sem infantilizar.
        """.trimIndent()

        /** Separa resumo e detalhes e junta as fontes. Retorna null se não veio texto. */
        internal fun parse(json: String): SearchResult? {
            val candidate = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0) ?: return null
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val text = (0 until parts.length())
                .mapNotNull { parts.optJSONObject(it)?.optString("text") }
                .joinToString("")
                .let(::cleanForSpeech)
            if (text.isBlank()) return null

            val summary = text.substringBefore("DETALHES:").replace(Regex("^RESUMO:\\s*"), "").trim()
            val details = if (text.contains("DETALHES:")) text.substringAfter("DETALHES:").trim() else ""

            val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
            val sources = (0 until (chunks?.length() ?: 0))
                .mapNotNull { chunks?.optJSONObject(it)?.optJSONObject("web") }
                .map { Source(it.optString("title"), it.optString("uri")) }
                .filter { it.title.isNotBlank() }
                .distinctBy { it.title }

            return SearchResult.Success(Answer(summary.ifBlank { details }, if (summary.isBlank()) "" else details, sources))
        }

        private fun cleanForSpeech(text: String): String = text
            .replace(Regex("[*#_`]"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }
}
