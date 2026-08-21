package com.theuzxl13.assistente.gemini

import org.json.JSONArray
import org.json.JSONObject

/** Nome do modelo Gemini Live usado para o diálogo por áudio nativo. */
const val GEMINI_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"

private const val MIME_AUDIO_IN = "audio/pcm;rate=16000"

// Quanto tempo de silêncio a Live API espera antes de considerar que a pessoa terminou de falar.
// Valores mais altos toleram melhor pausas naturais no meio de frases longas (bom pra fala mais
// pausada), ao custo de a resposta demorar um pouquinho mais pra começar depois que ela para.
private const val VAD_SILENCE_DURATION_MS = 1200
private const val VAD_PREFIX_PADDING_MS = 300

data class ParamSpec(val type: String, val description: String)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ParamSpec>,
    val required: List<String>
)

fun buildSetupMessage(
    systemInstruction: String,
    tools: List<ToolDefinition>,
    voiceName: String = "Kore"
): String {
    val generationConfig = JSONObject().apply {
        put("responseModalities", JSONArray(listOf("AUDIO")))
        put("speechConfig", JSONObject().apply {
            put("voiceConfig", JSONObject().apply {
                put("prebuiltVoiceConfig", JSONObject().apply {
                    put("voiceName", voiceName)
                })
            })
        })
    }

    val toolsArray = JSONArray()
    if (tools.isNotEmpty()) {
        val functionDeclarations = JSONArray()
        for (tool in tools) {
            val properties = JSONObject()
            for ((paramName, spec) in tool.parameters) {
                properties.put(
                    paramName,
                    JSONObject().apply {
                        put("type", spec.type)
                        put("description", spec.description)
                    }
                )
            }
            functionDeclarations.put(
                JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", properties)
                            put("required", JSONArray(tool.required))
                        }
                    )
                }
            )
        }
        toolsArray.put(JSONObject().apply { put("functionDeclarations", functionDeclarations) })
    }

    val setup = JSONObject().apply {
        put("model", "models/$GEMINI_LIVE_MODEL")
        put("generationConfig", generationConfig)
        put(
            "systemInstruction",
            JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            }
        )
        if (toolsArray.length() > 0) {
            put("tools", toolsArray)
        }
        // Habilita a transcrição do que a pessoa está falando, em tempo real — usada pra
        // detectar a palavra-gatilho "por favor" e responder mais rápido (ver GeminiLiveClient).
        put("inputAudioTranscription", JSONObject())
        put(
            "realtimeInputConfig",
            JSONObject().apply {
                put(
                    "automaticActivityDetection",
                    JSONObject().apply {
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        put("prefixPaddingMs", VAD_PREFIX_PADDING_MS)
                        put("silenceDurationMs", VAD_SILENCE_DURATION_MS)
                    }
                )
            }
        )
    }

    return JSONObject().put("setup", setup).toString()
}

fun buildRealtimeAudioMessage(base64Pcm16k: String): String {
    return JSONObject()
        .put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject().put("mimeType", MIME_AUDIO_IN).put("data", base64Pcm16k)
            )
        )
        .toString()
}

/**
 * Avisa a Live API que não vai vir mais áudio dessa vez, pra ela parar de esperar silêncio e já
 * responder — usado quando detectamos a palavra-gatilho "por favor" na transcrição ao vivo.
 */
fun buildAudioStreamEndMessage(): String {
    return JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()
}

fun buildToolResponseMessage(id: String, name: String, resultJson: JSONObject): String {
    val functionResponse = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("response", resultJson)
    }
    return JSONObject()
        .put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(functionResponse)))
        .toString()
}

data class FunctionCallRequest(val id: String, val name: String, val args: JSONObject)

sealed class LiveEvent {
    data object SetupComplete : LiveEvent()
    data class InputTranscript(val text: String) : LiveEvent()
    data class AudioChunk(val base64Pcm24k: String) : LiveEvent()
    data class ToolCall(val calls: List<FunctionCallRequest>) : LiveEvent()
    data object TurnComplete : LiveEvent()
    data object Interrupted : LiveEvent()
    data class Unknown(val raw: String) : LiveEvent()
}

/**
 * Uma mensagem do servidor pode carregar mais de um evento relevante ao mesmo tempo — por
 * exemplo, o schema da Live API permite modelTurn (áudio) e turnComplete no mesmo objeto
 * serverContent, então o último pedaço de áudio de uma resposta pode vir junto com o aviso de
 * que o turno terminou. Por isso retornamos uma lista, na ordem em que devem ser processados.
 */
fun parseServerMessage(raw: String): List<LiveEvent> {
    val json = JSONObject(raw)
    val events = mutableListOf<LiveEvent>()

    if (json.has("setupComplete")) {
        events.add(LiveEvent.SetupComplete)
        return events
    }

    if (json.has("toolCall")) {
        val calls = mutableListOf<FunctionCallRequest>()
        val functionCalls = json.getJSONObject("toolCall").optJSONArray("functionCalls")
        if (functionCalls != null) {
            for (i in 0 until functionCalls.length()) {
                val call = functionCalls.getJSONObject(i)
                calls.add(
                    FunctionCallRequest(
                        id = call.optString("id"),
                        name = call.optString("name"),
                        args = call.optJSONObject("args") ?: JSONObject()
                    )
                )
            }
        }
        events.add(LiveEvent.ToolCall(calls))
        return events
    }

    if (json.has("serverContent")) {
        val serverContent = json.getJSONObject("serverContent")

        if (serverContent.optBoolean("interrupted", false)) {
            events.add(LiveEvent.Interrupted)
        }

        val inputTranscription = serverContent.optJSONObject("inputTranscription")
        val transcriptText = inputTranscription?.optString("text")
        if (!transcriptText.isNullOrEmpty()) {
            events.add(LiveEvent.InputTranscript(transcriptText))
        }

        val modelTurn = serverContent.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                if (inlineData != null) {
                    events.add(LiveEvent.AudioChunk(inlineData.optString("data")))
                }
            }
        }

        if (serverContent.optBoolean("turnComplete", false)) {
            events.add(LiveEvent.TurnComplete)
        }
    }

    if (events.isEmpty()) {
        events.add(LiveEvent.Unknown(raw))
    }

    return events
}
