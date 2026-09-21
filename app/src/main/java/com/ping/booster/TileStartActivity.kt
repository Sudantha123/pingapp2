package com.sudantha.pingbooster

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

class TileStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ContextCompat.startForegroundService(this, Intent(this, PingService::class.java))
        } catch (_: Exception) {
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
