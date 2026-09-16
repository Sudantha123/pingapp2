package com.ping.booster

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * තිරයේ නොපෙනෙන (NoDisplay) තාවකාලික activity එක.
 * Android 14 හි Quick Settings tile එකෙන් foreground service ආරම්භ කළ නොහැකි
 * අවස්ථාවේදී පමණක් භාවිතා වේ. වැඩය කරා වහාම වැසී යයි.
 */
class TileStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ContextCompat.startForegroundService(this, Intent(this, PingService::class.java))
        } catch (e: Exception) {
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
