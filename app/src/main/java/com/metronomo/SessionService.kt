package com.metronomo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Mantém a sessão viva com a tela apagada: serviço em primeiro plano, com
 * WakeLock da CPU e trava de Wi-Fi para evitar economia de energia na rede.
 */
class SessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "metronomo:session").apply {
            setReferenceCounted(false)
            acquire()   // sem limite de tempo: o ensaio pode passar de 4 h; é solto em onDestroy
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(wifiMode(), "metronomo:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // botão "Encerrar sessão" da notificação
            SessionController.leave(applicationContext)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun wifiMode(): Int =
        if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sessão do metrônomo", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification = build(this, "Sessão ativa. Toque para abrir.")

    companion object {
        private const val CHANNEL_ID = "metronomo_session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.metronomo.action.STOP_SESSION"

        private fun build(ctx: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                ctx,
                0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                ctx,
                1,
                Intent(ctx, SessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Sincrono")
                .setContentText(text)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar sessão", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** Atualiza o texto da notificação (ex.: "Reconectando ao anfitrião…"). Sem efeito se o serviço não está rodando. */
        fun updateText(ctx: Context, text: String) {
            runCatching {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, build(ctx, text))
            }
        }
    }
}
