package com.ping.booster

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class PingService : Service() {
    companion object {
        const val ACTION_STOP = "com.ping.booster.STOP"
        private const val PREFS = "PingPrefs"
        private const val RUNNING = "running"
        private const val CHANNEL_ID = "PingBooster"
        private const val NOTIFICATION_ID = 1

        fun isServiceEnabled(prefs: android.content.SharedPreferences) = prefs.getBoolean(RUNNING, false)
        var isRunning = false
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var ping: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ping Booster", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, PingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ping Booster")
            .setContentText("Active")
            .setSmallIcon(R.drawable.ic_ping_booster)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (!isRunning) {
            isRunning = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RUNNING, true).apply()
            startPingLoop()
        }
        return START_NOT_STICKY
    }

    private fun startPingLoop() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val target = prefs.getString("url", "https://oneapp.hutch.lk") ?: return
        val delay = prefs.getInt("delay", 15).coerceAtLeast(5) * 1000L
        thread = HandlerThread("PingWorker", Process.THREAD_PRIORITY_LOWEST).also { it.start() }
        handler = Handler(thread!!.looper)
        ping = object : Runnable {
            override fun run() {
                if (!isRunning) return
                pingOnce(target)
                if (isRunning) handler?.postDelayed(this, delay)
            }
        }
        handler?.post(ping!!)
    }

    private fun pingOnce(target: String) {
        var socket: SSLSocket? = null
        var output: OutputStream? = null
        var input: InputStream? = null
        try {
            val url = URL(target)
            val port = if (url.port > 0) url.port else 443
            socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(url.host, port) as SSLSocket
            socket.soTimeout = 4000
            socket.startHandshake()
            output = socket.outputStream
            output.write("HEAD ${if (url.path.isEmpty()) "/" else url.path} HTTP/1.1\r\nHost: ${url.host}\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            input = socket.inputStream
            input.read()
        } catch (_: Exception) {
            // Network failures are intentionally silent and do not restart the loop.
        } finally {
            try { input?.close() } catch (_: Exception) { }
            try { output?.close() } catch (_: Exception) { }
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        isRunning = false
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RUNNING, false).apply()
        ping?.let { handler?.removeCallbacks(it) }
        thread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
