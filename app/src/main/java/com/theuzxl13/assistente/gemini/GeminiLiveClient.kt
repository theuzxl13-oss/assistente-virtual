package com.theuzxl13.assistente.gemini

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface GeminiLiveListener {
    fun onConnected()
    fun onAudioChunk(base64Pcm24k: String)
    fun onToolCall(calls: List<FunctionCallRequest>)
    fun onTurnComplete()
    fun onInterrupted()
    fun onError(message: String)
    fun onClosed()

    /**
     * Ela disse a palavra-gatilho ("por favor") e o cliente já avisou o servidor pra responder
     * na hora, sem esperar o silêncio de fim de fala. É a deixa pra parar de gravar o microfone.
     */
    fun onSpeechInputComplete()
}

private const val TAG = "GeminiLiveClient"
private const val LIVE_ENDPOINT =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

// Dita essa palavra (em qualquer lugar da frase) que a Bela já entende que a pessoa terminou de
// falar e responde na hora, sem esperar a pausa de silêncio configurada.
private const val TRIGGER_PHRASE = "por favor"

class GeminiLiveClient(
    private val apiKey: String,
    private val listener: GeminiLiveListener
) {
    private var webSocket: WebSocket? = null
    private val transcriptoAcumulado = StringBuilder()
    private var gatilhoJaDisparado = false

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect(systemInstruction: String, tools: List<ToolDefinition>) {
        val request = Request.Builder()
            .url("$LIVE_ENDPOINT?key=$apiKey")
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildSetupMessage(systemInstruction, tools))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Mensagem recebida: $text")
                    handleServerMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val text = bytes.utf8()
                    Log.d(TAG, "Mensagem recebida (bytes): $text")
                    handleServerMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Falha na conexão com a Live API (resposta: ${response?.code})", t)
                    listener.onError(t.message ?: "Erro de conexão")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Conexão fechada pelo servidor: code=$code reason=$reason")
                    listener.onClosed()
                }
            }
        )
    }

    private fun handleServerMessage(raw: String) {
        for (event in parseServerMessage(raw)) {
            when (event) {
                is LiveEvent.SetupComplete -> listener.onConnected()
                is LiveEvent.InputTranscript -> handleInputTranscript(event.text)
                is LiveEvent.AudioChunk -> listener.onAudioChunk(event.base64Pcm24k)
                is LiveEvent.ToolCall -> listener.onToolCall(event.calls)
                is LiveEvent.TurnComplete -> {
                    transcriptoAcumulado.clear()
                    gatilhoJaDisparado = false
                    listener.onTurnComplete()
                }
                is LiveEvent.Interrupted -> listener.onInterrupted()
                is LiveEvent.Unknown -> Log.d(TAG, "Mensagem não tratada: ${event.raw}")
            }
        }
    }

    private fun handleInputTranscript(text: String) {
        if (gatilhoJaDisparado) {
            Log.d(TAG, "Transcrição ignorada (gatilho já disparado): \"$text\"")
            return
        }
        transcriptoAcumulado.append(text)
        Log.d(TAG, "Transcrição acumulada: \"$transcriptoAcumulado\"")
        if (transcriptoAcumulado.toString().lowercase().contains(TRIGGER_PHRASE)) {
            gatilhoJaDisparado = true
            Log.d(TAG, "Gatilho \"por favor\" disparado — enviando audioStreamEnd")
            webSocket?.send(buildAudioStreamEndMessage())
            listener.onSpeechInputComplete()
        }
    }

    fun sendAudioChunk(base64Pcm16k: String) {
        // Depois do audioStreamEnd (gatilho "por favor"), nunca mais manda áudio nessa sessão —
        // a Live API reabre o fluxo se receber áudio depois do audioStreamEnd (documentado), o
        // que cancelaria a finalização antecipada e travaria esperando silêncio de novo. Como
        // a captura do microfone para de forma assíncrona (outra thread), sem essa trava um
        // último pedaço de áudio ainda em trânsito podia escapar e reabrir o fluxo sem querer.
        if (gatilhoJaDisparado) {
            Log.d(TAG, "Chunk de áudio bloqueado (depois do audioStreamEnd)")
            return
        }
        webSocket?.send(buildRealtimeAudioMessage(base64Pcm16k))
    }

    fun sendToolResponse(id: String, name: String, result: JSONObject) {
        webSocket?.send(buildToolResponseMessage(id, name, result))
    }

    fun close() {
        webSocket?.close(1000, "Encerrado pelo app")
        webSocket = null
    }
}
