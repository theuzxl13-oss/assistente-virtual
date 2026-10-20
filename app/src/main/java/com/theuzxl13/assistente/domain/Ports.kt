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

data class Answer(val summary: String, val details: String, val sources: List<Source>)

sealed class SearchResult {
    data class Success(val answer: Answer) : SearchResult()
    data object NotConfigured : SearchResult()
    data object Failure : SearchResult()
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
    fun isOnline(): Boolean
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
