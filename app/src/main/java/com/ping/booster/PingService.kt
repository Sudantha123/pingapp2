package com.sudantha.pingbooster

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class PingService : Service() {

    companion object {
        const val ACTION_STOP = "com.sudantha.pingbooster.action.STOP"
        const val PREFS_NAME = "PingPrefs"

        private const val CHANNEL_ID = "PingServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_URL = "https://oneapp.hutch.lk"
        private const val DEFAULT_DELAY_SECONDS = 15
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_BYTES = 16 * 1024

        @Volatile
        var isRunning = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var pingRunnable: Runnable? = null

    @Volatile
    private var activeSocket: SSLSocket? = null

    private var delayMillis = DEFAULT_DELAY_SECONDS * 1000L

    // Parse and allocate the request once when the service starts.
    private var targetHost = ""
    private var targetPort = 443
    private var requestBytes = ByteArray(0)
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault()

    // Reused for every response; no per-ping buffer allocation.
    private val responseBuffer = ByteArray(MAX_HEADER_BYTES)

    private var inputStream: BufferedInputStream? = null
    private var outputStream: OutputStream? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            serviceChannel.setShowBadge(false)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (!isRunning) {
            isRunning = true
            loadSettings()
            acquireWakeLock()
            startOptimizedPingLoop()
            refreshTile()
        }

        return START_STICKY
    }

    private fun buildNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(R.drawable.ic_tile)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seconds = prefs.getInt("delay", DEFAULT_DELAY_SECONDS).coerceAtLeast(1)
        delayMillis = seconds * 1000L

        val targetUrl = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        val url = URL(targetUrl)

        targetHost = url.host
        targetPort = if (url.port > 0) url.port else 443

        val path = buildString {
            append(if (url.path.isEmpty()) "/" else url.path)
            if (!url.query.isNullOrEmpty()) {
                append('?')
                append(url.query)
            }
        }

        val hostHeader = if (targetPort == 443) targetHost else "$targetHost:$targetPort"

        requestBytes = (
            "HEAD $path HTTP/1.1\r\n" +
                "Host: $hostHeader\r\n" +
                "Connection: keep-alive\r\n" +
                "User-Agent: PingBooster/3.0\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingBooster::Ping")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun startOptimizedPingLoop() {
        handlerThread = HandlerThread("PingWorker", Process.THREAD_PRIORITY_LOWEST).apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        pingRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                // WakeLock is intentionally retained for the whole service lifetime.
                acquireWakeLock()
                pingOnce()

                if (isRunning) {
                    backgroundHandler?.postDelayed(this, delayMillis)
                }
            }
        }

        backgroundHandler?.post(pingRunnable!!)
    }

    /**
     * Reuses the same TLS/TCP connection whenever the peer keeps HTTP/1.1 alive.
     * If the peer closes it or an I/O error occurs, the next ping reconnects.
     */
    private fun pingOnce() {
        try {
            ensureConnection()

            val out = outputStream ?: return
            val input = inputStream ?: return

            out.write(requestBytes)
            out.flush()

            if (!readResponseHeaders(input)) {
                closeConnection()
            }
        } catch (_: Exception) {
            closeConnection()
        }
    }

    private fun ensureConnection() {
        val current = activeSocket
        if (current != null &&
            !current.isClosed &&
            current.isConnected &&
            !current.isInputShutdown &&
            !current.isOutputShutdown
        ) {
            return
        }

        closeConnection()

        val socket = sslSocketFactory.createSocket(targetHost, targetPort) as SSLSocket
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.keepAlive = true
        socket.tcpNoDelay = true
        socket.startHandshake()

        activeSocket = socket
        outputStream = socket.outputStream
        inputStream = BufferedInputStream(socket.inputStream, 1024)
    }

    /**
     * Reads exactly through the HTTP header terminator CRLFCRLF.
     * HEAD responses have no message body, so the connection is ready for
     * the next request immediately after the header block.
     */
    private fun readResponseHeaders(input: InputStream): Boolean {
        var used = 0
        var state = 0

        while (used < responseBuffer.size) {
            val value = input.read()
            if (value < 0) return false

            responseBuffer[used++] = value.toByte()

            when (state) {
                0 -> if (value == 13) state = 1
                1 -> state = when (value) {
                    10 -> 2
                    13 -> 1
                    else -> 0
                }
                2 -> state = if (value == 13) 3 else 0
                3 -> {
                    if (value == 10) {
                        if (containsConnectionClose(used)) {
                            closeConnection()
                        }
                        return true
                    }
                    state = if (value == 13) 1 else 0
                }
            }
        }

        return false
    }

    private fun containsConnectionClose(length: Int): Boolean {
        val needle = "connection: close"
        if (length < needle.length) return false

        for (start in 0..length - needle.length) {
            var match = true
            for (i in needle.indices) {
                val b = responseBuffer[start + i].toInt() and 0xFF
                val c = needle[i].code
                val lower = if (b in 'A'.code..'Z'.code) b + 32 else b
                if (lower != c) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun closeConnection() {
        val socket = activeSocket
        activeSocket = null

        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}

        inputStream = null
        outputStream = null
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(
                applicationContext,
                ComponentName(applicationContext, PingTileService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isRunning = false

        backgroundHandler?.removeCallbacksAndMessages(null)
        backgroundHandler = null

        handlerThread?.quitSafely()
        handlerThread = null

        closeConnection()

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        pingRunnable = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        refreshTile()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
