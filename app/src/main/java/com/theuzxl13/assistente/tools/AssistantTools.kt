package com.theuzxl13.assistente.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.theuzxl13.assistente.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
private const val CATEGORIA_MUSICA = "10" // categoria "Music" da YouTube Data API

/** Implementação das ações que o Gemini pode disparar via function calling durante a conversa. */
class AssistantTools(private val context: Context) {

    private val httpClient = OkHttpClient()

    /**
     * Busca a música pela YouTube Data API (pra achar o vídeo certo) e abre já tocando ele —
     * em vez de só abrir uma tela de busca que ainda precisaria de um toque pra escolher.
     */
    suspend fun tocarMusica(busca: String): JSONObject = withContext(Dispatchers.IO) {
        val videoId = buscarVideoId(busca)

        if (videoId != null) {
            val abriu = abrirUri("https://music.youtube.com/watch?v=$videoId", YOUTUBE_MUSIC_PACKAGE) ||
                abrirUri("https://www.youtube.com/watch?v=$videoId", null)
            if (abriu) {
                return@withContext JSONObject().put("sucesso", true).put("mensagem", "Tocando \"$busca\" agora.")
            }
        }

        // Sem chave configurada, sem resultado, ou API fora do ar: cai para abrir a busca
        // (ainda funciona, só que ela vai precisar tocar no resultado).
        val query = URLEncoder.encode(busca, "UTF-8")
        val abriuBusca = abrirUri("https://music.youtube.com/search?q=$query", null)
        if (abriuBusca) {
            JSONObject()
                .put("sucesso", true)
                .put(
                    "mensagem",
                    "Abri a busca por \"$busca\", mas não consegui tocar automaticamente — " +
                        "avise que vai precisar tocar no resultado na tela."
                )
        } else {
            JSONObject().put("sucesso", false).put("mensagem", "Não consegui abrir nada para tocar \"$busca\".")
        }
    }

    private fun abrirUri(uri: String, packageName: String?): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageName != null) setPackage(packageName)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Retorna o ID do primeiro vídeo encontrado para [busca], ou null se não achar/der erro. */
    private fun buscarVideoId(busca: String): String? {
        if (BuildConfig.YOUTUBE_API_KEY.isBlank()) return null

        val url = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("maxResults", "1")
            .addQueryParameter("videoCategoryId", CATEGORIA_MUSICA)
            .addQueryParameter("q", busca)
            .addQueryParameter("key", BuildConfig.YOUTUBE_API_KEY)
            .build()

        return try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val items = JSONObject(body).optJSONArray("items") ?: return null
                if (items.length() == 0) return null
                items.getJSONObject(0).getJSONObject("id").optString("videoId").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun ligarPara(nomeContato: String): JSONObject {
        val matches = ContactsHelper.findByName(context, nomeContato)

        return when {
            matches.isEmpty() -> JSONObject()
                .put("sucesso", false)
                .put("mensagem", "Não encontrei nenhum contato chamado \"$nomeContato\" na agenda.")

            matches.size > 1 -> JSONObject()
                .put("sucesso", false)
                .put(
                    "mensagem",
                    "Encontrei mais de um contato parecido com \"$nomeContato\": " +
                        matches.joinToString(", ") { it.name } +
                        ". Pergunte a ela qual desses ela quer, pelo nome completo."
                )

            else -> {
                val contact = matches.first()
                try {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.phoneNumber}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    JSONObject()
                        .put("sucesso", true)
                        .put("mensagem", "Ligando para ${contact.name} agora.")
                } catch (e: SecurityException) {
                    JSONObject()
                        .put("sucesso", false)
                        .put("mensagem", "Não tenho permissão para ligar. Peça para verificarem as permissões do app.")
                }
            }
        }
    }
}
