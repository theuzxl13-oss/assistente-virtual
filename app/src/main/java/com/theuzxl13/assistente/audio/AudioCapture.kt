package com.theuzxl13.assistente.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captura o microfone em PCM 16-bit mono a 16kHz (formato de entrada exigido pela Live API).
 *
 * @param onError chamado se o microfone não puder ser inicializado (ex: em uso por outro app).
 */
class AudioCapture(
    private val onChunk: (String) -> Unit,
    private val onError: () -> Unit = {}
) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_BYTES = 2048
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError()
            return
        }

        record.startRecording()

        // stop()/release() acontecem aqui dentro, na mesma thread que faz o read(), para nunca
        // liberar o AudioRecord enquanto uma leitura ainda está em andamento em outra thread.
        job = scope.launch {
            val chunk = ByteArray(CHUNK_SIZE_BYTES)
            try {
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        onChunk(Base64.encodeToString(chunk, 0, read, Base64.NO_WRAP))
                    }
                }
            } finally {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                    // já estava parado
                }
                record.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
