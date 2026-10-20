package com.theuzxl13.assistente.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.theuzxl13.assistente.domain.Heard
import com.theuzxl13.assistente.domain.ListenFailure
import com.theuzxl13.assistente.domain.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Reconhecimento de voz com o SpeechRecognizer do Android (serviço do app Google nos Motorola).
 *
 * Usa a internet quando disponível (reconhece melhor) e, se a rede falhar, tenta de novo pedindo
 * o reconhecimento offline — que só funciona se o pacote de português estiver baixado no aparelho.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    override suspend fun listen(): Heard = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext Heard.Failure(ListenFailure.UNAVAILABLE)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Heard.Failure(ListenFailure.NO_PERMISSION)
        }
        // Dá tempo do bipe de "pode falar" terminar, para ele não ser captado como fala.
        delay(BEEP_GAP_MS)
        val online = listenOnce(preferOffline = false)
        if (online is Heard.Failure && online.reason == ListenFailure.NO_NETWORK) {
            val offline = listenOnce(preferOffline = true)
            // Sem pacote offline o reconhecedor devolve erro de idioma: o problema real é a rede.
            if (offline is Heard.Failure) online else offline
        } else {
            online
        }
    }

    private suspend fun listenOnce(preferOffline: Boolean): Heard = suspendCancellableCoroutine { continuation ->
        val speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        fun finish(result: Heard) {
            if (continuation.isActive) continuation.resume(result)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                    .filter { it.isNotBlank() }
                finish(if (texts.isEmpty()) Heard.Silence else Heard.Text(texts))
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    // O serviço ficou num estado ruim: recria na próxima tentativa.
                    recognizer?.destroy()
                    recognizer = null
                }
                finish(mapError(error))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Tolera pausas no meio da frase (nem todo reconhecedor respeita esses valores).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        speechRecognizer.startListening(intent)

        continuation.invokeOnCancellation { mainHandler.post { recognizer?.cancel() } }
    }

    private fun mapError(error: Int): Heard = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Heard.Silence
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> Heard.Failure(ListenFailure.NO_NETWORK)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Heard.Failure(ListenFailure.NO_PERMISSION)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> Heard.Failure(ListenFailure.BUSY)
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            Heard.Failure(ListenFailure.UNAVAILABLE)
        else -> Heard.Failure(ListenFailure.OTHER)
    }

    private companion object {
        const val BEEP_GAP_MS = 250L
    }

    override fun cancel() {
        mainHandler.post { recognizer?.cancel() }
    }

    fun destroy() {
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }
}
