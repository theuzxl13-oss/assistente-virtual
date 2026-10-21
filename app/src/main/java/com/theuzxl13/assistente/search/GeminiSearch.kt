package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.BuildConfig
import com.theuzxl13.assistente.domain.Answer
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
) {

    @Volatile private var model: String = PREFERRED_MODEL

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    suspend fun ask(question: String, history: List<Turn>): SearchResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SearchResult.NotConfigured

        var outcome = generate(model, question, history)
        if (outcome is HttpOutcome.Error && outcome.error == SearchError.MODEL_UNAVAILABLE) {
            log.warn("gemini.gerar", "erro=MODEL_UNAVAILABLE http=404 modelo=$model; procurando outro modelo")
            val replacement = discoverModel(exclude = model)
            if (replacement == null) {
                log.warn("gemini.modelos", "nenhum modelo compatível encontrado para a chave")
                return@withContext SearchResult.Failure(SearchError.MODEL_UNAVAILABLE)
            }
            model = replacement
            outcome = generate(model, question, history)
        }

        when (outcome) {
            is HttpOutcome.Error -> {
                log.warn("gemini.gerar", "erro=${outcome.error} http=${outcome.httpCode} modelo=$model ${outcome.detail}")
                SearchResult.Failure(outcome.error)
            }
            is HttpOutcome.Ok -> try {
                parse(outcome.body)?.let { SearchResult.Success(it) } ?: run {
                    log.warn("gemini.resposta", "erro=EMPTY modelo=$model")
                    SearchResult.Failure(SearchError.EMPTY)
                }
            } catch (e: JSONException) {
                log.warn("gemini.resposta", "erro=INVALID_RESPONSE modelo=$model")
                SearchResult.Failure(SearchError.INVALID_RESPONSE)
            }
        }
    }

    private fun generate(model: String, question: String, history: List<Turn>): HttpOutcome {
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(buildBody(question, history).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return SearchHttp.execute(http, request)
    }

    /** Lista os modelos que esta chave pode usar e escolhe o melhor para pesquisa. */
    private fun discoverModel(exclude: String): String? {
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models?pageSize=200")
            .header("x-goog-api-key", apiKey)
            .build()
        return when (val outcome = SearchHttp.execute(http, request)) {
            is HttpOutcome.Ok -> runCatching { chooseModel(outcome.body, exclude) }.getOrNull()
            is HttpOutcome.Error -> {
                log.warn("gemini.modelos", "erro=${outcome.error} http=${outcome.httpCode}")
                null
            }
        }
    }

    private fun buildBody(question: String, history: List<Turn>): JSONObject {
        val contents = JSONArray()
        for (turn in history) {
            contents.put(message("user", turn.question))
            contents.put(message("model", turn.answer))
        }
        contents.put(message("user", question))
        val date = today().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$SYSTEM_PROMPT\nData de hoje: $date."))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("temperature", 0.3))
    }

    private fun message(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    companion object {
        /** Primeira tentativa; se a chave não tiver acesso, [chooseModel] escolhe outro. */
        const val PREFERRED_MODEL = "gemini-flash-latest"

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

        /**
         * Escolhe o modelo: o apelido "gemini-flash-latest" se existir; senão o "gemini-X-flash"
         * estável de versão mais alta; por último um preview. Ignora variantes de áudio, imagem, TTS etc.
         */
        internal fun chooseModel(json: String, exclude: String): String? {
            val models = JSONObject(json).optJSONArray("models") ?: return null
            val names = (0 until models.length()).map { models.getJSONObject(it) }
                .filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("\"generateContent\"") == true }
                .map { it.optString("name").removePrefix("models/") }
                .filter { it != exclude && it.contains("flash") }
                .filterNot { name -> EXCLUDED_VARIANTS.any { name.contains(it) } }
            if ("gemini-flash-latest" in names) return "gemini-flash-latest"
            fun version(name: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            fun isStable(name: String) = !name.contains("preview") && !name.contains("exp") && !name.contains("lite")
            return names.sortedWith(compareByDescending<String> { isStable(it) }.thenByDescending { version(it) }.thenBy { it.length })
                .firstOrNull()
        }

        private val EXCLUDED_VARIANTS = listOf("audio", "tts", "image", "live", "embedding", "thinking", "8b", "computer")

        /** Separa resumo e detalhes e junta as fontes. Retorna null se não veio texto. */
        internal fun parse(json: String): Answer? {
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
                provider = SearchProvider.GEMINI
            )
        }

        private fun cleanForSpeech(text: String): String = text
            .replace(Regex("[*#_`]"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }
}
