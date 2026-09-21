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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
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

    // Parsed once at service start. The old implementation parsed the URL on every ping.
    private var targetHost = ""
    private var targetPort = 443
    private var requestBytes = ByteArray(0)
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault()

    // Reused for every HTTP response. No per-ping buffer allocation.
    private val responseBuffer = ByteArray(4096)

    private var inputStream: InputStream? = null
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

        val hostHeader = if (targetPort == 443) {
            targetHost
        } else {
            "$targetHost:$targetPort"
        }

        requestBytes = "HEAD $path HTTP/1.1\r\n" +
                "Host: $hostHeader\r\n" +
                "Connection: keep-alive\r\n" +
                "User-Agent: PingBooster/3.0\r\n" +
                "\r\n"
            .toByteArray(Charsets.US_ASCII)
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

                // The WakeLock is intentionally retained for the entire service lifetime.
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
     * Sends a lightweight HTTP HEAD request.
     *
     * The TLS/TCP connection is reused whenever the server keeps HTTP/1.1 alive.
     * If the peer closes it, the next attempt transparently creates a new socket.
     */
    private fun pingOnce() {
        try {
            ensureConnection()

            val socket = activeSocket ?: return
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
        if (current != null && !current.isClosed && current.isConnected &&
            !current.isInputShutdown && !current.isOutputShutdown
        ) {
            return
        }

        closeConnection()

        val socket = sslSocketFactory.createSocket(targetHost, targetPort) as SSLSocket
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.keepAlive = true
        socket.startHandshake()

        activeSocket = socket
        outputStream = socket.outputStream
        inputStream = socket.inputStream
    }

    /**
     * Consumes the complete HTTP header block so that the next request starts
     * on a clean response boundary. HEAD responses have no response body.
     *
     * Returns false when the peer closes the connection or the header is invalid.
     */
    private fun readResponseHeaders(input: InputStream): Boolean {
        var used = 0
        var lastByte = -1
        var previousByte = -1

        while (used < MAX_HEADER_BYTES) {
            val count = input.read(responseBuffer, 0, responseBuffer.size)
            if (count <= 0) return false

            for (i in 0 until count) {
                val current = responseBuffer[i].toInt() and 0xFF

                if (previousByte == '\r'.code && lastByte == '\n'.code && current == '\r'.code) {
                    // This branch only handles the final CRLFCRLF sequence after the
                    // next byte is read. Keep the state machine below simpler instead.
                }

                // Detect CRLFCRLF using the last four bytes without storing a
                // growing response. We only need to retain the previous 3 bytes.
                if (previousByte == '\r'.code && lastByte == '\n'.code && current == '\r'.code) {
                    val nextIndex = i + 1
                    if (nextIndex < count && (responseBuffer[nextIndex].toInt() and 0xFF) == '\n'.code) {
                        return !responseHasConnectionClose(responseBuffer, used + i + 1)
                    }
                }

                previousByte = lastByte
                lastByte = current
                used++
                if (used >= MAX_HEADER_BYTES) return false
            }
        }

        return false
    }

    /**
     * Detects an explicit Connection: close header without allocating a String
     * for the whole response. If present, the connection is closed after this ping.
     */
    private fun responseHasConnectionClose(buffer: ByteArray, endExclusive: Int): Boolean {
        var start = 0
        while (start + 18 <= endExclusive) {
            if ((buffer[start].toInt() and 0xFF) == 'C'.code ||
                (buffer[start].toInt() and 0xFF) == 'c'.code
            ) {
                val remaining = endExclusive - start
                if (remaining >= 19 &&
                    asciiEqualsIgnoreCase(buffer, start, "connection: close")
                ) {
                    closeConnection()
                    return true
                }
            }
            start++
        }
        return false
    }

    private fun asciiEqualsIgnoreCase(buffer: ByteArray, offset: Int, value: String): Boolean {
        if (offset + value.length > buffer.size) return false
        for (i in value.indices) {
            val b = buffer[offset + i].toInt() and 0xFF
            val c = value[i].code
            if (b != c && b != (c xor 32)) return false
        }
        return true
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
