package com.sudantha.pingbooster

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var inputUrl: TextInputEditText
    private lateinit var inputDelay: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        inputUrl = findViewById(R.id.inputUrl)
        inputDelay = findViewById(R.id.inputDelay)
        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)

        val prefs = getSharedPreferences(PingService.PREFS_NAME, Context.MODE_PRIVATE)
        inputUrl.setText(prefs.getString("url", "https://oneapp.hutch.lk"))
        inputDelay.setText(prefs.getInt("delay", 15).toString())
        updateStatus()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }

        btnStart.setOnClickListener {
            val url = inputUrl.text.toString().trim()
            val delayStr = inputDelay.text.toString().trim()

            if (url.isEmpty() || delayStr.isEmpty()) {
                Toast.makeText(this, "Please enter URL and Delay", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val delaySeconds = delayStr.toIntOrNull()
            if (delaySeconds == null || delaySeconds < 1) {
                Toast.makeText(this, "Delay must be at least 1 second", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("url", url)
                .putInt("delay", delaySeconds)
                .apply()

            val serviceIntent = Intent(this, PingService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)

            updateStatus()
            Toast.makeText(this, "Started in Background", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            val serviceIntent = Intent(this, PingService::class.java)
            stopService(serviceIntent)
            updateStatus()
        }
    }

    private fun updateStatus() {
        if (PingService.isRunning) {
            statusText.text = "Status: Running"
            statusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            statusText.text = "Status: Stopped"
            statusText.setTextColor(android.graphics.Color.parseColor("#FF5252"))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
