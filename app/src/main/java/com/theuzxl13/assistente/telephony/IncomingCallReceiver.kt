package com.theuzxl13.assistente.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Recebe o aviso do sistema de que o telefone começou a tocar. O número só vem preenchido quando
 * a permissão do registro de chamadas (READ_CALL_LOG) foi dada. Ele fica guardado só enquanto a
 * chamada existe, para a Bela responder "quem está ligando?", e é apagado quando ela termina.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                // O sistema manda o aviso duas vezes: sem número e (com permissão) com número.
                if (!number.isNullOrBlank()) RingingCallStore.save(context, number)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> RingingCallStore.clear(context)
        }
    }
}

/** Número da chamada atual, guardado só no armazenamento privado do app e apagado ao desligar. */
object RingingCallStore {

    private const val FILE = "chamada_atual"
    private const val KEY_NUMBER = "numero"
    private const val KEY_AT = "recebido_em"
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    fun save(context: Context, number: String) {
        prefs(context).edit().putString(KEY_NUMBER, number).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun currentNumber(context: Context): String? {
        val prefs = prefs(context)
        val at = prefs.getLong(KEY_AT, 0)
        if (System.currentTimeMillis() - at > MAX_AGE_MS) return null
        return prefs.getString(KEY_NUMBER, null)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
