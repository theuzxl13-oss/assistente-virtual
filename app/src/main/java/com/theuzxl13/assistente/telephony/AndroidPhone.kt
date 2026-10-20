package com.theuzxl13.assistente.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.theuzxl13.assistente.domain.CallState
import com.theuzxl13.assistente.domain.PhonePort
import com.theuzxl13.assistente.domain.PlaceCallResult
import kotlinx.coroutines.delay

/**
 * Ligações pelas APIs oficiais, sem ser o app de telefone padrão:
 * - fazer: Intent.ACTION_CALL (permissão CALL_PHONE); sem ela, abre o discador (ACTION_DIAL);
 * - atender/recusar/desligar: TelecomManager.acceptRingingCall / endCall (permissão ANSWER_PHONE_CALLS);
 * - estado: TelephonyManager (permissão READ_PHONE_STATE).
 *
 * O app de telefone do sistema continua cuidando da chamada (tela, viva-voz, emergência).
 */
class AndroidPhone(private val context: Context) : PhonePort {

    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun placeCall(number: String): PlaceCallResult {
        val uri = Uri.fromParts("tel", number, null)
        if (granted(Manifest.permission.CALL_PHONE)) {
            try {
                context.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return PlaceCallResult.STARTED
            } catch (_: SecurityException) {
            } catch (_: ActivityNotFoundException) {
            }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            PlaceCallResult.OPENED_DIALER
        } catch (_: ActivityNotFoundException) {
            PlaceCallResult.FAILED
        }
    }

    @SuppressLint("MissingPermission")
    override fun callState(): CallState {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return CallState.UNKNOWN
        return try {
            @Suppress("DEPRECATION")
            when (telephony?.callState) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
                else -> CallState.UNKNOWN
            }
        } catch (_: SecurityException) {
            CallState.UNKNOWN
        }
    }

    override suspend fun awaitState(target: CallState, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited <= timeoutMs) {
            if (callState() == target) return true
            delay(POLL_MS)
            waited += POLL_MS
        }
        return false
    }

    @SuppressLint("MissingPermission")
    override fun answer(): Boolean {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try {
            @Suppress("DEPRECATION")
            telecom?.acceptRingingCall()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun endCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try {
            @Suppress("DEPRECATION")
            telecom?.endCall() == true
        } catch (_: SecurityException) {
            false
        }
    }

    override fun ringingNumber(): String? = RingingCallStore.currentNumber(context)

    private companion object {
        const val POLL_MS = 250L
    }
}
