package com.theuzxl13.assistente.messaging

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.theuzxl13.assistente.domain.MessageOpenResult
import com.theuzxl13.assistente.domain.MessagingPort

/**
 * Abre a conversa do WhatsApp com o texto preenchido pelo link oficial "click to chat"
 * (api.whatsapp.com/send). O WhatsApp sempre exige que a pessoa toque em Enviar.
 */
class AndroidMessaging(private val context: Context) : MessagingPort {

    override fun isWhatsAppInstalled(): Boolean = installedPackage() != null

    override fun openWhatsApp(phoneDigits: String, text: String): MessageOpenResult {
        val packageName = installedPackage() ?: return MessageOpenResult.NOT_INSTALLED
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phoneDigits&text=${Uri.encode(text)}")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            MessageOpenResult.OPENED
        } catch (_: ActivityNotFoundException) {
            MessageOpenResult.FAILED
        }
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
    }
}
