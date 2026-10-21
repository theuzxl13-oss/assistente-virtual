package com.theuzxl13.assistente.search

import com.theuzxl13.assistente.domain.SearchError
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Registro técnico da pesquisa: etapa e código do erro, nunca a pergunta nem a chave. */
fun interface SearchLog {
    fun warn(stage: String, detail: String)

    companion object {
        val NONE = SearchLog { _, _ -> }
    }
}

/** Resposta HTTP bem-sucedida (2xx) ou o erro já classificado. */
sealed class HttpOutcome {
    data class Ok(val body: String) : HttpOutcome()
    data class Error(val error: SearchError, val httpCode: Int?, val detail: String) : HttpOutcome()
}

object SearchHttp {

    const val USER_AGENT = "Bela/2.0 (assistente de acessibilidade; https://github.com/theuzxl13-oss/assistente-virtual)"

    fun client(timeoutSeconds: Long = 20): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    /** Executa a chamada e transforma qualquer falha no motivo real. */
    fun execute(http: OkHttpClient, request: Request): HttpOutcome = try {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                HttpOutcome.Ok(body)
            } else {
                val (status, message) = googleError(body)
                HttpOutcome.Error(classifyHttp(response.code, status, message), response.code, "status=$status")
            }
        }
    } catch (e: Exception) {
        HttpOutcome.Error(classifyException(e), null, e.javaClass.simpleName)
    }

    fun classifyHttp(code: Int, status: String?, message: String?): SearchError = when {
        // O Gemini responde 400 INVALID_ARGUMENT quando a chave é inválida.
        code == 400 && (message?.contains("API key", ignoreCase = true) == true || status == "PERMISSION_DENIED") ->
            SearchError.UNAUTHORIZED
        code == 401 || code == 403 -> SearchError.UNAUTHORIZED
        code == 404 -> SearchError.MODEL_UNAVAILABLE
        code == 429 -> SearchError.RATE_LIMITED
        code in 500..599 -> SearchError.SERVER
        else -> SearchError.BAD_REQUEST
    }

    fun classifyException(e: Throwable): SearchError = when (e) {
        is UnknownHostException -> SearchError.DNS
        is SocketTimeoutException -> SearchError.TIMEOUT
        // OkHttp sinaliza o callTimeout com InterruptedIOException("timeout").
        is InterruptedIOException -> SearchError.TIMEOUT
        is ConnectException, is NoRouteToHostException -> SearchError.NO_CONNECTION
        is JSONException -> SearchError.INVALID_RESPONSE
        is SSLException -> SearchError.UNEXPECTED
        else -> SearchError.UNEXPECTED
    }

    /** Lê o formato de erro das APIs do Google: {"error": {"status": "...", "message": "..."}}. */
    private fun googleError(body: String): Pair<String?, String?> = try {
        val error = JSONObject(body).optJSONObject("error")
        error?.optString("status")?.ifBlank { null } to error?.optString("message")
    } catch (_: JSONException) {
        null to null
    }
}
