package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.domain.SearchError
import com.theuzxl13.assistente.domain.SearchProvider
import com.theuzxl13.assistente.domain.SearchResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Testa os clientes de pesquisa contra um servidor HTTP falso (MockWebServer): respostas reais
 * gravadas, erros HTTP, timeout, DNS e JSON inválido.
 */
class SearchClientsTest {

    private lateinit var server: MockWebServer
    private val logs = mutableListOf<String>()
    private val log = SearchLog { stage, detail -> logs += "$stage $detail" }
    private val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun gemini(key: String = "chave-de-teste") = GeminiSearch(key, http, base(), log)

    // ---------------------------------------------------------------- Gemini

    /** Corpo exato que a API devolveu para a chave do projeto em 21/10/2026 (diagnóstico do CI). */
    private val realModelGone404 = """
        {"error":{"code":404,"message":"This model models/gemini-2.5-flash is no longer available to new users. Please update your code to use models/gemini-3.8-flash for the latest features and improvements.","status":"NOT_FOUND"}}
    """.trimIndent()

    private val okAnswer = """
        {"candidates":[{"content":{"parts":[{"text":"Moisés foi um profeta hebreu. Ele conduziu o povo de Israel.\nDETALHES: Segundo a Bíblia, recebeu os Dez Mandamentos."}]},
          "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://pt.wikipedia.org/wiki/Mois%C3%A9s","title":"wikipedia.org"}}]}}]}
    """.trimIndent()

