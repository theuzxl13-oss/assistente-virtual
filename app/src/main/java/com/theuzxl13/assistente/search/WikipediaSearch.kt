package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.domain.Answer
import com.theuzxl13.assistente.domain.SearchError
import com.theuzxl13.assistente.domain.SearchProvider
import com.theuzxl13.assistente.domain.SearchResult
import com.theuzxl13.assistente.domain.Source
import com.theuzxl13.assistente.domain.WebResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * Pesquisa na Wikipédia em português pela API pública da Wikimedia (sem chave, sem cadastro).
 * Busca as páginas (título + trecho + endereço) e lê o resumo da primeira.
 */
class WikipediaSearch(
    private val http: OkHttpClient = SearchHttp.client(15),
    private val baseUrl: String = "https://pt.wikipedia.org",
    private val log: SearchLog = SearchLog.NONE
) {

    suspend fun search(topic: String): SearchResult = withContext(Dispatchers.IO) {
        val searchUrl = "$baseUrl/w/rest.php/v1/search/page".toHttpUrl().newBuilder()
            .addQueryParameter("q", topic)
            .addQueryParameter("limit", "5")
            .build()
        val body = when (val outcome = SearchHttp.execute(http, request(searchUrl))) {
            is HttpOutcome.Error -> {
                log.warn("wikipedia.busca", "erro=${outcome.error} http=${outcome.httpCode} ${outcome.detail}")
                return@withContext SearchResult.Failure(outcome.error)
            }
            is HttpOutcome.Ok -> outcome.body
        }
        val pages = try {
            parseSearch(body, baseUrl)
        } catch (e: JSONException) {
            log.warn("wikipedia.busca", "erro=INVALID_RESPONSE")
            return@withContext SearchResult.Failure(SearchError.INVALID_RESPONSE)
        }
        if (pages.isEmpty()) return@withContext SearchResult.Failure(SearchError.EMPTY)

        // O resumo é um extra: se falhar, a Bela lê o trecho da busca.
        val first = pages.first()
        val summaryUrl = "$baseUrl/api/rest_v1/page/summary".toHttpUrl().newBuilder().addPathSegment(first.key).build()
        val extract = when (val outcome = SearchHttp.execute(http, request(summaryUrl))) {
            is HttpOutcome.Ok -> runCatching { parseSummary(outcome.body) }.getOrNull()
            is HttpOutcome.Error -> {
                log.warn("wikipedia.resumo", "erro=${outcome.error} http=${outcome.httpCode}")
                null
            }
        }
        SearchResult.Success(buildAnswer(extract ?: first.result.snippet, pages))
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", SearchHttp.USER_AGENT).build()

    internal data class Page(val key: String, val result: WebResult)

    companion object {
        internal fun parseSearch(json: String, baseUrl: String): List<Page> {
            val pages = JSONObject(json).getJSONArray("pages")
            return (0 until pages.length()).map { i ->
                val page = pages.getJSONObject(i)
                val key = page.getString("key")
                val title = page.optString("title", key.replace('_', ' '))
                val description = page.optString("description").takeIf { it.isNotBlank() && it != "null" }
                val snippet = description ?: TextCleanup.stripHtml(page.optString("excerpt"))
                val url = "$baseUrl/wiki".toHttpUrl().newBuilder().addPathSegment(key).build().toString()
                Page(key, WebResult(title, snippet, url, "Wikipédia"))
            }
        }

        /** Texto do resumo; null para páginas de desambiguação ("Moisés pode referir-se a..."). */
        internal fun parseSummary(json: String): String? {
            val summary = JSONObject(json)
            if (summary.optString("type") == "disambiguation") return null
            return summary.optString("extract").trim().ifBlank { null }
        }

        internal fun buildAnswer(extract: String, pages: List<Page>): Answer {
            val (summary, details) = TextCleanup.splitSentences(extract, 2)
            val first = pages.first().result
            return Answer(
                summary = summary,
                details = details,
                sources = listOf(Source("Wikipédia", first.url)),
                results = pages.map { it.result },
                provider = SearchProvider.WIKIPEDIA
            )
        }
    }
}
