package com.sudantha.pingbooster

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class PingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render(if (PingService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
    }

    override fun onClick() {
        super.onClick()
        if (PingService.isRunning) {
            stopPing()
            render(Tile.STATE_INACTIVE)
        } else {
            startPing()
            render(Tile.STATE_ACTIVE)
        }
    }

    private fun startPing() {
        val intent = Intent(this, PingService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {
            startViaTrampoline()
        }
    }

    private fun stopPing() {
        try {
            stopService(Intent(this, PingService::class.java))
        } catch (_: Exception) {
            try {
                startService(
                    Intent(this, PingService::class.java).setAction(PingService.ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun startViaTrampoline() {
        val intent = Intent(this, TileStartActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            try {
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun render(state: Int) {
        val tile = qsTile ?: return
        try {
            tile.state = state
            tile.label = getString(R.string.app_name)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
            tile.updateTile()
        } catch (_: Exception) {
        }
    }
}
