package com.theuzxl13.assistente

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Detecta 3 toques rápidos no botão de aumentar volume, em qualquer lugar do sistema — dá pra
 * ativar a Bela sem precisar enxergar a tela, mesmo com o app fechado ou o celular bloqueado.
 *
 * Não consome o evento (retorna false), pra não atrapalhar o uso normal do volume — o volume
 * pode subir um pouco como efeito colateral dos 3 toques, o que é aceitável.
 */
class VolumeButtonAccessibilityService : AccessibilityService() {

    companion object {
        private const val CLIQUES_NECESSARIOS = 3
        private const val JANELA_MS = 1200L
    }

    private val timestampsDosCliques = mutableListOf<Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Em vários aparelhos, declarar canRequestFilterKeyEvents só no XML não é suficiente —
        // é preciso reforçar a flag programaticamente pra o Android realmente entregar os
        // eventos de tecla pro serviço.
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.d("VolumeButtonA11y", "Serviço conectado, ouvindo o botão de volume.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            val agora = System.currentTimeMillis()
            timestampsDosCliques.add(agora)
            timestampsDosCliques.removeAll { agora - it > JANELA_MS }
            Log.d("VolumeButtonA11y", "Volume+ detectado (${timestampsDosCliques.size}/$CLIQUES_NECESSARIOS)")

            if (timestampsDosCliques.size >= CLIQUES_NECESSARIOS) {
                timestampsDosCliques.clear()
                AssistantLauncher.open(this)
            }
        }
        return false
    }
}
