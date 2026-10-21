package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Diagnóstico com chamadas REAIS aos serviços de pesquisa. Só roda no CI, no passo
 * "Diagnóstico da pesquisa" (variável LIVE_SEARCH_DIAGNOSTIC=1). Grava em
 * app/build/diagnostico-pesquisa.txt o código HTTP e o motivo de cada chamada.
 * Nunca grava a chave: qualquer ocorrência dela é substituída por "<chave>".
 */
class LiveSearchDiagnosticTest {

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val key = BuildConfig.GEMINI_API_KEY
    private val report = StringBuilder()
    private val userAgent = "Bela/2.0 (assistente de acessibilidade; https://github.com/theuzxl13-oss/assistente-virtual)"

    @Test
    fun diagnostico() {
        assumeTrue(System.getenv("LIVE_SEARCH_DIAGNOSTIC") == "1")
        line("Chave do Gemini presente no build: ${key.isNotBlank()} (tamanho ${key.length})")

        if (key.isNotBlank()) {
            gemini("gemini-2.5-flash com google_search (igual ao app)", "gemini-2.5-flash", grounding = true)
            gemini("gemini-2.5-flash sem google_search", "gemini-2.5-flash", grounding = false)
            listModels()
        }
        get("Wikipédia (busca)", "https://pt.wikipedia.org/w/rest.php/v1/search/page?q=Mois%C3%A9s&limit=1")
        get("Google Notícias (RSS)", "https://news.google.com/rss/search?q=Brasil&hl=pt-BR&gl=BR&ceid=BR:pt-419")

        File("build/diagnostico-pesquisa.txt").writeText(report.toString())
    }

    private fun gemini(label: String, model: String, grounding: Boolean) {
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user")
                .put("parts", org.json.JSONArray().put(JSONObject().put("text", "Quem foi Moisés? Responda em uma frase.")))))
            .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
        if (grounding) body.put("tools", org.json.JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        call(label, request) { json ->
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val text = candidate?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
            val sources = candidate?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.length() ?: 0
            "texto=${text.take(120).replace("\n", " ")} | fontes=$sources"
        }
    }

    private fun listModels() {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            .header("x-goog-api-key", key)
            .build()
        call("Modelos disponíveis para a chave", request) { json ->
            val models = json.optJSONArray("models") ?: return@call "nenhum"
            (0 until models.length()).map { models.getJSONObject(it) }
                .filter { m -> m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                .map { it.optString("name").removePrefix("models/") }
                .filter { "flash" in it || "pro" in it }
                .joinToString(", ")
        }
    }

    private fun get(label: String, url: String) {
        call(label, Request.Builder().url(url).header("User-Agent", userAgent).build()) { _ -> "ok" }
    }

    private fun call(label: String, request: Request, summarize: (JSONObject) -> String) {
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val detail = if (response.isSuccessful) {
                    runCatching { summarize(JSONObject(text)) }.getOrElse { "corpo não-JSON, ${text.length} bytes" }
                } else {
                    val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                    "status=${error?.optString("status")} mensagem=${error?.optString("message")?.take(300) ?: text.take(200)}"
                }
                line("$label -> HTTP ${response.code} | $detail")
            }
        } catch (e: Exception) {
            line("$label -> EXCEÇÃO ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun line(text: String) {
        val safe = if (key.isNotBlank()) text.replace(key, "<chave>") else text
        report.appendLine(safe)
        println("DIAGNOSTICO: $safe")
    }
}
