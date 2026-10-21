package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.domain.SearchError
import com.theuzxl13.assistente.domain.SearchPort
import com.theuzxl13.assistente.domain.SearchQuery
import com.theuzxl13.assistente.domain.SearchResult
import com.theuzxl13.assistente.domain.Turn

/**
 * Pesquisa que a Bela usa, combinando serviços reais:
 * - notícias: manchetes do Google Notícias (lista para "próxima notícia"); se falhar, Gemini;
 * - o resto: Gemini com Pesquisa Google (resumo com fontes); se falhar, Wikipédia.
 *
 * Wikipédia e Google Notícias não precisam de chave, então a pesquisa continua funcionando mesmo
 * se o Gemini estiver sem chave, sem cota ou fora do ar. Quando tudo falha, devolve o erro mais
 * útil para a usuária (o do serviço principal, a não ser que ele só não tenha achado nada).
 */
class WebSearch(
    private val gemini: GeminiSearch,
    private val wikipedia: WikipediaSearch,
    private val news: NewsSearch,
    private val log: SearchLog = SearchLog.NONE
) : SearchPort {

    override suspend fun ask(question: String, history: List<Turn>): SearchResult {
        val query = SearchQuery.from(question)
        val attempts = if (query.isNews) {
            listOf<suspend () -> SearchResult>(
                { news.search(query.newsTopic) },
                { gemini.ask(query.question, history) }
            )
        } else {
            listOf<suspend () -> SearchResult>(
                { gemini.ask(query.question, history) },
                { wikipedia.search(query.topic) }
            )
        }

        val failures = mutableListOf<SearchResult>()
        for (attempt in attempts) {
            val result = attempt()
            if (result is SearchResult.Success) return result
            failures += result
        }
        log.warn("pesquisa", "todas as fontes falharam: ${failures.joinToString { describe(it) }}")
        return mostUseful(failures)
    }

    private fun describe(result: SearchResult) = when (result) {
        is SearchResult.Failure -> result.error.name
        SearchResult.NotConfigured -> "NOT_CONFIGURED"
        is SearchResult.Success -> "OK"
    }

    companion object {
        /** Erro de rede em todas as fontes = problema de conexão; senão, o primeiro erro informativo. */
        internal fun mostUseful(failures: List<SearchResult>): SearchResult {
            val errors = failures.filterIsInstance<SearchResult.Failure>().map { it.error }
            if (errors.isEmpty()) return failures.firstOrNull() ?: SearchResult.Failure(SearchError.UNEXPECTED)
            val network = setOf(SearchError.NO_CONNECTION, SearchError.DNS, SearchError.TIMEOUT)
            if (errors.size == failures.size && errors.all { it in network }) {
                return SearchResult.Failure(if (SearchError.TIMEOUT in errors) SearchError.TIMEOUT else errors.first())
            }
            val informative = errors.firstOrNull { it != SearchError.EMPTY && it !in network }
            return SearchResult.Failure(informative ?: errors.first())
        }
    }
}
