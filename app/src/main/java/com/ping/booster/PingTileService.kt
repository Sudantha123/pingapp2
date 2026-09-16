package com.ping.booster

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/** Optional Quick Settings tile: add “Ping Booster” once from Android's edit panel. */
class PingTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("PingPrefs", MODE_PRIVATE)
        if (PingService.isServiceEnabled(prefs)) {
            stopService(Intent(this, PingService::class.java))
        } else {
            ContextCompat.startForegroundService(this, Intent(this, PingService::class.java))
        }
        refresh()
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (PingService.isServiceEnabled(getSharedPreferences("PingPrefs", MODE_PRIVATE))) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Ping Booster"
            icon = Icon.createWithResource(this@PingTileService, R.drawable.ic_ping_booster)
            updateTile()
        }
    }
}
