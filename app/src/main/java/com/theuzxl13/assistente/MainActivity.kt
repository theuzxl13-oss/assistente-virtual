package com.theuzxl13.assistente

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.theuzxl13.assistente.audio.AudioCapture
import com.theuzxl13.assistente.audio.AudioPlayer
import com.theuzxl13.assistente.databinding.ActivityMainBinding
import com.theuzxl13.assistente.gemini.FunctionCallRequest
import com.theuzxl13.assistente.gemini.GeminiLiveClient
import com.theuzxl13.assistente.gemini.GeminiLiveListener
import com.theuzxl13.assistente.gemini.ParamSpec
import com.theuzxl13.assistente.gemini.ToolDefinition
import com.theuzxl13.assistente.tools.AssistantTools
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class AssistantState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

class MainActivity : AppCompatActivity(), GeminiLiveListener {

    companion object {
        /** Ação usada pelo BelaTileService para abrir a Bela e já começar a falar. */
        const val ACTION_AUTO_START = "com.theuzxl13.assistente.ACTION_AUTO_START"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var tools: AssistantTools
    private var toneGenerator: ToneGenerator? = null
    private var tts: TextToSpeech? = null

    private var client: GeminiLiveClient? = null
    private var audioCapture: AudioCapture? = null
    private var audioPlayer: AudioPlayer? = null
    private var watchdogJob: Job? = null

    private var state = AssistantState.IDLE

    private val requiredPermissions: Array<String> = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Snackbar.make(binding.root, "Tudo certo! Pode tocar no botão para falar.", Snackbar.LENGTH_LONG).show()
        } else {
            Snackbar.make(binding.root, getString(R.string.permissions_denied), Snackbar.LENGTH_INDEFINITE)
                .setAction("Abrir configurações") { openAppSettings() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tools = AssistantTools(applicationContext)
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (_: RuntimeException) {
            // Alguns aparelhos/emuladores não conseguem alocar o gerador de bipes; o app
            // funciona normalmente sem esse feedback sonoro extra.
            null
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale("pt", "BR")
            }
        }

        setState(AssistantState.IDLE)
        binding.talkButton.setOnClickListener { onButtonTapped() }

        ensurePermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // ACTION_ASSIST chega quando a Bela é o assistente padrão e ela segura o botão de ligar.
        if (intent?.action != ACTION_AUTO_START && intent?.action != Intent.ACTION_ASSIST) return
        setIntent(Intent()) // consome a ação, pra não repetir se a activity for recriada depois
        // Só inicia uma conversa nova; nunca cancela uma em andamento.
        if (state == AssistantState.IDLE || state == AssistantState.ERROR) {
            startConversation()
        }
    }

    private fun ensurePermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun onButtonTapped() {
        when (state) {
            AssistantState.IDLE, AssistantState.ERROR -> startConversation()
            else -> cancelConversation()
        }
    }

