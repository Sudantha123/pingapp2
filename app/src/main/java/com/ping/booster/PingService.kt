package com.ping.booster

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
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class PingService : Service() {

    companion object {
        /** Control panel / notification එකෙන් වහාම නැවැත්වීම සඳහා */
        const val ACTION_STOP = "com.ping.booster.action.STOP"
        const val PREFS_NAME = "PingPrefs"

        private const val CHANNEL_ID = "PingServiceChannel"
        private const val NOTIFICATION_ID = 1

        /** සජීවී තත්ත්වය - process එක මැරුණොත් ස්වයංක්‍රීයව false වේ */
        @Volatile
        var isRunning = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var pingRunnable: Runnable? = null

    /** නැවැත්වූ වහාම අතරමැද සම්බන්ධතාවය කපන්න */
    @Volatile
    private var activeSocket: Socket? = null

    private var delayMillis = 15_000L

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            serviceChannel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // වහාම නැවැත්වීම - ඉතිරි සියල්ල onDestroy() තුළ මුදා හරිනවා
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
        val seconds = prefs.getInt("delay", 15).coerceAtLeast(1)
        delayMillis = seconds * 1000L
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingBooster::Ping")
                .apply { setReferenceCounted(false) }
        }
        // STOP කරන තුරු පමණක් තබා ගැනීම - onDestroy() තුළ 100% ක් release වේ.
        // Process එක මැරුණොත් system එක විසින්ම මෙය මුදා හරිනවා.
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun startOptimizedPingLoop() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val targetUrlStr = prefs.getString("url", "https://oneapp.hutch.lk") ?: "https://oneapp.hutch.lk"

        // අඩුම CPU ප්‍රමුඛතාවය
        handlerThread = HandlerThread("PingWorker", Process.THREAD_PRIORITY_LOWEST).apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        pingRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                acquireWakeLock()
                pingOnce(targetUrlStr)
                if (isRunning) backgroundHandler?.postDelayed(this, delayMillis)
            }
        }

        backgroundHandler?.post(pingRunnable!!)
    }

    private fun pingOnce(targetUrlStr: String) {
        var socket: SSLSocket? = null
        var outStream: OutputStream? = null
        var inStream: InputStream? = null

        try {
            val urlObj = URL(targetUrlStr)
            val host = urlObj.host
            val path = if (urlObj.path.isEmpty()) "/" else urlObj.path
            val port = if (urlObj.port > 0) urlObj.port else 443

            // Termux (C/C++) Technique: කෙලින්ම Raw SSL Socket එකක් සෑදීම
            val factory = SSLSocketFactory.getDefault()
            socket = factory.createSocket(host, port) as SSLSocket
            activeSocket = socket
            socket.soTimeout = 5000
            socket.startHandshake()

            // Raw HTTP Request එක කෙලින්ම Bytes විදිහට යැවීම
            val request = "HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: Termux/1.0\r\n\r\n"
            outStream = socket.outputStream
            outStream.write(request.toByteArray())
            outStream.flush()

            // Memory පිරෙන්නේ නැති වෙන්න Response එකේ 1 Byte එකක් පමණක් කියවීම
            inStream = socket.inputStream
            inStream.read()
        } catch (e: Exception) {
            // Network / Stop කිරීමේදී එන දෝෂ නිශ්ශබ්දව අත්හැරීම
        } finally {
            activeSocket = null
            try { inStream?.close() } catch (e: Exception) {}
            try { outStream?.close() } catch (e: Exception) {}
            try { socket?.close() } catch (e: Exception) {}
        }
    }

    /** Control panel tile එක වහාම නිවැරදි තත්ත්වයට පත් කිරීම */
    private fun refreshTile() {
        try {
            TileService.requestListeningState(
                applicationContext,
                ComponentName(applicationContext, PingTileService::class.java)
            )
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        // ---- STOP වූ වහාම RAM / CPU සම්පූර්ණයෙන් මුදා හැරීම ----
        isRunning = false

        // 1. ඉතිරි ping callbacks සියල්ල ඉවත් කිරීම
        backgroundHandler?.removeCallbacksAndMessages(null)
        backgroundHandler = null

        // 2. Background thread එක සම්පූර්ණයෙන් නැවැත්වීම
        handlerThread?.quitSafely()
        handlerThread = null

        // 3. දැනට විවෘත socket එක වහාම කැපීම (තත්පර 5ක් ඉන්නේ නෑ)
        try { activeSocket?.close() } catch (e: Exception) {}
        activeSocket = null

        // 4. WakeLock එක මුදා හැරීම - CPU නිදහස්
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        pingRunnable = null

        // 5. Notification එක ඉවත් කිරීම
        stopForeground(STOP_FOREGROUND_REMOVE)

        // 6. Tile එක OFF තත්ත්වයට පත් කිරීම
        refreshTile()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
