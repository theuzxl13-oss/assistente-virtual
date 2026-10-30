package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.BuildConfig
import com.theuzxl13.assistente.domain.Answer
import com.theuzxl13.assistente.domain.BibleExplainerPort
import com.theuzxl13.assistente.domain.SearchError
import com.theuzxl13.assistente.domain.SearchProvider
import com.theuzxl13.assistente.domain.SearchResult
import com.theuzxl13.assistente.domain.Source
import com.theuzxl13.assistente.domain.Turn
import com.theuzxl13.assistente.domain.WebResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pesquisa com a API do Gemini usando a ferramenta oficial "Google Search grounding": o modelo faz
 * buscas reais no Google e a resposta volta com a lista de sites usados, que a Bela cita.
 *
 * Se o modelo configurado não existir mais para a chave (HTTP 404 — o Google aposenta e restringe
 * modelos com frequência), consulta a lista de modelos da própria chave e passa a usar o melhor
 * modelo "flash" disponível.
 */
class GeminiSearch(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val http: OkHttpClient = SearchHttp.client(25),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val log: SearchLog = SearchLog.NONE,
    private val today: () -> LocalDate = { LocalDate.now() }
) : BibleExplainerPort {

    /** Modelo de cada uso; troca sozinho se a chave não tiver acesso (HTTP 404). */
    private class ModelSlot(@Volatile var name: String, val preferLite: Boolean)

    private val searchModel = ModelSlot(PREFERRED_MODEL, preferLite = false)
    private val fastModel = ModelSlot(FAST_MODEL, preferLite = true)

    /** Depois de uma recusa da pesquisa Google (cota/plano), não tenta de novo por um tempo. */
    @Volatile private var groundingBlockedUntil = 0L

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** Gemini com Pesquisa Google: resposta com os sites usados. */
    suspend fun ask(question: String, history: List<Turn>): SearchResult {
        if (System.currentTimeMillis() < groundingBlockedUntil) {
            return SearchResult.Failure(SearchError.RATE_LIMITED)
        }
        val result = run(buildBody(question, history, SYSTEM_PROMPT, grounding = true), "gemini", searchModel, SearchProvider.GEMINI)
        if (result is SearchResult.Failure && result.error in GROUNDING_REFUSALS) {
            groundingBlockedUntil = System.currentTimeMillis() + GROUNDING_RETRY_MS
        }
        return result
    }

    /** Gemini respondendo com o que já sabe, sem pesquisar (a Bela avisa que pode não estar atualizado). */
    suspend fun askWithoutSearch(question: String, history: List<Turn>): SearchResult =
        run(buildBody(question, history, NO_SEARCH_PROMPT, grounding = false), "gemini.semPesquisa", fastModel, SearchProvider.GEMINI_NO_SEARCH)

    /** Explicação simples de um trecho bíblico, sem pesquisa na internet (só o texto do trecho). */
    override suspend fun explain(reference: String, text: String): SearchResult =
        run(buildBody("Explique $reference: \"$text\"", emptyList(), BIBLE_PROMPT, grounding = false), "gemini.biblia", fastModel, SearchProvider.GEMINI_NO_SEARCH)

    private suspend fun run(body: JSONObject, stage: String, slot: ModelSlot, provider: SearchProvider): SearchResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SearchResult.NotConfigured

        var outcome = generate(slot.name, body)
        if (outcome is HttpOutcome.Error && outcome.error == SearchError.MODEL_UNAVAILABLE) {
            log.warn("$stage.gerar", "erro=MODEL_UNAVAILABLE http=404 modelo=${slot.name}; procurando outro modelo")
            val replacement = discoverModel(exclude = slot.name, preferLite = slot.preferLite)
            if (replacement == null) {
                log.warn("gemini.modelos", "nenhum modelo compatível encontrado para a chave")
                return@withContext SearchResult.Failure(SearchError.MODEL_UNAVAILABLE)
            }
            slot.name = replacement
            outcome = generate(slot.name, body)
        }
        val model = slot.name

        when (outcome) {
            is HttpOutcome.Error -> {
                log.warn("$stage.gerar", "erro=${outcome.error} http=${outcome.httpCode} modelo=$model ${outcome.detail}")
                SearchResult.Failure(outcome.error)
            }
            is HttpOutcome.Ok -> try {
                parse(outcome.body, provider)?.let { SearchResult.Success(it) } ?: run {
                    log.warn("$stage.resposta", "erro=EMPTY modelo=$model")
                    SearchResult.Failure(SearchError.EMPTY)
                }
            } catch (e: JSONException) {
                log.warn("$stage.resposta", "erro=INVALID_RESPONSE modelo=$model")
                SearchResult.Failure(SearchError.INVALID_RESPONSE)
            }
        }
    }

    private fun generate(model: String, body: JSONObject): HttpOutcome {
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return SearchHttp.execute(http, request)
    }

    /** Lista os modelos que esta chave pode usar e escolhe o melhor para pesquisa. */
    private fun discoverModel(exclude: String, preferLite: Boolean): String? {
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models?pageSize=200")
            .header("x-goog-api-key", apiKey)
            .build()
        return when (val outcome = SearchHttp.execute(http, request)) {
            is HttpOutcome.Ok -> runCatching { chooseModel(outcome.body, exclude, preferLite) }.getOrNull()
            is HttpOutcome.Error -> {
                log.warn("gemini.modelos", "erro=${outcome.error} http=${outcome.httpCode}")
                null
            }
        }
    }

    private fun buildBody(question: String, history: List<Turn>, system: String, grounding: Boolean): JSONObject {
        val contents = JSONArray()
        for (turn in history) {
            contents.put(message("user", turn.question))
            contents.put(message("model", turn.answer))
        }
        contents.put(message("user", question))
        val date = today().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$system\nData de hoje: $date."))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", MAX_OUTPUT_TOKENS))
        if (grounding) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return body
    }

    private fun message(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    companion object {
        /** Primeira tentativa; se a chave não tiver acesso, [chooseModel] escolhe outro. */
        const val PREFERRED_MODEL = "gemini-flash-latest"
        /** Modelo leve e rápido para respostas sem pesquisa e explicações. */
        const val FAST_MODEL = "gemini-flash-lite-latest"
        /** Limite de tamanho da resposta: a Bela lê só um resumo, e respostas menores chegam mais rápido. */
        const val MAX_OUTPUT_TOKENS = 1024
        private const val GROUNDING_RETRY_MS = 30 * 60 * 1000L
        private val GROUNDING_REFUSALS = setOf(SearchError.RATE_LIMITED, SearchError.UNAUTHORIZED, SearchError.BAD_REQUEST)

        private val NO_SEARCH_PROMPT = """
            Você é a Bela, assistente de voz de uma senhora com cegueira total. Responda em português do Brasil.
            Você NÃO tem acesso à internet agora: responda só com o que sabe. Se a pergunta depender de informação
            atual (notícias, preços, horários, telefones, endereços, clima, resultados), diga que não consegue confirmar
            isso agora, em vez de inventar.
            Você não enxerga o ambiente da usuária: nunca dê instruções físicas como atravessar a rua, desviar de
            obstáculos ou indicar direções; nesses casos, recomende pedir ajuda a alguém de confiança ou usar a bengala.
            Seu texto será lido em voz alta: não use markdown, asteriscos, listas com símbolos, links ou emojis.
            Formato obrigatório: primeiro uma resposta direta de no máximo duas frases curtas. Se houver mais
            informação útil (passos de uma receita, por exemplo), escreva uma linha começando com DETALHES: seguida
            de até oito frases curtas, em ordem.
            Trate a usuária com respeito e carinho, como adulta, sem infantilizar.
        """.trimIndent()

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
            Você não enxerga o ambiente da usuária: nunca dê instruções físicas como atravessar a rua, desviar de
            obstáculos ou indicar direções; nesses casos, recomende pedir ajuda a alguém de confiança ou usar a bengala.
            Trate a usuária com respeito e carinho, como adulta, sem infantilizar.
        """.trimIndent()

        private val BIBLE_PROMPT = """
            Você é a Bela, assistente de voz de uma senhora evangélica com cegueira total. Responda em português do Brasil.
            Explique o trecho bíblico pedido de forma simples, respeitosa e acolhedora, numa linha evangélica tradicional.
            Baseie-se no texto do trecho e no contexto bíblico; não invente fatos, datas ou citações. Se houver
            interpretações diferentes entre igrejas, diga isso com simplicidade, sem tomar partido de denominações.
            O texto pode estar na grafia antiga da Almeida de 1911; explique com palavras de hoje.
            Seu texto será lido em voz alta: não use markdown, asteriscos, listas com símbolos, links ou emojis.
            Formato obrigatório: primeiro uma explicação de no máximo três frases curtas. Se houver mais a dizer
            (contexto, aplicação para a vida), escreva uma linha começando com DETALHES: seguida de até seis frases curtas.
        """.trimIndent()

        /**
         * Escolhe o modelo: o apelido "gemini-flash-latest" se existir; senão o "gemini-X-flash"
         * estável de versão mais alta; por último um preview. Ignora variantes de áudio, imagem, TTS etc.
         */
        internal fun chooseModel(json: String, exclude: String, preferLite: Boolean = false): String? {
            val models = JSONObject(json).optJSONArray("models") ?: return null
            val names = (0 until models.length()).map { models.getJSONObject(it) }
                .filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("\"generateContent\"") == true }
                .map { it.optString("name").removePrefix("models/") }
                .filter { it != exclude && it.contains("flash") }
                .filterNot { name -> EXCLUDED_VARIANTS.any { name.contains(it) } }
            val alias = if (preferLite) "gemini-flash-lite-latest" else "gemini-flash-latest"
            if (alias in names) return alias
            fun version(name: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            fun isStable(name: String) = !name.contains("preview") && !name.contains("exp")
            fun matchesKind(name: String) = name.contains("lite") == preferLite
            return names.sortedWith(
                compareByDescending<String> { matchesKind(it) }
                    .thenByDescending { isStable(it) }
                    .thenByDescending { version(it) }
                    .thenBy { it.length }
            ).firstOrNull()
        }

        private val EXCLUDED_VARIANTS = listOf("audio", "tts", "image", "live", "embedding", "thinking", "8b", "computer")

        /** Separa resumo e detalhes e junta as fontes. Retorna null se não veio texto. */
        internal fun parse(json: String, provider: SearchProvider = SearchProvider.GEMINI): Answer? {
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

            return Answer(
                summary = summary.ifBlank { details },
                details = if (summary.isBlank()) "" else details,
                sources = sources,
                results = sources.map { WebResult(it.title, "", it.uri, it.title) },
                provider = provider
            )
        }

        private fun cleanForSpeech(text: String): String = text
            .replace(Regex("[*#_`]"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }
}
