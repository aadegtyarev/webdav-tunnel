package ru.adsrv.webdavtunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import mobile.Mobile
import kotlin.concurrent.thread

/**
 * Foreground service that runs the gomobile WebDAV SOCKS5 tunnel.
 * The proxy keeps running while the service is alive (app may be backgrounded).
 */
class TunnelService : Service() {

    companion object {
        const val ACTION_START = "ru.adsrv.webdavtunnel.START"
        const val ACTION_STOP = "ru.adsrv.webdavtunnel.STOP"
        const val ACTION_STATUS = "ru.adsrv.webdavtunnel.STATUS"

        const val EXTRA_URL = "url"
        const val EXTRA_LOGIN = "login"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_STATE = "state"
        const val EXTRA_MSG = "msg"

        const val STATE_CONNECTING = "connecting"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"
        const val STATE_ERROR = "error"

        private const val CHANNEL_ID = "tunnel"
        private const val NOTIF_ID = 1

        // Cached so the Activity can sync its UI on resume.
        @Volatile var lastState: String = STATE_STOPPED
        @Volatile var lastMsg: String = "Stopped"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> startTunnel(
                intent.getStringExtra(EXTRA_URL).orEmpty(),
                intent.getStringExtra(EXTRA_LOGIN).orEmpty(),
                intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                intent.getStringExtra(EXTRA_LISTEN).orEmpty().ifBlank { "127.0.0.1:1080" }
            )
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(url: String, login: String, password: String, listen: String) {
        createChannel()
        startForegroundCompat(buildNotification("Connecting…"))
        broadcast(STATE_CONNECTING, "Connecting…")

        thread(name = "tunnel-start") {
            try {
                applyTuning()
                // Blocking: verifies WebDAV connectivity (up to ~15s) before returning.
                Mobile.start(url, login, password, listen, "", "")
                val msg = "Tunnel active · SOCKS5 $listen"
                broadcast(STATE_RUNNING, msg)
                updateNotification(msg)
            } catch (e: Exception) {
                broadcast(STATE_ERROR, e.message ?: "connection error")
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun stopTunnel() {
        try { Mobile.stop() } catch (_: Exception) {}
        broadcast(STATE_STOPPED, "Stopped")
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * Applies the transport tuning from persisted settings (level 3). Defaults
     * mirror the fast throttling profile for 1-2 clients on one Yandex account.
     * Persisted in SharedPreferences, so the values survive app/phone restarts.
     */
    private fun applyTuning() {
        val s = Settings.Tune
        Mobile.setChunkSize(Settings.tuneInt(this, s.CHUNK, s.DEF_CHUNK).toLong())
        Mobile.setCoalesceMs(Settings.tuneInt(this, s.COALESCE, s.DEF_COALESCE).toLong())
        Mobile.setPollMinMs(Settings.tuneInt(this, s.POLL_MIN, s.DEF_POLL_MIN).toLong())
        Mobile.setPollMaxMs(Settings.tuneInt(this, s.POLL_MAX, s.DEF_POLL_MAX).toLong())
        Mobile.setConcurrentPuts(Settings.tuneInt(this, s.PUTS, s.DEF_PUTS).toLong())
        Mobile.setReadAheadMin(Settings.tuneInt(this, s.READ_MIN, s.DEF_READ_MIN).toLong())
        Mobile.setReadAheadMax(Settings.tuneInt(this, s.READ_MAX, s.DEF_READ_MAX).toLong())
        Mobile.setDialTimeoutSec(Settings.tuneInt(this, s.DIAL, s.DEF_DIAL).toLong())
        Mobile.setIdleTimeoutSec(Settings.tuneInt(this, s.IDLE, s.DEF_IDLE).toLong())
    }

    // ── notifications / broadcast ────────────────────────────────────────────────

    private fun broadcast(state: String, msg: String) {
        lastState = state
        lastMsg = msg
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MSG, msg)
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Tunnel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebDAV Tunnel")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun startForegroundCompat(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }
}
