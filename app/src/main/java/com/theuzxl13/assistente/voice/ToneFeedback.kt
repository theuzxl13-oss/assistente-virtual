package com.theuzxl13.assistente.voice

import android.media.AudioManager
import android.media.ToneGenerator
import com.theuzxl13.assistente.domain.SoundFeedback

/** Bipes curtos: "pode falar", "terminei" e "deu erro". */
class ToneFeedback : SoundFeedback {

    private val generator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    } catch (_: RuntimeException) {
        // Alguns aparelhos não conseguem alocar o gerador; a Bela funciona sem os bipes.
        null
    }

    override fun listening() = play(ToneGenerator.TONE_PROP_BEEP, 150)
    override fun done() = play(ToneGenerator.TONE_PROP_BEEP2, 150)
    override fun error() = play(ToneGenerator.TONE_PROP_NACK, 250)

    private fun play(tone: Int, durationMs: Int) {
        try {
            generator?.startTone(tone, durationMs)
        } catch (_: Exception) {
            // bipe é só um reforço; nunca deve travar a conversa
        }
    }

    fun release() {
        generator?.release()
    }
}
