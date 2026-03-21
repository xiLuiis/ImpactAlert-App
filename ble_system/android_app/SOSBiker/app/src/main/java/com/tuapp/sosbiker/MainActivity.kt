package com.tuapp.sosbiker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var bleManager: BleManager

    private lateinit var btnTabHome: Button
    private lateinit var btnTabBle: Button
    private lateinit var homePage: LinearLayout
    private lateinit var blePage: ScrollView

    private lateinit var txtMcuStatus: TextView
    private lateinit var txtCountdown: TextView
    private lateinit var txtBleStatus: TextView
    private lateinit var txtAcc: TextView
    private lateinit var txtGyro: TextView
    private lateinit var txtAccMag: TextView
    private lateinit var txtGyroMag: TextView
    private lateinit var txtCrashState: TextView

    private lateinit var btnMain: Button
    private lateinit var btnConnectBle: Button
    private lateinit var btnEmergency: Button

    private var latestAccText: String = ""
    private var latestGyroText: String = ""

    private var accMag = 0.0
    private var gyroMag = 0.0
    private var emergencyActive = false
    private var collisionHits = 0
    private var countdownTimer: CountDownTimer? = null
    private var bleConnected = false
    private var bleSubscribed = false
    companion object {
        var emergencyActiveGlobal = false
        var collisionHitsGlobal = 0
        var cancelEmergencyFromNotification = false
    }

    private val emergencyChannelId = "emergency_channel"
    private val emergencyNotificationId = 1001
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            bleManager.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bleManager = BleManager(this, this)

        btnTabHome = findViewById(R.id.btnTabHome)
        btnTabBle = findViewById(R.id.btnTabBle)
        homePage = findViewById(R.id.homePage)
        blePage = findViewById(R.id.blePage)

        txtMcuStatus = findViewById(R.id.txtMcuStatus)
        txtCountdown = findViewById(R.id.txtCountdown)
        txtBleStatus = findViewById(R.id.txtBleStatus)
        txtAcc = findViewById(R.id.txtAcc)
        txtGyro = findViewById(R.id.txtGyro)
        txtAccMag = findViewById(R.id.txtAccMag)
        txtGyroMag = findViewById(R.id.txtGyroMag)
        txtCrashState = findViewById(R.id.txtCrashState)

        btnMain = findViewById(R.id.btnMain)
        btnConnectBle = findViewById(R.id.btnConnectBle)
        btnEmergency = findViewById(R.id.btnEmergency)

        showHome()

        btnTabHome.setOnClickListener { showHome() }
        btnTabBle.setOnClickListener { showBle() }

        btnConnectBle.setOnClickListener {
            requestBlePermissionsAndScan()
        }

        btnMain.setOnClickListener {
            if (emergencyActive) {
                cancelEmergency()
            }
        }

        btnEmergency.setOnClickListener {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:911")
            }
            startActivity(intent)
        }

        updateMainButton()
        createEmergencyChannel()
        requestNotificationPermissionIfNeeded()
    }

    private fun showHome() {
        homePage.visibility = View.VISIBLE
        blePage.visibility = View.GONE
    }

    private fun showBle() {
        homePage.visibility = View.GONE
        blePage.visibility = View.VISIBLE
    }
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }
    }
    private fun createEmergencyChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                emergencyChannelId,
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Crash emergency countdown"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    private fun requestBlePermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (needed.isNotEmpty()) {
                permissionLauncher.launch(needed.toTypedArray())
            } else {
                bleManager.startScan()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                bleManager.startScan()
            }
        }
    }

    override fun onBleStatus(text: String) {
        runOnUiThread {
            txtBleStatus.text = text

            bleConnected = text.contains("connected", ignoreCase = true) ||
                    text.contains("subscribed", ignoreCase = true)

            bleSubscribed = text.contains("subscribed", ignoreCase = true)

            txtMcuStatus.text = when {
                bleConnected -> "MCU Status: Connected"
                text.contains("scanning", ignoreCase = true) -> "MCU Status: Scanning..."
                else -> "MCU Status: Offline"
            }

            updateMainButton()
        }
    }

    override fun onStatusValue(text: String) {
        runOnUiThread {
            txtMcuStatus.text = "MCU Status: $text"

            if (text.contains("IMU_OK", ignoreCase = true) ||
                text.contains("CONNECTED", ignoreCase = true)
            ) {
                txtBleStatus.text = "BLE: connected"
                bleConnected = true
                updateMainButton()
            }
        }
    }

    override fun onAccValue(text: String) {
        latestAccText = text
        val triple = parseTriple(text) ?: return

        accMag = magnitude(triple.first, triple.second, triple.third)

        runOnUiThread {
            txtAcc.text = "ACC: $text"
            txtAccMag.text = "ACC MAG: %.2f".format(accMag)
            evaluateCrash()
        }
    }

    override fun onGyroValue(text: String) {
        latestGyroText = text
        val triple = parseTriple(text) ?: return

        gyroMag = magnitude(triple.first, triple.second, triple.third)

        runOnUiThread {
            txtGyro.text = "GYRO: $text"
            txtGyroMag.text = "GYRO MAG: %.2f".format(gyroMag)
            evaluateCrash()
        }
    }
    private fun showEmergencyNotification(secondsLeft: Int) {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, EmergencyActionReceiver::class.java).apply {
            action = "com.tuapp.sosbiker.ACTION_CANCEL_EMERGENCY"
        }

        val cancelPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, emergencyChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Crash detected")
            .setContentText("Calling in $secondsLeft... False alarm?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(emergencyNotificationId, notification)
        }
    }    private fun evaluateCrash() {
        if (emergencyActive) return

        val linearAcc = kotlin.math.abs(accMag - 1.0)
        val collisionNow = gyroMag > 100.0 && linearAcc > 0.5

        if (collisionNow) {
            collisionHits++
        } else {
            collisionHits = (collisionHits - 1).coerceAtLeast(0)
        }

        if (collisionHits >= 3) {
            triggerEmergency()
        } else {
            txtCrashState.text = "Crash state: NORMAL"
        }
    }

    private fun triggerEmergency() {
        emergencyActive = true
        emergencyActiveGlobal = true
        txtCrashState.text = "Crash state: EMERGENCY"
        updateMainButton()

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(8000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L).toInt()
                txtCountdown.text = "Calling in $sec..."
                showEmergencyNotification(sec)

                if (cancelEmergencyFromNotification) {
                    cancelEmergencyFromNotification = false
                    cancelEmergency()
                }
            }

            override fun onFinish() {
                txtCountdown.text = "Ready to call 911"
                NotificationManagerCompat.from(this@MainActivity).cancel(emergencyNotificationId)
            }
        }.start()
    }

    private fun cancelEmergency() {
        emergencyActive = false
        emergencyActiveGlobal = false
        collisionHits = 0
        collisionHitsGlobal = 0
        txtCountdown.text = ""
        txtCrashState.text = "Crash state: NORMAL"
        countdownTimer?.cancel()
        NotificationManagerCompat.from(this).cancel(emergencyNotificationId)
        updateMainButton()
    }

    private fun updateMainButton() {
        when {
            emergencyActive -> {
                btnMain.text = "CANCEL EMERGENCY"
                btnMain.isEnabled = true
                btnMain.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#D93D33")
                )
            }

            !bleConnected -> {
                btnMain.text = "BLE OFFLINE"
                btnMain.isEnabled = false
                btnMain.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#9E9E9E")
                )
            }

            else -> {
                btnMain.text = "SYSTEM NORMAL"
                btnMain.isEnabled = false
                btnMain.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#2ECC71")
                )
            }
        }
    }

    private fun parseTriple(text: String): Triple<Double, Double, Double>? {
        val parts = text.split(",")
        if (parts.size != 3) return null

        return try {
            Triple(
                parts[0].trim().toDouble(),
                parts[1].trim().toDouble(),
                parts[2].trim().toDouble()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun magnitude(x: Double, y: Double, z: Double): Double {
        return sqrt(x * x + y * y + z * z)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}