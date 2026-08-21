package com.theuzxl13.assistente

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/** Abre a Bela e já começa a ouvir, de qualquer lugar do sistema — usado pelo atalho de 3 cliques no volume. */
object AssistantLauncher {

    private const val CHANNEL_ALERT = "assistente_alerta"
    private const val NOTIFICATION_ID_ALERT = 2

    fun open(context: Context) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_AUTO_START
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        // Com a permissão de "aparecer sobre outros apps", conseguimos abrir a tela na hora,
        // mesmo com o celular desbloqueado e em outro app — sem precisar tocar em notificação.
        // Sem essa permissão, o Android só permite abrir sozinho quando o celular está bloqueado
        // (a notificação de tela cheia abaixo cobre esse caso, e serve de reserva pro resto).
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(openAppIntent)
                return
            } catch (_: Exception) {
                // segue para a notificação abaixo
            }
        }

        ensureAlertChannel(context)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(context.getString(R.string.notification_alert_title))
            .setContentText(context.getString(R.string.notification_alert_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun ensureAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                context.getString(R.string.notification_channel_alert),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
