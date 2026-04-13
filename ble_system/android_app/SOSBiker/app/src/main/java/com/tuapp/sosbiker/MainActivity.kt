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
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt
import android.widget.CheckBox
import android.widget.CompoundButton
import java.net.URLEncoder

class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var bleManager: BleManager

    private lateinit var btnTabHome: Button
    private lateinit var btnTabBle: Button
    private lateinit var homePage: LinearLayout
    private lateinit var blePage: ScrollView
    private lateinit var chkDebugMode: CheckBox

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
    private lateinit var btnTestAlert: Button
    private lateinit var btnEmergencyContacts: Button

    private var latestAccText: String = ""
    private var latestGyroText: String = ""

    private var accMag = 0.0
    private var gyroMag = 0.0
    private var lastAccMag = 0.0
    private var lastGyroMag = 0.0

    private var emergencyActive = false
    private var collisionHits = 0
    private var countdownTimer: CountDownTimer? = null
    private var bleConnected = false
    private var bleSubscribed = false
    private var debugModeEnabled = false

    private enum class CrashState {
        NORMAL,
        EVENT_DETECTED,
        WAITING_FOR_STILLNESS,
        CONFIRMED
    }

    private var crashStateMachine = CrashState.NORMAL
    private var stateStartMs = 0L
    private var stillnessStartMs = 0L

    // ===== Thresholds =====
    private val impactThreshold = 6.5
    private val deltaAccThreshold = 2.5
    private val deltaGyroThreshold = 220.0
    private val minAccForRotationEvent = 3.5

    private val stillAccMin = 0.90
    private val stillAccMax = 1.10
    private val stillGyroThreshold = 10.0
    private val stillnessRequiredMs = 1800L

    private val eventWindowMs = 1000L
    private val postEventWindowMs = 5000L

    private var lastEmergencyMs = 0L
    private val emergencyCooldownMs = 6000L

    private var peakAccMag = 0.0
    private var peakGyroMag = 0.0

    companion object {
        var emergencyActiveGlobal = false
        var collisionHitsGlobal = 0
        var cancelEmergencyFromNotification = false
    }

    private val emergencyChannelId = "emergency_channel_v2"
    private val crashDetectedNotificationId = 1001
    private val countdownNotificationId = 1002
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startBleAutoConnect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bleManager = BleManager(this, this)

        btnTabHome = findViewById(R.id.btnTabHome)
        btnTabBle = findViewById(R.id.btnTabBle)
        homePage = findViewById(R.id.homePage)
        blePage = findViewById(R.id.blePage)
        chkDebugMode = findViewById(R.id.chkDebugMode)

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
        btnTestAlert = findViewById(R.id.btnTestAlert)
        btnEmergencyContacts = findViewById(R.id.btnEmergencyContacts)

        showHome()

        btnTabHome.setOnClickListener { showHome() }

        btnTabBle.setOnClickListener {
            if (debugModeEnabled) {
                showBle()
            } else {
                Toast.makeText(this, "Enable DEBUG mode first", Toast.LENGTH_SHORT).show()
            }
        }

        chkDebugMode.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            debugModeEnabled = isChecked
            updateDebugUIVisibility()

            if (!debugModeEnabled && blePage.visibility == View.VISIBLE) {
                showHome()
            }
        }

        btnConnectBle.setOnClickListener {
            requestBlePermissionsAndAutoConnect()
        }

        btnTestAlert.setOnClickListener {
            triggerEmergencyTest()
        }

        btnEmergencyContacts.setOnClickListener {
            openEmergencyContacts()
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
        updateTestAlertButton()
        updateDebugUIVisibility()
        createEmergencyChannel()
        requestNotificationPermissionIfNeeded()

        // AUTO CONNECT AL ABRIR
        requestBlePermissionsAndAutoConnect()
    }

    private fun showHome() {
        homePage.visibility = View.VISIBLE
        blePage.visibility = View.GONE
    }

    private fun showBle() {
        homePage.visibility = View.GONE
        blePage.visibility = View.VISIBLE
    }

    private fun updateDebugUIVisibility() {
        btnTabBle.visibility = if (debugModeEnabled) View.VISIBLE else View.GONE
        btnTestAlert.visibility = if (debugModeEnabled) View.VISIBLE else View.GONE
    }
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
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
                description = "Crash and emergency countdown alerts"
                enableVibration(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun requestBlePermissionsAndAutoConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()

            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (needed.isNotEmpty()) {
                permissionLauncher.launch(needed.toTypedArray())
            } else {
                startBleAutoConnect()
            }
        } else {
            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                startBleAutoConnect()
            }
        }
    }

    private fun startBleAutoConnect() {
        bleManager.startAutoConnect()
    }

    override fun onBleStatus(text: String) {
        runOnUiThread {
            txtBleStatus.text = text

            bleConnected = text.contains("connected", ignoreCase = true) ||
                    text.contains("subscribed", ignoreCase = true)

            bleSubscribed = text.contains("subscribed", ignoreCase = true)

            txtMcuStatus.text = when {
                text.contains("scanning", ignoreCase = true) -> "MCU Status: Scanning..."
                text.contains("device found, connecting", ignoreCase = true) ||
                        text.contains("connecting", ignoreCase = true) -> "MCU Status: Connecting..."
                bleConnected -> "MCU Status: Connected"
                else -> "MCU Status: Offline"
            }

            if (!bleConnected) {
                bleSubscribed = false
                collisionHits = 0

                if (!emergencyActive) {
                    resetCrashState("Crash state: BLE OFFLINE")
                }
            }

            updateMainButton()
            updateTestAlertButton()
        }
    }

    override fun onStatusValue(text: String) {
        runOnUiThread {
            // Aquí mostramos el estado que reporta el micro SIN pisar el estado general de MCU/BLE
            txtBleStatus.text = "BLE: MCU -> $text"

            if (
                text.contains("IMU_OK", ignoreCase = true) ||
                text.contains("CONNECTED", ignoreCase = true)
            ) {
                bleConnected = true
                updateMainButton()
                updateTestAlertButton()
            }
        }
    }

    override fun onAccValue(text: String) {
        latestAccText = text
        val triple = parseTriple(text) ?: return

        lastAccMag = accMag
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

        lastGyroMag = gyroMag
        gyroMag = magnitude(triple.first, triple.second, triple.third)

        runOnUiThread {
            txtGyro.text = "GYRO: $text"
            txtGyroMag.text = "GYRO MAG: %.2f".format(gyroMag)
            evaluateCrash()
        }
    }

    private fun showCrashDetectedNotification() {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, EmergencyActionReceiver::class.java).apply {
            action = "com.tuapp.sosbiker.ACTION_CANCEL_EMERGENCY"
        }

        val cancelPendingIntent = PendingIntent.getBroadcast(
            this,
            11,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, emergencyChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Crash detected")
            .setContentText("Possible accident detected. Emergency sequence started.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
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

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this)
                .notify(crashDetectedNotificationId, notification)
        }
    }

    private fun showEmergencyNotification(secondsLeft: Int) {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            20,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, EmergencyActionReceiver::class.java).apply {
            action = "com.tuapp.sosbiker.ACTION_CANCEL_EMERGENCY"
        }

        val cancelPendingIntent = PendingIntent.getBroadcast(
            this,
            21,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, emergencyChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Emergency countdown")
            .setContentText("Calling in $secondsLeft... False alarm?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
            .build()

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this)
                .notify(countdownNotificationId, notification)
        }
    }

    private fun debugCrash(message: String) {
        Log.d("SOS_BIKER_CRASH", message)
    }

    private fun setCrashState(newState: CrashState, reason: String) {
        if (crashStateMachine != newState) {
            debugCrash("${crashStateMachine.name} -> ${newState.name} | $reason")
            crashStateMachine = newState
            stateStartMs = System.currentTimeMillis()
        }
    }

    private fun resetCrashState(uiText: String = "Crash state: NORMAL") {
        if (crashStateMachine != CrashState.NORMAL) {
            debugCrash("${crashStateMachine.name} -> NORMAL | reset")
        }
        crashStateMachine = CrashState.NORMAL
        stateStartMs = 0L
        stillnessStartMs = 0L
        peakAccMag = 0.0
        peakGyroMag = 0.0
        txtCrashState.text = uiText
    }

    private fun evaluateCrash() {
        if (emergencyActive) return
        if (!bleConnected || !bleSubscribed) return

        val now = System.currentTimeMillis()

        if (now - lastEmergencyMs < emergencyCooldownMs) {
            return
        }

        val deltaAccMag = abs(accMag - lastAccMag)
        val deltaGyroMag = abs(gyroMag - lastGyroMag)

        peakAccMag = maxOf(peakAccMag, accMag)
        peakGyroMag = maxOf(peakGyroMag, gyroMag)

        val isStillNow = accMag in stillAccMin..stillAccMax && gyroMag <= stillGyroThreshold

        if (isStillNow) {
            if (stillnessStartMs == 0L) {
                stillnessStartMs = now
            }
        } else {
            stillnessStartMs = 0L
        }

        val stillnessDuration = if (stillnessStartMs == 0L) 0L else now - stillnessStartMs
        val sustainedStillness = stillnessDuration >= stillnessRequiredMs

        val debugText = "State: ${crashStateMachine.name}\n" +
                "acc=%.2f dAcc=%.2f\ngyro=%.2f dGyro=%.2f\npeakAcc=%.2f peakGyro=%.2f\nstillMs=%d"
                    .format(accMag, deltaAccMag, gyroMag, deltaGyroMag, peakAccMag, peakGyroMag, stillnessDuration)

        txtCrashState.text = debugText
        debugCrash(
            "state=${crashStateMachine.name} acc=%.2f dAcc=%.2f gyro=%.2f dGyro=%.2f peakAcc=%.2f peakGyro=%.2f stillMs=%d"
                .format(accMag, deltaAccMag, gyroMag, deltaGyroMag, peakAccMag, peakGyroMag, stillnessDuration)
        )

        when (crashStateMachine) {
            CrashState.NORMAL -> {
                val strongImpact = accMag >= impactThreshold
                val rotationWithImpact = deltaGyroMag >= deltaGyroThreshold &&
                        (accMag >= minAccForRotationEvent || deltaAccMag >= deltaAccThreshold)

                if (strongImpact || rotationWithImpact) {
                    setCrashState(
                        CrashState.EVENT_DETECTED,
                        "event detected | acc=%.2f dAcc=%.2f gyro=%.2f dGyro=%.2f"
                            .format(accMag, deltaAccMag, gyroMag, deltaGyroMag)
                    )
                    return
                }
            }

            CrashState.EVENT_DETECTED -> {
                val elapsed = now - stateStartMs

                if (elapsed > eventWindowMs) {
                    setCrashState(
                        CrashState.WAITING_FOR_STILLNESS,
                        "waiting for stillness | peakAcc=%.2f peakGyro=%.2f"
                            .format(peakAccMag, peakGyroMag)
                    )
                    return
                }
            }

            CrashState.WAITING_FOR_STILLNESS -> {
                val elapsed = now - stateStartMs

                if (sustainedStillness) {
                    setCrashState(
                        CrashState.CONFIRMED,
                        "confirmed by sustained stillness | peakAcc=%.2f peakGyro=%.2f stillMs=%d"
                            .format(peakAccMag, peakGyroMag, stillnessDuration)
                    )

                    lastEmergencyMs = now
                    collisionHits++
                    collisionHitsGlobal = collisionHits
                    triggerEmergency()
                    return
                }

                if (elapsed > postEventWindowMs) {
                    resetCrashState("Crash state: NORMAL")
                }
            }

            CrashState.CONFIRMED -> {
                txtCrashState.text = "Crash state: EMERGENCY"
            }
        }
    }

    private fun triggerEmergency() {
        emergencyActive = true
        emergencyActiveGlobal = true
        txtCrashState.text = "Crash state: EMERGENCY"
        txtMcuStatus.text = "MCU Status: Emergency Active"
        updateMainButton()
        updateTestAlertButton()

        val alertSent = bleManager.sendCommand("ALERT_ON")
        debugCrash("ALERT_ON sent to hardware = $alertSent")

        if (!alertSent) {
            Toast.makeText(
                this,
                "No se pudo enviar ALERT_ON al micro",
                Toast.LENGTH_SHORT
            ).show()
        }

        showCrashDetectedNotification()

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
                txtCountdown.text = "Opening WhatsApp..."
                NotificationManagerCompat.from(this@MainActivity)
                    .cancel(countdownNotificationId)

                sendWhatsappAlertToAllContacts()
            }
        }.start()
    }

    private fun triggerEmergencyTest() {
        showBle()

        if (!bleConnected || !bleSubscribed) {
            Toast.makeText(
                this,
                "Test alert unavailable: BLE not connected/subscribed yet",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (emergencyActive) {
            Toast.makeText(this, "Emergency already active", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "DEBUG: manual emergency trigger", Toast.LENGTH_SHORT).show()

        // Para pruebas manuales, no queremos que el cooldown estorbe
        lastEmergencyMs = 0L

        collisionHits++
        collisionHitsGlobal = collisionHits

        triggerEmergency()
    }

    private fun openEmergencyContacts() {
        val intent = Intent(this, EmergencyContactsActivity::class.java)
        startActivity(intent)
    }

    private fun getEmergencyPhoneNumbers(): List<String> {
        return EmergencyContactsStore.getEnabledPhoneNumbers(this)
    }

    private fun debugShowRegisteredContacts() {
        val numbers = getEmergencyPhoneNumbers()

        if (numbers.isEmpty()) {
            Toast.makeText(this, "No emergency contacts registered", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Contacts: ${numbers.joinToString()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendWhatsappAlertToAllContacts() {
        val numbers = EmergencyContactsStore.getEnabledPhoneNumbers(this)

        if (numbers.isEmpty()) {
            Toast.makeText(this, "No emergency contacts registered", Toast.LENGTH_SHORT).show()
            return
        }

        val message = buildEmergencyWhatsappMessage()

        // Por ahora abrimos solo el primer contacto (limitación de WhatsApp)
        openWhatsappWithMessage(numbers.first(), message)
    }

    private fun buildEmergencyWhatsappMessage(): String {
        return """
        🚨 SOS Biker Alert
        
        Se detectó una posible caída o accidente.
        Necesito ayuda.
        
        Estado: emergencia confirmada
    """.trimIndent()
    }

    private fun openWhatsappWithMessage(phoneNumber: String, message: String) {
        try {
            val encodedMessage = URLEncoder.encode(message, "UTF-8")
            val uri = Uri.parse("https://wa.me/$phoneNumber?text=$encodedMessage")

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
            }

            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "WhatsApp no está disponible",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun cancelEmergency() {
        val alertOffSent = bleManager.sendCommand("ALERT_OFF")
        debugCrash("ALERT_OFF sent to hardware = $alertOffSent")

        emergencyActive = false
        emergencyActiveGlobal = false
        collisionHits = 0
        collisionHitsGlobal = 0
        countdownTimer?.cancel()
        countdownTimer = null
        txtCountdown.text = ""

        resetCrashState("Crash state: NORMAL")

        NotificationManagerCompat.from(this).cancel(crashDetectedNotificationId)
        NotificationManagerCompat.from(this).cancel(countdownNotificationId)

        txtMcuStatus.text = if (bleConnected) {
            "MCU Status: Connected"
        } else {
            "MCU Status: Offline"
        }

        updateMainButton()
        updateTestAlertButton()
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

    private fun updateTestAlertButton() {
        val enabled = bleConnected && bleSubscribed && !emergencyActive

        btnTestAlert.isEnabled = enabled
        btnTestAlert.alpha = if (enabled) 1.0f else 0.5f
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