package com.theuzxl13.assistente.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64

/** Toca o áudio de resposta da Live API: PCM 16-bit mono a 24kHz. */
class AudioPlayer {

    companion object {
        private const val SAMPLE_RATE = 24000
    }

    private var audioTrack: AudioTrack? = null

    fun start() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBufferSize, SAMPLE_RATE),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
        audioTrack = track
    }

    fun playChunk(base64Pcm24k: String) {
        val bytes = Base64.decode(base64Pcm24k, Base64.NO_WRAP)
        audioTrack?.write(bytes, 0, bytes.size)
    }

    fun stop() {
        audioTrack?.let {
            try {
                it.pause()
                it.flush()
                it.stop()
            } catch (_: IllegalStateException) {
                // já estava parado
            }
            it.release()
        }
        audioTrack = null
    }
}
