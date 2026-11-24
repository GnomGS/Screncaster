package com.screencast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.pedro.common.ConnectChecker
import com.pedro.rtspserver.RtspServerDisplay
import java.net.Inet4Address
import java.net.NetworkInterface

class StreamService : Service(), ConnectChecker {

    private var server: RtspServerDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (server?.isStreaming == true) return
        startForegroundCompat()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val width = intent.getIntExtra(EXTRA_W, 720)
        val height = intent.getIntExtra(EXTRA_H, 1280)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000)

        if (data == null) {
            publish(false, "Нет разрешения на захват экрана")
            stopAll()
            return
        }

        val dpi = resources.displayMetrics.densityDpi
        val s = RtspServerDisplay(applicationContext, true, this, PORT)
        s.setIntentResult(resultCode, data)
        val ok = s.prepareVideo(width, height, fps, bitrate, 0, dpi)
        if (!ok) {
            publish(false, "Не удалось подготовить видео")
            stopAll()
            return
        }
        s.startStream()
        server = s

        val ip = localIp()
        val url = if (ip != null) "rtsp://$ip:$PORT/" else "Wi-Fi не найден"
        publish(true, "Идёт трансляция", url)
    }

    private fun stopAll() {
        server?.let {
            if (it.isStreaming) it.stopStream()
        }
        server = null
        publish(false, "Остановлено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Трансляция экрана", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("ScreenCast")
            .setContentText("Экран транслируется в Streamlabs")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Стоп", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun publish(running: Boolean, status: String, url: String = "") {
        Companion.running = running
        Companion.status = status
        Companion.url = url
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    // --- ConnectChecker (клиенты Streamlabs подключаются к серверу) ---
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { publish(true, "Streamlabs подключён", url) }
    override fun onConnectionFailed(reason: String) { publish(true, "Ошибка: $reason", url) }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() { publish(true, "Ждём подключения Streamlabs", url) }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}

    override fun onDestroy() {
        server?.let { if (it.isStreaming) it.stopStream() }
        server = null
        super.onDestroy()
    }

    companion object {
        const val PORT = 8554
        const val CHANNEL = "stream"
        const val ACTION_START = "com.screencast.START"
        const val ACTION_STOP = "com.screencast.STOP"
        const val ACTION_STATE = "com.screencast.STATE"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "br"

        @Volatile var running = false
        @Volatile var status = "Остановлено"
        @Volatile var url = ""
    }
}
