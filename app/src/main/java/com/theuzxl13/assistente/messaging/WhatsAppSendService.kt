package com.theuzxl13.assistente.messaging

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Serviço de acessibilidade "Bela, envio de mensagens".
 *
 * Escopo mínimo, de propósito:
 * - só recebe eventos do WhatsApp (packageNames no XML de configuração);
 * - não faz nada sem um pedido armado pela Bela (usuária acabou de confirmar a mensagem);
 * - só toca em Enviar se a caixa de texto contém exatamente a mensagem confirmada;
 * - confirma o envio vendo a caixa esvaziar; não guarda nem registra nada da tela.
 */
class WhatsAppSendService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        WhatsAppAutoSend.serviceConnected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        WhatsAppAutoSend.serviceConnected = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = WhatsAppAutoSend.active() ?: return
        if (request.clicked) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return
        val root = rootInActiveWindow ?: return

        val input = findInput(root, pkg) ?: return
        if (!sameText(input.text?.toString(), request.text)) return
        val send = findSendButton(root, pkg) ?: return
        if (!click(send)) return

        request.clicked = true
        verifySent(request, pkg, attempt = 0)
    }

    /** Enviado = a caixa de texto esvaziou (ou não tem mais a mensagem) logo depois do toque. */
    private fun verifySent(request: WhatsAppAutoSend.Request, pkg: String, attempt: Int) {
        handler.postDelayed({
            val input = rootInActiveWindow?.let { findInput(it, pkg) }
            val stillThere = input != null && sameText(input.text?.toString(), request.text)
            when {
                !stillThere -> WhatsAppAutoSend.finish(request, sent = true)
                attempt < VERIFY_ATTEMPTS -> verifySent(request, pkg, attempt + 1)
                else -> WhatsAppAutoSend.finish(request, sent = false)
            }
        }, VERIFY_DELAY_MS)
    }

    private fun findInput(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull()
            ?: findFirst(root) { it.className?.toString() == "android.widget.EditText" && it.isEditable }

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
            ?: findFirst(root) { node ->
                val label = (node.contentDescription ?: node.text)?.toString()?.trim()?.lowercase()
                label == "enviar" || label == "send"
            }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    private fun findFirst(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirst(child, predicate)?.let { return it }
        }
        return null
    }

    private fun sameText(onScreen: String?, expected: String): Boolean =
        onScreen != null && normalize(onScreen) == normalize(expected)

    private fun normalize(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private companion object {
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        const val VERIFY_DELAY_MS = 400L
        const val VERIFY_ATTEMPTS = 5
    }
}