    private fun startConversation() {
        val missing = requiredPermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            ensurePermissions()
            return
        }

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            setState(AssistantState.ERROR)
            Snackbar.make(
                binding.root,
                "Falta configurar a chave do Gemini em local.properties.",
                Snackbar.LENGTH_INDEFINITE
            ).show()
            return
        }

        setState(AssistantState.CONNECTING)
        playTone(ToneGenerator.TONE_PROP_BEEP)

        val newClient = GeminiLiveClient(BuildConfig.GEMINI_API_KEY, this)
        client = newClient
        newClient.connect(systemInstructionText(), assistantTools())
    }

    private fun cancelConversation() {
        cancelWatchdog()
        audioCapture?.stop()
        audioCapture = null
        audioPlayer?.stop()
        audioPlayer = null
        client?.close()
        client = null
        setState(AssistantState.IDLE)
    }

    // ---- GeminiLiveListener ----

    override fun onConnected() {
        runOnUiThread {
            playTone(ToneGenerator.TONE_PROP_ACK)
            startListening()
        }
    }

    /** Volta a gravar e ouvir o microfone. Usado tanto ao conectar quanto entre um pedido e outro. */
    private fun startListening() {
        setState(AssistantState.LISTENING)
        val capture = AudioCapture(
            onChunk = { chunk -> client?.sendAudioChunk(chunk) },
            onError = { onError("Não consegui acessar o microfone.") }
        )
        audioCapture = capture
        capture.start()
    }

    override fun onAudioChunk(base64Pcm24k: String) {
        runOnUiThread {
            cancelWatchdog()
            if (state != AssistantState.SPEAKING) {
                audioCapture?.stop()
                audioCapture = null
                setState(AssistantState.SPEAKING)
                val player = AudioPlayer()
                player.start()
                audioPlayer = player
            }
            audioPlayer?.playChunk(base64Pcm24k)
        }
    }

    override fun onSpeechInputComplete() {
        // Ela disse "por favor": já avisamos o servidor pra responder, então para de gravar
        // agora (senão continuaríamos mandando áudio depois de já ter avisado que acabou).
        runOnUiThread {
            audioCapture?.stop()
            audioCapture = null
            setState(AssistantState.THINKING)
            startWatchdog()
        }
    }

    /**
     * Rede de segurança: se, por qualquer motivo, a Bela não responder depois de avisarmos que
     * ela pode falar (gatilho "por favor"), cancela e avisa o erro em vez de ficar travada pra
     * sempre em "Só um instante..." sem ela ter como saber o que aconteceu.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(20_000)
            onError("A Bela demorou demais para responder.")
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    override fun onToolCall(calls: List<FunctionCallRequest>) {
        // Tudo isso roda em sequência no dispatcher principal: o microfone precisa estar
        // realmente parado ANTES de respondermos a chamada de função (senão o app pode continuar
        // mandando áudio pro meio de uma chamada já respondida), e tocarMusica pode envolver uma
        // busca de rede (suspend), por isso o lifecycleScope em vez de um runOnUiThread comum.
        lifecycleScope.launch {
            cancelWatchdog()
            audioCapture?.stop()
            audioCapture = null
            setState(AssistantState.THINKING)

            for (call in calls) {
                val result = when (call.name) {
                    "tocar_musica" -> tools.tocarMusica(call.args.optString("busca"))
                    "ligar_para" -> tools.ligarPara(call.args.optString("nome_contato"))
                    else -> JSONObject().put("sucesso", false).put("mensagem", "Ferramenta desconhecida.")
                }
                client?.sendToolResponse(call.id, call.name, result)
            }
            startWatchdog()
        }
    }

    override fun onTurnComplete() {
        runOnUiThread {
            cancelWatchdog()
            audioPlayer?.stop()
            audioPlayer = null
            // Encerra a sessão: pra pedir outra coisa, ela segura o botão de ligar, toca no botão
            // ou no ladrilho de novo — mais previsível do que deixar o microfone aberto sozinho.
            playTone(ToneGenerator.TONE_PROP_BEEP2)
            setState(AssistantState.IDLE)
            client?.close()
            client = null
        }
    }

    override fun onInterrupted() {
        runOnUiThread {
            audioPlayer?.stop()
            audioPlayer = null
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            cancelConversation()
            setState(AssistantState.ERROR)
            Snackbar.make(binding.root, "Erro: $message", Snackbar.LENGTH_LONG).show()
            // A tela sozinha não serve pra ela — o erro precisa ser falado em voz alta.
            tts?.speak(
                "Desculpa, deu um problema. Toque no botão e tente de novo.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "erro"
            )
        }
    }

    override fun onClosed() {
        runOnUiThread {
            cancelWatchdog()
            if (state != AssistantState.IDLE) {
                setState(AssistantState.IDLE)
            }
        }
    }

    // ---- Estado / UI ----

    private fun setState(newState: AssistantState) {
        state = newState
        val (colorRes, statusRes) = when (newState) {
            AssistantState.IDLE -> R.color.button_idle to R.string.status_idle
            AssistantState.CONNECTING -> R.color.button_listening to R.string.status_connecting
            AssistantState.LISTENING -> R.color.button_listening to R.string.status_listening
            AssistantState.THINKING -> R.color.button_speaking to R.string.status_thinking
            AssistantState.SPEAKING -> R.color.button_speaking to R.string.status_speaking
            AssistantState.ERROR -> R.color.button_error to R.string.status_error
        }
        binding.talkButton.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
        binding.statusText.setText(statusRes)
        binding.talkButton.contentDescription = getString(
            if (newState == AssistantState.IDLE || newState == AssistantState.ERROR) {
                R.string.button_idle_description
            } else {
                R.string.button_active_description
            }
        )
    }

    private fun playTone(tone: Int) {
        try {
            toneGenerator?.startTone(tone, 150)
        } catch (_: Exception) {
            // som é só um feedback extra, não precisa travar o app se falhar
        }
    }

    private fun assistantTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "tocar_musica",
            description = "Toca uma música, artista ou playlist no YouTube Music.",
            parameters = mapOf(
                "busca" to ParamSpec(
                    "STRING",
                    "O que buscar e tocar, por exemplo o nome da música ou do artista."
                )
            ),
            required = listOf("busca")
        ),
        ToolDefinition(
            name = "ligar_para",
            description = "Liga para um contato da agenda de telefone pelo nome.",
            parameters = mapOf(
                "nome_contato" to ParamSpec(
                    "STRING",
                    "O nome (ou parte do nome) do contato para ligar."
                )
            ),
            required = listOf("nome_contato")
        )
    )

    private fun systemInstructionText(): String = """
        Seu nome é Bela. Você é uma assistente de voz simpática e paciente, falando em português do Brasil
        com uma senhora que tem deficiência visual. Ela não consegue ver a tela do celular, então:
        - Quando ela perguntar seu nome ou pedir para você se apresentar, diga que você é a Bela.
        - Fale de forma clara, calma e objetiva. Evite respostas longas.
        - Nunca peça para ela "olhar", "ver" ou "tocar em algo na tela".
        - Se ela pedir para tocar uma música, use a ferramenta tocar_musica.
        - Se ela pedir para ligar para alguém, use a ferramenta ligar_para. Se a ferramenta disser que
          encontrou mais de um contato parecido, pergunte a ela qual das pessoas ela quer, pelo nome completo.
        - Se a ferramenta disser que não encontrou o contato, avise ela e pergunte se quer tentar outro nome.
        - Trate-a com respeito e carinho, como alguém falaria com a própria mãe.
    """.trimIndent()

    override fun onDestroy() {
        super.onDestroy()
        cancelConversation()
        toneGenerator?.release()
        tts?.stop()
        tts?.shutdown()
    }
}
