package com.theuzxl13.assistente.messaging

import kotlinx.coroutines.CompletableDeferred

/**
 * Ponte entre a conversa da Bela e o serviço de acessibilidade. O serviço só age quando existe um
 * pedido "armado" aqui: logo depois de a usuária confirmar a mensagem, por poucos segundos, e só
 * para o texto confirmado.
 */
object WhatsAppAutoSend {

    class Request(val text: String, val deadline: Long) {
        val result = CompletableDeferred<Boolean>()
        @Volatile var clicked = false
    }

    @Volatile var serviceConnected = false
        internal set

    @Volatile private var current: Request? = null

    fun arm(text: String, timeoutMs: Long): Request {
        current?.result?.complete(false)
        return Request(text, System.currentTimeMillis() + timeoutMs).also { current = it }
    }

    /** Pedido ainda válido, ou null (sem pedido ou já expirado). */
    fun active(): Request? {
        val request = current ?: return null
        if (System.currentTimeMillis() > request.deadline || request.result.isCompleted) {
            finish(request, sent = false)
            return null
        }
        return request
    }

    fun finish(request: Request, sent: Boolean) {
        request.result.complete(sent)
        if (current === request) current = null
    }
}
