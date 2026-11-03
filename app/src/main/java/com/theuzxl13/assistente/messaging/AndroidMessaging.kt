package com.theuzxl13.assistente.messaging

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.theuzxl13.assistente.domain.MessageOpenResult
import com.theuzxl13.assistente.domain.MessagingPort
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Abre a conversa do WhatsApp com o texto preenchido pelo link oficial "click to chat"
 * (api.whatsapp.com/send). Com o serviço "Bela, envio de mensagens" ativado, arma o envio e espera
 * o serviço confirmar que a mensagem saiu; sem ele, a usuária toca em Enviar.
 */
class AndroidMessaging(private val context: Context) : MessagingPort {

    override fun isWhatsAppInstalled(): Boolean = installedPackage() != null

    override fun isAutoSendEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val ours = enabled.any { info ->
            info.resolveInfo?.serviceInfo?.let {
                it.packageName == context.packageName && it.name == WhatsAppSendService::class.java.name
            } == true
        }
        return ours && WhatsAppAutoSend.serviceConnected
    }

    override suspend fun openWhatsApp(phoneDigits: String, text: String, autoSend: Boolean): MessageOpenResult {
        val packageName = installedPackage() ?: return MessageOpenResult.NOT_INSTALLED
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phoneDigits&text=${Uri.encode(text)}")
        // Arma antes de abrir, para o serviço já encontrar o pedido quando a conversa aparecer.
        val request = if (autoSend) WhatsAppAutoSend.arm(text, AUTO_SEND_WINDOW_MS) else null
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            request?.let { WhatsAppAutoSend.finish(it, sent = false) }
            return MessageOpenResult.FAILED
        }
        request ?: return MessageOpenResult.OPENED
        val sent = withTimeoutOrNull(AUTO_SEND_WINDOW_MS + VERIFY_MARGIN_MS) { request.result.await() } ?: false
        WhatsAppAutoSend.finish(request, sent)
        return if (sent) MessageOpenResult.SENT else MessageOpenResult.SEND_FAILED
    }

    override fun openAutoSendSettings(): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun installedPackage(): String? = PACKAGES.firstOrNull { name ->
        try {
            context.packageManager.getPackageInfo(name, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private companion object {
        val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        /** Tempo para o WhatsApp abrir a conversa e o serviço tocar em Enviar. */
        const val AUTO_SEND_WINDOW_MS = 12_000L
        const val VERIFY_MARGIN_MS = 3_000L
    }
}
