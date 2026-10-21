package com.theuzxl13.assistente.device

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.theuzxl13.assistente.domain.DevicePort
import kotlin.math.roundToInt

class AndroidDevice(private val context: Context) : DevicePort {

    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * Só diz "sem internet" quando não há nenhuma rede com acesso à internet. Não exige
     * NET_CAPABILITY_VALIDATED: essa checagem do Android falha em algumas redes (DNS privado,
     * operadoras, Wi-Fi com filtro) mesmo com a internet funcionando, e fazia a Bela desistir sem
     * tentar. Quem decide se a rede funciona é a própria chamada ao serviço.
     */
    override fun isOnline(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun openUrl(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    override fun raiseVolume(): Int = adjust(AudioManager.ADJUST_RAISE)
    override fun lowerVolume(): Int = adjust(AudioManager.ADJUST_LOWER)

    /** Volume de mídia (é o que a Bela, o YouTube e as músicas usam). Mostra a barra do sistema. */
    private fun adjust(direction: Int): Int {
        val audio = audio ?: return 0
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    override fun isTalkBackOn(): Boolean =
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    override fun isDefaultAssistant(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }
        return Settings.Secure.getString(context.contentResolver, "assistant")?.contains(context.packageName) == true
    }

    /** Abre a tela "App assistente digital padrão". O papel de assistente não pode ser pedido por janela. */
    override fun openAssistantSettings(): Boolean {
        val candidates = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        for (action in candidates) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            }
        }
        return false
    }
}
