package com.theuzxl13.assistente.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.theuzxl13.assistente.BuildConfig
import com.theuzxl13.assistente.domain.MediaItem
import com.theuzxl13.assistente.domain.MediaPort
import com.theuzxl13.assistente.domain.MediaSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Busca vídeos pela YouTube Data API v3 (para ler os títulos em voz alta) e abre o escolhido no
 * app oficial do YouTube. Os controles (pausar, continuar, próxima) usam as teclas de mídia do
 * Android, que o player ativo recebe pela sessão de mídia dele — a Bela não controla o YouTube
 * diretamente.
 */
class YouTubeMedia(private val context: Context) : MediaPort {

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val audio = context.getSystemService(AudioManager::class.java)

    override suspend fun search(query: String): MediaSearchResult = withContext(Dispatchers.IO) {
        if (BuildConfig.YOUTUBE_API_KEY.isBlank()) return@withContext MediaSearchResult.NotConfigured
        val url = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("maxResults", "9")
            .addQueryParameter("regionCode", "BR")
            .addQueryParameter("relevanceLanguage", "pt")
            .addQueryParameter("safeSearch", "moderate")
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder().url(url).header("X-Goog-Api-Key", BuildConfig.YOUTUBE_API_KEY).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext MediaSearchResult.Failure
                val items = JSONObject(response.body?.string().orEmpty()).optJSONArray("items")
                    ?: return@withContext MediaSearchResult.Found(emptyList())
                val results = (0 until items.length()).mapNotNull { i ->
                    val item = items.getJSONObject(i)
                    val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                    val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null
                    if (id.isBlank()) return@mapNotNull null
                    MediaItem(
                        title = spokenTitle(snippet.optString("title")),
                        channel = decodeEntities(snippet.optString("channelTitle")),
                        videoId = id
                    )
                }
                MediaSearchResult.Found(results)
            }
        } catch (_: Exception) {
            MediaSearchResult.Failure
        }
    }

    override fun play(item: MediaItem): Boolean {
        val uri = Uri.parse("https://www.youtube.com/watch?v=${item.videoId}")
        return open(uri, YOUTUBE_PACKAGE) || open(uri, YOUTUBE_MUSIC_PACKAGE) || open(uri, null)
    }

    override fun openSearch(query: String): Boolean {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        return open(uri, YOUTUBE_PACKAGE) || open(uri, null)
    }

    private fun open(uri: Uri, packageName: String?): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null) intent.setPackage(packageName)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    override fun pause() = mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    override fun resume() = mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    override fun stop() = mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) // o YouTube não trata MEDIA_STOP
    override fun next() = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    override fun isPlaying(): Boolean = audio?.isMusicActive == true

    private fun mediaKey(code: Int) {
        val time = SystemClock.uptimeMillis()
        audio?.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        audio?.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    }

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

        /** Título bom para ouvir: sem "(Clipe Oficial)", "[HD]", "| Ao Vivo" e afins. */
        internal fun spokenTitle(raw: String): String {
            val decoded = decodeEntities(raw)
            val cleaned = decoded
                .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
                .substringBefore(" | ")
                .replace(Regex("#\\S+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '-', '–', '|')
            return cleaned.ifBlank { decoded }
        }

        internal fun decodeEntities(text: String): String = text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
