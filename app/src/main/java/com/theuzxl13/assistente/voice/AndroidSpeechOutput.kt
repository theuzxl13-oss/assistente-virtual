package com.theuzxl13.assistente.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.theuzxl13.assistente.domain.SpeechOutput
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Fala com o TextToSpeech do Android, em português do Brasil. Prefere uma voz que funcione sem
 * internet (as vozes pt-BR do "Serviços de fala do Google" vêm instaladas na maioria dos Motorola).
 */
class AndroidSpeechOutput(context: Context, initialRate: Float) : SpeechOutput {

    private val ptBr = Locale("pt", "BR")
    private val ready = CompletableDeferred<Boolean>()
    private val waiting = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    private lateinit var tts: TextToSpeech

    override var rate: Float = initialRate

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready.complete(false)
                return@TextToSpeech
            }
            val result = tts.setLanguage(ptBr)
            val available = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (available) chooseBestVoice()
            ready.complete(available)
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)
        })
    }

    /** true se existe voz em português do Brasil pronta para uso. */
    suspend fun awaitReady(): Boolean = ready.await()

    /** true se a voz escolhida depende de internet (as locais são preferidas). */
    fun usesNetworkVoice(): Boolean = runCatching { tts.voice?.isNetworkConnectionRequired == true }.getOrDefault(false)

    private fun chooseBestVoice() {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull() ?: return
        val best = voices
            .filter { it.locale == ptBr && !it.isNetworkConnectionRequired && Voice.FEATURE_NOT_INSTALLED !in it.features }
            .maxByOrNull { it.quality }
        if (best != null) tts.voice = best
    }

    private fun finish(utteranceId: String) {
        waiting.remove(utteranceId)?.let { if (it.isActive) it.resume(Unit) }
    }

    override suspend fun speak(text: String, rateMultiplier: Float) {
        if (text.isBlank() || !ready.await()) return
        suspendCancellableCoroutine { continuation ->
            val id = UUID.randomUUID().toString()
            waiting[id] = continuation
            tts.setSpeechRate(rate * rateMultiplier)
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result == TextToSpeech.ERROR) finish(id)
            continuation.invokeOnCancellation {
                waiting.remove(id)
                tts.stop()
            }
        }
    }

    override fun stop() {
        tts.stop()
        waiting.keys.toList().forEach { finish(it) }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
