package com.theuzxl13.assistente.domain

/*
 * Interfaces que separam a lógica da conversa (pura Kotlin, testável na JVM) das APIs do
 * Android. Cada implementação real fica no pacote correspondente (voice/, telephony/, ...).
 */

// ---------------- Voz ----------------

interface SpeechOutput {
    /** Fala o texto e só retorna quando terminar (ou lança CancellationException se interrompida). */
    suspend fun speak(text: String, rateMultiplier: Float = 1f)
    fun stop()
    /** Velocidade base da fala: 1.0 é o normal do Android. */
    var rate: Float
}

sealed class Heard {
    /** Alternativas do reconhecedor, da mais provável para a menos provável. */
    data class Text(val alternatives: List<String>) : Heard()
    data object Silence : Heard()
    data class Failure(val reason: ListenFailure) : Heard()
}

enum class ListenFailure { NO_PERMISSION, NO_NETWORK, BUSY, UNAVAILABLE, OTHER }

interface SpeechInput {
    suspend fun listen(): Heard
    fun cancel()
}

/** Bipes curtos que indicam o estado sem palavras (não brigam com o TalkBack). */
interface SoundFeedback {
    fun listening()
    fun done()
    fun error()
}

// ---------------- Permissões ----------------

enum class PermissionGroup { MICROPHONE, CONTACTS, PHONE, CALL_LOG }

interface PermissionPort {
    fun has(group: PermissionGroup): Boolean
    /** Mostra a janela de permissão do Android e retorna se foi concedida. */
    suspend fun request(group: PermissionGroup): Boolean
}

// ---------------- Contatos e telefone ----------------

data class Contact(val name: String, val number: String, val label: String? = null) {
    /** Últimos 4 dígitos, para falar sem expor o número inteiro. */
    val numberEnding: String get() = number.filter { it.isDigit() }.takeLast(4)
}

interface ContactsPort {
    suspend fun findByName(query: String): List<Contact>
    suspend fun nameForNumber(number: String): String?
}

enum class CallState { IDLE, RINGING, OFFHOOK, UNKNOWN }

enum class PlaceCallResult { STARTED, OPENED_DIALER, FAILED }

interface PhonePort {
    fun placeCall(number: String): PlaceCallResult
    fun callState(): CallState
    /** Espera o estado da chamada virar [target]; retorna false se não virar até [timeoutMs]. */
    suspend fun awaitState(target: CallState, timeoutMs: Long): Boolean
    fun answer(): Boolean
    fun endCall(): Boolean
    /** Número da chamada tocando agora, se o Android tiver informado (precisa do registro de chamadas). */
    fun ringingNumber(): String?
}

// ---------------- Mídia ----------------

data class MediaItem(val title: String, val channel: String, val videoId: String)

sealed class MediaSearchResult {
    data class Found(val items: List<MediaItem>) : MediaSearchResult()
    data object NotConfigured : MediaSearchResult()
    data object Failure : MediaSearchResult()
}

interface MediaPort {
    suspend fun search(query: String): MediaSearchResult
    fun play(item: MediaItem): Boolean
    fun openSearch(query: String): Boolean
    fun pause()
    fun resume()
    fun stop()
    fun next()
    fun isPlaying(): Boolean
}

// ---------------- Pesquisa ----------------

data class Source(val title: String, val uri: String)

/** Um resultado de pesquisa de verdade: título, trecho, endereço e nome do site. */
data class WebResult(val title: String, val snippet: String, val url: String, val site: String)

enum class SearchProvider(val spokenName: String) {
    GEMINI("Pesquisa Google"),
    WIKIPEDIA("Wikipédia"),
    NEWS("Google Notícias")
}

/**
 * @param summary o que a Bela fala primeiro (curto).
 * @param details leitura completa, oferecida depois.
 * @param sources sites usados pela resposta (para citar a fonte).
 * @param results lista de resultados para "próximo resultado" (pode ser vazia).
 */
data class Answer(
    val summary: String,
    val details: String,
    val sources: List<Source>,
    val results: List<WebResult> = emptyList(),
    val provider: SearchProvider = SearchProvider.GEMINI
)

/** Motivo real de uma falha de pesquisa, para a Bela falar a mensagem certa. */
enum class SearchError {
    NO_CONNECTION,      // sem rede / conexão recusada
    TIMEOUT,            // demorou demais
    DNS,                // não achou o endereço do serviço
    UNAUTHORIZED,       // chave inválida, restrita ou sem permissão (HTTP 400 de chave, 401, 403)
    MODEL_UNAVAILABLE,  // modelo configurado não existe para esta chave (HTTP 404)
    RATE_LIMITED,       // limite de uso atingido (HTTP 429)
    SERVER,             // serviço fora do ar (HTTP 5xx)
    BAD_REQUEST,        // pedido recusado (outros HTTP 4xx)
    EMPTY,              // nenhum resultado
    INVALID_RESPONSE,   // JSON/XML inválido ou inesperado
    UNEXPECTED          // qualquer outra exceção
}

sealed class SearchResult {
    data class Success(val answer: Answer) : SearchResult()
    data object NotConfigured : SearchResult()
    data class Failure(val error: SearchError) : SearchResult()
}

data class Turn(val question: String, val answer: String)

interface SearchPort {
    suspend fun ask(question: String, history: List<Turn>): SearchResult
}

// ---------------- Aplicativos e aparelho ----------------

data class InstalledApp(val label: String, val packageName: String)

interface AppsPort {
    suspend fun find(name: String): List<InstalledApp>
    fun launch(app: InstalledApp): Boolean
}

interface DevicePort {
    /** Existe uma rede com acesso à internet (sem exigir a "validação" do Android, que falha em algumas redes). */
    fun isOnline(): Boolean
    /** Abre um endereço no navegador. */
    fun openUrl(url: String): Boolean
    /** Aumenta/diminui o volume de mídia e retorna o novo nível em porcentagem. */
    fun raiseVolume(): Int
    fun lowerVolume(): Int
    fun isTalkBackOn(): Boolean
    fun isDefaultAssistant(): Boolean
    fun openAssistantSettings(): Boolean
}

interface PreferencesPort {
    var speechRate: Float
    var onboardingDone: Boolean
}