    @Test
    fun `modelo aposentado - procura outro modelo e pesquisa com ele`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(realModelGone404))
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]}]}"""))
        server.enqueue(MockResponse().setBody(okAnswer))

        val result = gemini().ask("quem foi Moisés", emptyList())

        assertTrue(result is SearchResult.Success)
        val answer = (result as SearchResult.Success).answer
        assertEquals("Moisés foi um profeta hebreu. Ele conduziu o povo de Israel.", answer.summary)
        assertEquals("wikipedia.org", answer.sources.single().title)
        assertTrue(server.takeRequest().path!!.contains("gemini-flash-latest:generateContent"))
        assertTrue(server.takeRequest().path!!.startsWith("/v1beta/models?"))
        assertTrue(server.takeRequest().path!!.contains("gemini-3.8-flash:generateContent"))
    }

    @Test
    fun `envia a chave no cabecalho e nunca no log`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        gemini("segredo-123").ask("teste", emptyList())
        assertEquals("segredo-123", server.takeRequest().getHeader("x-goog-api-key"))
        assertFalse(logs.any { it.contains("segredo-123") })
        assertFalse(logs.any { it.contains("teste") })
    }

    @Test
    fun `erros http viram o motivo certo`() = runTest {
        val cases = listOf(
            400 to """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""" to SearchError.UNAUTHORIZED,
            401 to "{}" to SearchError.UNAUTHORIZED,
            403 to """{"error":{"code":403,"status":"PERMISSION_DENIED"}}""" to SearchError.UNAUTHORIZED,
            429 to """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""" to SearchError.RATE_LIMITED,
            500 to "erro" to SearchError.SERVER,
            503 to "" to SearchError.SERVER,
            400 to """{"error":{"code":400,"status":"FAILED_PRECONDITION"}}""" to SearchError.BAD_REQUEST
        )
        for ((response, expected) in cases) {
            val (code, body) = response
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            assertEquals("HTTP $code", SearchResult.Failure(expected), gemini().ask("x", emptyList()))
        }
    }

    @Test
    fun `modelo indisponivel sem alternativa`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(realModelGone404))
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        assertEquals(SearchResult.Failure(SearchError.MODEL_UNAVAILABLE), gemini().ask("x", emptyList()))
    }

    @Test
    fun `json invalido e resposta vazia`() = runTest {
        server.enqueue(MockResponse().setBody("<html>não é json</html>"))
        assertEquals(SearchResult.Failure(SearchError.INVALID_RESPONSE), gemini().ask("x", emptyList()))
        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
        assertEquals(SearchResult.Failure(SearchError.EMPTY), gemini().ask("x", emptyList()))
    }

    @Test
    fun `timeout`() = runTest {
        server.enqueue(MockResponse().setBody(okAnswer).setBodyDelay(5, TimeUnit.SECONDS))
        assertEquals(SearchResult.Failure(SearchError.TIMEOUT), gemini().ask("x", emptyList()))
    }

    @Test
    fun `conexao recusada e dns`() = runTest {
        val port = server.port
        server.shutdown()
        val refused = GeminiSearch("k", http, "http://127.0.0.1:$port", log).ask("x", emptyList())
        assertEquals(SearchResult.Failure(SearchError.NO_CONNECTION), refused)
        val dns = GeminiSearch("k", http, "http://servico-que-nao-existe.invalid", log).ask("x", emptyList())
        assertEquals(SearchResult.Failure(SearchError.DNS), dns)
    }

    @Test
    fun `conexao cai no meio`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = gemini().ask("x", emptyList())
        assertTrue(result is SearchResult.Failure)
    }

    @Test
    fun `sem chave nao chama o servico`() = runTest {
        assertEquals(SearchResult.NotConfigured, gemini(key = "").ask("x", emptyList()))
        assertEquals(0, server.requestCount)
    }

    // ---------------------------------------------------------------- Wikipédia

    private val wikiSearch = """
        {"pages":[
          {"id":1,"key":"Moisés","title":"Moisés","excerpt":"<span class=\"searchmatch\">Moisés</span> é uma figura","matched_title":null,"description":"profeta do judaísmo, cristianismo e islamismo"},
          {"id":2,"key":"Moisés_(Michelangelo)","title":"Moisés (Michelangelo)","excerpt":"escultura de <span class=\"searchmatch\">Moisés</span>","matched_title":null,"description":null}
        ]}
    """.trimIndent()

    private val wikiSummary = """
        {"type":"standard","title":"Moisés","extract":"Moisés é uma figura central nas religiões abraâmicas. Segundo a Bíblia, libertou os hebreus do Egito. Recebeu as Tábuas da Lei no Monte Sinai."}
    """.trimIndent()

    @Test
    fun `wikipedia - resumo, detalhes e lista de resultados`() = runTest {
        server.enqueue(MockResponse().setBody(wikiSearch))
        server.enqueue(MockResponse().setBody(wikiSummary))

        val result = WikipediaSearch(http, base(), log).search("Moisés") as SearchResult.Success
        val answer = result.answer

        assertEquals(SearchProvider.WIKIPEDIA, answer.provider)
        assertEquals("Moisés é uma figura central nas religiões abraâmicas. Segundo a Bíblia, libertou os hebreus do Egito.", answer.summary)
        assertEquals("Recebeu as Tábuas da Lei no Monte Sinai.", answer.details)
        assertEquals(listOf("Moisés", "Moisés (Michelangelo)"), answer.results.map { it.title })
        assertEquals("escultura de Moisés", answer.results[1].snippet)
        assertTrue(answer.results[0].url.endsWith("/wiki/Mois%C3%A9s"))
        val searchRequest = server.takeRequest()
        assertTrue(searchRequest.path!!.startsWith("/w/rest.php/v1/search/page?q=Mois%C3%A9s"))
        assertTrue(searchRequest.getHeader("User-Agent")!!.startsWith("Bela/"))
    }

    @Test
    fun `wikipedia - sem resultados e resumo que falha`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pages":[]}"""))
        assertEquals(SearchResult.Failure(SearchError.EMPTY), WikipediaSearch(http, base(), log).search("xyzw"))

        server.enqueue(MockResponse().setBody(wikiSearch))
        server.enqueue(MockResponse().setResponseCode(503))
        val fallback = WikipediaSearch(http, base(), log).search("Moisés") as SearchResult.Success
        assertEquals("profeta do judaísmo, cristianismo e islamismo", fallback.answer.summary)
    }

    // ---------------------------------------------------------------- Google Notícias

    private val rss = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
        <title>Principais notícias - Google Notícias</title>
        <item><title>Chuva forte atinge São Paulo nesta terça - g1</title><link>https://news.google.com/rss/articles/abc</link>
          <pubDate>Tue, 20 Oct 2026 12:00:00 GMT</pubDate><description>&lt;a href="x"&gt;Chuva forte atinge São Paulo&lt;/a&gt;</description>
          <source url="https://g1.globo.com">g1</source></item>
        <item><title>Inflação desacelera em outubro - Folha de S.Paulo</title><link>https://news.google.com/rss/articles/def</link>
          <source url="https://www.folha.uol.com.br">Folha de S.Paulo</source></item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `noticias - manchetes reais do rss`() = runTest {
        server.enqueue(MockResponse().setBody(rss))
        val answer = (NewsSearch(http, base(), log).search("") as SearchResult.Success).answer
        assertEquals(SearchProvider.NEWS, answer.provider)
        assertEquals("Chuva forte atinge São Paulo nesta terça", answer.results[0].title)
        assertEquals("g1", answer.results[0].site)
        assertEquals("Folha de S.Paulo", answer.results[1].site)
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/rss?hl=pt-BR&gl=BR"))
    }

    @Test
    fun `noticias - assunto vai na busca e xml invalido vira erro`() = runTest {
        server.enqueue(MockResponse().setBody(rss))
        NewsSearch(http, base(), log).search("eleições")
        assertTrue(server.takeRequest().path!!.startsWith("/rss/search?q=elei%C3%A7%C3%B5es"))

        server.enqueue(MockResponse().setBody("isto não é xml"))
        assertEquals(SearchResult.Failure(SearchError.INVALID_RESPONSE), NewsSearch(http, base(), log).search("x"))
    }

    // ---------------------------------------------------------------- Combinação

    @Test
    fun `gemini falha e a wikipedia responde`() = runTest {
        // Mesmo cenário do celular: Gemini 404 e nenhum outro modelo; Wikipédia funciona.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains(":generateContent") -> MockResponse().setResponseCode(404).setBody(realModelGone404)
                    path.startsWith("/v1beta/models") -> MockResponse().setBody("""{"models":[]}""")
                    path.startsWith("/w/rest.php") -> MockResponse().setBody(wikiSearch)
                    path.startsWith("/api/rest_v1/page/summary") -> MockResponse().setBody(wikiSummary)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val search = WebSearch(gemini(), WikipediaSearch(http, base(), log), NewsSearch(http, base(), log), log)
        val result = search.ask("Bela, pesquise quem foi Moisés", emptyList()) as SearchResult.Success
        assertEquals(SearchProvider.WIKIPEDIA, result.answer.provider)
        assertTrue(server.requestCount >= 3)
    }

    @Test
    fun `escolha do erro falado quando tudo falha`() {
        val network = WebSearch.mostUseful(listOf(SearchResult.Failure(SearchError.DNS), SearchResult.Failure(SearchError.NO_CONNECTION)))
        assertEquals(SearchResult.Failure(SearchError.DNS), network)
        val timeout = WebSearch.mostUseful(listOf(SearchResult.Failure(SearchError.NO_CONNECTION), SearchResult.Failure(SearchError.TIMEOUT)))
        assertEquals(SearchResult.Failure(SearchError.TIMEOUT), timeout)
        val config = WebSearch.mostUseful(listOf(SearchResult.Failure(SearchError.UNAUTHORIZED), SearchResult.Failure(SearchError.EMPTY)))
        assertEquals(SearchResult.Failure(SearchError.UNAUTHORIZED), config)
        val noKeyNothingFound = WebSearch.mostUseful(listOf(SearchResult.NotConfigured, SearchResult.Failure(SearchError.EMPTY)))
        assertEquals(SearchResult.Failure(SearchError.EMPTY), noKeyNothingFound)
    }
}
