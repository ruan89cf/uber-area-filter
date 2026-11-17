package com.ruan.uberareafilter.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Recebe o codigo de pareamento ADB de 6 digitos digitado diretamente na notificacao
 * enquanto o usuario esta na tela de Depuracao por Wi-Fi (sem precisar sair dela).
 */
class AdbPairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SUBMIT_PAIRING_CODE) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = remoteInput.getCharSequence(KEY_PAIRING_CODE)?.toString()?.trim().orEmpty()
        val pendingResult = goAsync()
        val appCtx = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = StandaloneAdbBridge.pairAndStart(appCtx, code)
                showResultNotification(
                    appCtx,
                    ok = res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED,
                    message = res.detail
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SUBMIT_PAIRING_CODE = "com.ruan.uberareafilter.ACTION_SUBMIT_ADB_PAIRING_CODE"
        const val KEY_PAIRING_CODE = "key_adb_pairing_code"
        private const val CHANNEL_ID = "standalone_adb_pairing_channel"
        private const val NOTIFICATION_ID = 7701

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Pareamento ADB Independente (Uber)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Permite digitar o código de 6 dígitos da Depuração por Wi-Fi direto na notificação"
                }
                nm.createNotificationChannel(channel)
            }
        }

        fun showPairingPromptNotification(context: Context) {
            ensureChannel(context)
            val replyIntent = Intent(context, AdbPairingReceiver::class.java).apply {
                action = ACTION_SUBMIT_PAIRING_CODE
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val replyPendingIntent = PendingIntent.getBroadcast(context, 7701, replyIntent, flags)

            val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
                .setLabel("Código de 6 dígitos (ex: 482910)")
                .build()

            val action = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Digitar código (6 dígitos)",
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .build()

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("⚡ Pareamento ADB Independente")
                .setContentText("Abra 'Parear dispositivo com código' e digite os 6 dígitos aqui ↓")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "1. Em Opções do Desenvolvedor > Depuração por Wi-Fi\n" +
                            "2. Toque em 'Parear dispositivo com código de pareamento'\n" +
                            "3. Sem fechar a janela, puxe esta notificação e digite os 6 dígitos!"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(false)
                .addAction(action)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(NOTIFICATION_ID, notif) }
        }

        fun showResultNotification(context: Context, ok: Boolean, message: String) {
            ensureChannel(context)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    if (ok) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert
                )
                .setContentTitle(
                    if (ok) "✅ Modo Independente ATIVO!" else "⚠️ Pareamento ADB não concluído"
                )
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(NOTIFICATION_ID, notif) }
        }
    }
}
