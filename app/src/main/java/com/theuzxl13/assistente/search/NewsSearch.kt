package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.domain.Answer
import com.theuzxl13.assistente.domain.SearchError
import com.theuzxl13.assistente.domain.SearchProvider
import com.theuzxl13.assistente.domain.SearchResult
import com.theuzxl13.assistente.domain.Source
import com.theuzxl13.assistente.domain.WebResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Manchetes reais do Google Notícias pelo feed RSS público em português do Brasil (sem chave).
 * Sem assunto, traz as principais manchetes do dia.
 */
class NewsSearch(
    private val http: OkHttpClient = SearchHttp.client(15),
    private val baseUrl: String = "https://news.google.com",
    private val log: SearchLog = SearchLog.NONE
) {

    suspend fun search(topic: String): SearchResult = withContext(Dispatchers.IO) {
        val builder = if (topic.isBlank()) {
            "$baseUrl/rss".toHttpUrl().newBuilder()
        } else {
            "$baseUrl/rss/search".toHttpUrl().newBuilder().addQueryParameter("q", topic)
        }
        val url = builder
            .addQueryParameter("hl", "pt-BR")
            .addQueryParameter("gl", "BR")
            .addQueryParameter("ceid", "BR:pt-419")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", SearchHttp.USER_AGENT).build()
        when (val outcome = SearchHttp.execute(http, request)) {
            is HttpOutcome.Error -> {
                log.warn("noticias", "erro=${outcome.error} http=${outcome.httpCode} ${outcome.detail}")
                SearchResult.Failure(outcome.error)
            }
            is HttpOutcome.Ok -> {
                val items = try {
                    parseRss(outcome.body)
                } catch (e: Exception) {
                    log.warn("noticias", "erro=INVALID_RESPONSE ${e.javaClass.simpleName}")
                    return@withContext SearchResult.Failure(SearchError.INVALID_RESPONSE)
                }
                if (items.isEmpty()) SearchResult.Failure(SearchError.EMPTY) else SearchResult.Success(buildAnswer(items))
            }
        }
    }

    companion object {
        private const val MAX_ITEMS = 8

        internal fun parseRss(xml: String): List<WebResult> {
            val factory = DocumentBuilderFactory.newInstance()
            // Feed de terceiros: não processar DOCTYPE/entidades externas (quando o parser suportar).
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val document = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
            val items = document.getElementsByTagName("item")
            return (0 until minOf(items.length, MAX_ITEMS)).mapNotNull { i ->
                val item = items.item(i) as? Element ?: return@mapNotNull null
                val rawTitle = item.text("title") ?: return@mapNotNull null
                val site = item.text("source").orEmpty()
                // O Google Notícias põe " - Nome do site" no fim do título.
                val title = if (site.isNotBlank()) rawTitle.removeSuffix(" - $site").trim() else rawTitle
                WebResult(
                    title = title,
                    snippet = TextCleanup.stripHtml(item.text("description").orEmpty()),
                    url = item.text("link").orEmpty(),
                    site = site
                )
            }
        }

        internal fun buildAnswer(items: List<WebResult>): Answer {
            val first = items.first()
            return Answer(
                summary = first.title,
                details = "",
                sources = listOf(Source(first.site.ifBlank { "Google Notícias" }, first.url)),
                results = items,
                provider = SearchProvider.NEWS
            )
        }

        private fun Element.text(tag: String): String? =
            getElementsByTagName(tag).item(0)?.textContent?.trim()?.ifBlank { null }
    }
}
