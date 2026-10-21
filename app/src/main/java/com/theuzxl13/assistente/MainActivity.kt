package com.theuzxl13.assistente

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.theuzxl13.assistente.apps.AndroidApps
import com.theuzxl13.assistente.contacts.AndroidContacts
import com.theuzxl13.assistente.databinding.ActivityMainBinding
import com.theuzxl13.assistente.device.AndroidDevice
import com.theuzxl13.assistente.domain.BelaIntent
import com.theuzxl13.assistente.domain.ConversationEngine
import com.theuzxl13.assistente.domain.ConversationState
import com.theuzxl13.assistente.domain.Onboarding
import com.theuzxl13.assistente.media.YouTubeMedia
import com.theuzxl13.assistente.search.GeminiSearch
import com.theuzxl13.assistente.search.NewsSearch
import com.theuzxl13.assistente.search.SearchLog
import com.theuzxl13.assistente.search.WebSearch
import com.theuzxl13.assistente.search.WikipediaSearch
import com.theuzxl13.assistente.settings.UserPreferences
import com.theuzxl13.assistente.telephony.AndroidPhone
import com.theuzxl13.assistente.voice.AndroidSpeechInput
import com.theuzxl13.assistente.voice.AndroidSpeechOutput
import com.theuzxl13.assistente.voice.ToneFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tela única da Bela: um botão enorme "Falar com a Bela" (que também interrompe) e dois botões
 * grandes de apoio, Repetir e Ajuda. Toda a informação importante também é falada.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Usada pelo ladrilho das Configurações Rápidas para abrir a Bela já ouvindo. */
        const val ACTION_AUTO_START = "com.theuzxl13.assistente.ACTION_AUTO_START"
    }

    private lateinit var binding: ActivityMainBinding
    private val permissions = ActivityPermissions(this)

    private lateinit var prefs: UserPreferences
    private lateinit var speech: AndroidSpeechOutput
    private lateinit var input: AndroidSpeechInput
    private lateinit var sounds: ToneFeedback
    private lateinit var engine: ConversationEngine
    private lateinit var onboarding: Onboarding
    private lateinit var audioManager: AudioManager

    private var session: Job? = null
    private var currentState = ConversationState.INATIVA
    private var focusRequest: AudioFocusRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AudioManager::class.java)
        prefs = UserPreferences(this)
        speech = AndroidSpeechOutput(this, prefs.speechRate)
        input = AndroidSpeechInput(this)
        sounds = ToneFeedback()
        val device = AndroidDevice(this)
        // Registro técnico da pesquisa (logcat, tag "BelaPesquisa"): etapa e código do erro, sem a pergunta nem a chave.
        val searchLog = SearchLog { stage, detail -> Log.w("BelaPesquisa", "etapa=$stage $detail") }

        engine = ConversationEngine(
            speech = speech,
            input = input,
            sounds = sounds,
            permissions = permissions,
            contacts = AndroidContacts(this),
            phone = AndroidPhone(this),
            media = YouTubeMedia(this),
            search = WebSearch(GeminiSearch(log = searchLog), WikipediaSearch(log = searchLog), NewsSearch(log = searchLog), searchLog),
            apps = AndroidApps(this),
            device = device,
            prefs = prefs
        )
        engine.onStateChanged = { state -> runOnUiThread { render(state) } }
        onboarding = Onboarding(speech, input, sounds, permissions, device, prefs)
        onboarding.onStateChanged = { state -> runOnUiThread { render(state) } }

        binding.talkButton.setOnClickListener { toggleSession() }
        binding.repeatButton.setOnClickListener { restartWith(BelaIntent.Repeat) }
        binding.helpButton.setOnClickListener { restartWith(BelaIntent.Help) }
        render(ConversationState.INATIVA)

        lifecycleScope.launch {
            if (!speech.awaitReady()) {
                showVoiceMissing()
                return@launch
            }
            // Abrir a Bela já é um pedido para falar com ela.
            if (savedInstanceState == null && !handleIntent(intent)) startSession()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Trata ladrilho, botão de ligar (assistente) e "Compartilhar". Retorna true se tratou. */
    private fun handleIntent(intent: Intent?): Boolean {
        when (intent?.action) {
            ACTION_AUTO_START, Intent.ACTION_ASSIST -> {
                setIntent(Intent())
                if (session?.isActive != true) startSession()
                return true
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                setIntent(Intent())
                if (!text.isNullOrBlank()) {
                    engine.sharedText = text
                    restartWith(BelaIntent.ReadShared)
                    return true
                }
            }
        }
        return false
    }

    private fun toggleSession() {
        if (session?.isActive == true) {
            session?.cancel()
        } else {
            startSession()
        }
    }

    private fun restartWith(start: BelaIntent) {
        session?.cancel()
        startSession(start)
    }

    private fun startSession(start: BelaIntent? = null) {
        val previous = session
        session = lifecycleScope.launch {
            previous?.join()
            requestAudioFocus()
            try {
                if (!prefs.onboardingDone) onboarding.run() else engine.runSession(start)
            } finally {
                abandonAudioFocus()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Microfone não fica aberto com a Bela fora da tela. Ações em andamento (verificar se a
        // ligação começou, avisar se o vídeo não tocou) continuam.
        if (currentState == ConversationState.OUVINDO || currentState == ConversationState.AGUARDANDO_CONFIRMACAO) {
            session?.cancel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.cancel()
        input.destroy()
        speech.shutdown()
        sounds.release()
    }

    /** Pausa a música enquanto a Bela fala/ouve; o player retoma sozinho quando ela termina. */
    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun render(state: ConversationState) {
        currentState = state
        val (colorRes, statusRes) = when (state) {
            ConversationState.INATIVA, ConversationState.CANCELADA -> R.color.button_idle to R.string.status_idle
            ConversationState.OUVINDO -> R.color.button_listening to R.string.status_listening
            ConversationState.AGUARDANDO_CONFIRMACAO -> R.color.button_listening to R.string.status_waiting
            ConversationState.PROCESSANDO -> R.color.button_speaking to R.string.status_thinking
            ConversationState.FALANDO -> R.color.button_speaking to R.string.status_speaking
            ConversationState.EXECUTANDO_ACAO -> R.color.button_speaking to R.string.status_acting
            ConversationState.ERRO -> R.color.button_error to R.string.status_error
        }
        val idle = state == ConversationState.INATIVA || state == ConversationState.CANCELADA || state == ConversationState.ERRO
        binding.talkButton.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
        binding.talkButton.setText(if (idle) R.string.button_talk else R.string.button_interrupt)
        binding.statusText.setText(statusRes)
    }

    private fun showVoiceMissing() {
        binding.statusText.setText(R.string.voice_missing)
        sounds.error()
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (_: ActivityNotFoundException) {
            // Sem instalador de voz: a mensagem na tela orienta quem estiver ajudando.
        }
    }
}
