package com.tuapp.sosbiker

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.abs
import kotlin.math.sqrt
import java.net.URLEncoder
import kotlin.math.max

class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var bleManager: BleManager

    private lateinit var btnTabHome: Button
    private lateinit var btnTabSettings: Button
    private lateinit var btnTabBle: Button
    
    private lateinit var homeScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private lateinit var bleScroll: ScrollView
    
    private lateinit var chkDebugMode: CheckBox
    private lateinit var switchSmsEnabled: SwitchCompat
    private lateinit var switchIncludeLocation: SwitchCompat
    private lateinit var radioGroupSensitivity: RadioGroup

    private lateinit var txtMcuStatus: TextView
    private lateinit var txtCountdown: TextView
    private lateinit var txtMechanismStatus: TextView
    private lateinit var txtBleStatus: TextView
    private lateinit var txtSmsLog: TextView
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

    private lateinit var prefs: SharedPreferences

    private var accMag = 0.0
    private var gyroMag = 0.0
    private var lastAccMag = 0.0
    private var lastGyroMag = 0.0

    private var emergencyActive = false
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

    private var impactThreshold = 6.5 
    private val stillnessRequiredMs = 1800L
    private val eventWindowMs = 1000L
    private val postEventWindowMs = 5000L
    private var lastEmergencyMs = 0L
    private val emergencyCooldownMs = 6000L

    private val stillAccMin = 0.90
    private val stillAccMax = 1.10
    private val stillGyroThreshold = 10.0
    private val deltaGyroThreshold = 220.0
    private val minAccForRotationEvent = 3.5
    private val deltaAccThreshold = 2.5

    companion object {
        var emergencyActiveGlobal = false
        var cancelEmergencyFromNotification = false
        const val EXTRA_NAVIGATE_HOME = "NAVIGATE_TO_HOME"
        const val SMS_SENT_ACTION = "com.tuapp.sosbiker.SMS_SENT"
    }

    private val emergencyChannelId = "emergency_channel_v2"
    private val countdownNotificationId = 1002
    
    private val uiRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (cancelEmergencyFromNotification) {
                cancelEmergencyFromNotification = false
                cancelEmergency()
            }
        }
    }

    private val smsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = when (resultCode) {
                Activity.RESULT_OK -> "SUCCESS"
                else -> "ERROR"
            }
            runOnUiThread { txtSmsLog.text = "SMS Status: $status" }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            startBleAutoConnect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bleManager = BleManager(this, this)
        prefs = getSharedPreferences("SOSBikerPrefs", MODE_PRIVATE)

        btnTabHome = findViewById(R.id.btnTabHome)
        btnTabSettings = findViewById(R.id.btnTabSettings)
        btnTabBle = findViewById(R.id.btnTabBle)
        homeScroll = findViewById(R.id.homeScroll)
        settingsScroll = findViewById(R.id.settingsScroll)
        bleScroll = findViewById(R.id.bleScroll)
        
        chkDebugMode = findViewById(R.id.chkDebugMode)
        switchSmsEnabled = findViewById(R.id.switchSmsEnabled)
        switchIncludeLocation = findViewById(R.id.switchIncludeLocation)
        radioGroupSensitivity = findViewById(R.id.radioGroupSensitivity)
        
        txtMcuStatus = findViewById(R.id.txtMcuStatus)
        txtCountdown = findViewById(R.id.txtCountdown)
        txtMechanismStatus = findViewById(R.id.txtMechanismStatus)
        txtSmsLog = findViewById(R.id.txtSmsLog)
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

        switchSmsEnabled.isChecked = prefs.getBoolean("sms_enabled", true)
        switchSmsEnabled.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("sms_enabled", isChecked).apply() }
        
        switchIncludeLocation.isChecked = prefs.getBoolean("location_enabled", true)
        switchIncludeLocation.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("location_enabled", isChecked).apply() }

        loadSensitivityProfile()
        radioGroupSensitivity.setOnCheckedChangeListener { _, checkedId -> saveSensitivityProfile(checkedId) }

        showHome()
        btnTabHome.setOnClickListener { showHome() }
        btnTabSettings.setOnClickListener { showSettings() }
        btnTabBle.setOnClickListener { showBle() }

        chkDebugMode.setOnCheckedChangeListener { _, isChecked ->
            debugModeEnabled = isChecked
            btnTabBle.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked && bleScroll.visibility == View.VISIBLE) showHome()
        }

        btnConnectBle.setOnClickListener { requestPermissionsAndAutoConnect() }
        btnTestAlert.setOnClickListener { triggerEmergencyTest() }
        btnEmergencyContacts.setOnClickListener { openEmergencyContacts() }
        btnMain.setOnClickListener { if (emergencyActive) cancelEmergency() }
        btnEmergency.setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:911") }) }

        updateMainButton()
        updateTestAlertButton()
        createEmergencyChannel()
        requestPermissionsAndAutoConnect()

        val filterUI = IntentFilter("com.tuapp.sosbiker.REFRESH_UI")
        val filterSMS = IntentFilter(SMS_SENT_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiRefreshReceiver, filterUI, RECEIVER_NOT_EXPORTED)
            registerReceiver(smsStatusReceiver, filterSMS, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiRefreshReceiver, filterUI)
            registerReceiver(smsStatusReceiver, filterSMS)
        }
    }

    private fun loadSensitivityProfile() {
        val selectedId = prefs.getInt("sensitivity_id", R.id.radioMedium)
        radioGroupSensitivity.check(selectedId)
        applySensitivity(selectedId)
    }

    private fun saveSensitivityProfile(checkedId: Int) {
        prefs.edit().putInt("sensitivity_id", checkedId).apply()
        applySensitivity(checkedId)
    }

    private fun applySensitivity(checkedId: Int) {
        impactThreshold = when (checkedId) {
            R.id.radioHigh -> 4.5
            R.id.radioLow -> 8.5
            else -> 6.5
        }
    }

    private fun showHome() {
        homeScroll.visibility = View.VISIBLE
        settingsScroll.visibility = View.GONE
        bleScroll.visibility = View.GONE
    }

    private fun showSettings() {
        homeScroll.visibility = View.GONE
        settingsScroll.visibility = View.VISIBLE
        bleScroll.visibility = View.GONE
    }

    private fun showBle() {
        homeScroll.visibility = View.GONE
        settingsScroll.visibility = View.GONE
        bleScroll.visibility = View.VISIBLE
    }

    private fun createEmergencyChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(emergencyChannelId, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Crash alerts"
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestPermissionsAndAutoConnect() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.SEND_SMS)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val toRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
        else startBleAutoConnect()
    }
    
    private fun startBleAutoConnect() { bleManager.startAutoConnect() }

    override fun onBleStatus(text: String) {
        runOnUiThread {
            txtBleStatus.text = "BLE Status: $text"
            bleConnected = text.contains("connected", ignoreCase = true) || text.contains("subscribed", ignoreCase = true)
            bleSubscribed = text.contains("subscribed", ignoreCase = true)
            txtMcuStatus.text = if (bleConnected) "MCU Status: Connected" else "MCU Status: Offline"
            updateMainButton()
            updateTestAlertButton()
        }
    }

    override fun onStatusValue(text: String) {
        runOnUiThread {
            if (text.contains("IMU_OK", ignoreCase = true) || text.contains("CONNECTED", ignoreCase = true)) {
                bleConnected = true
                updateMainButton()
            }
        }
    }

    override fun onAccValue(text: String) {
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
        val triple = parseTriple(text) ?: return
        lastGyroMag = gyroMag
        gyroMag = magnitude(triple.first, triple.second, triple.third)
        runOnUiThread {
            txtGyro.text = "GYRO: $text"
            txtGyroMag.text = "GYRO MAG: %.2f".format(gyroMag)
            evaluateCrash()
        }
    }

    private fun showEmergencyNotification(secondsLeft: Int) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_HOME, true)
        }
        val openPI = PendingIntent.getActivity(this, 20, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancelIntent = Intent(this, EmergencyActionReceiver::class.java).apply { action = "com.tuapp.sosbiker.ACTION_CANCEL_EMERGENCY" }
        val cancelPI = PendingIntent.getBroadcast(this, 21, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, emergencyChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("¡CHOQUE DETECTADO!")
            .setContentText("Activando mecanismo de auxilio en $secondsLeft seg.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCELAR", cancelPI)
            .build()

        NotificationManagerCompat.from(this).notify(countdownNotificationId, notification)
    }

    private fun resetCrashState() {
        crashStateMachine = CrashState.NORMAL
        stateStartMs = 0L
        stillnessStartMs = 0L
        txtCrashState.text = "State: NORMAL"
        txtMechanismStatus.text = "Sistema de monitoreo activo"
    }

    private fun evaluateCrash() {
        if (emergencyActive || !bleConnected || !bleSubscribed) return
        val now = System.currentTimeMillis()
        if (now - lastEmergencyMs < emergencyCooldownMs) return

        val isStillNow = accMag in stillAccMin..stillAccMax && gyroMag <= stillGyroThreshold
        if (isStillNow) { if (stillnessStartMs == 0L) stillnessStartMs = now } else { stillnessStartMs = 0L }
        val stillnessDuration = if (stillnessStartMs == 0L) 0L else now - stillnessStartMs

        when (crashStateMachine) {
            CrashState.NORMAL -> {
                val deltaAccMag = abs(accMag - lastAccMag)
                val deltaGyroMag = abs(gyroMag - lastGyroMag)
                if (accMag >= impactThreshold || (deltaGyroMag >= deltaGyroThreshold && (accMag >= minAccForRotationEvent || deltaAccMag >= deltaAccThreshold))) {
                    crashStateMachine = CrashState.EVENT_DETECTED
                    stateStartMs = now
                }
            }
            CrashState.EVENT_DETECTED -> { if (now - stateStartMs > eventWindowMs) { crashStateMachine = CrashState.WAITING_FOR_STILLNESS; stateStartMs = now } }
            CrashState.WAITING_FOR_STILLNESS -> {
                if (stillnessDuration >= stillnessRequiredMs) {
                    crashStateMachine = CrashState.CONFIRMED
                    triggerEmergency()
                } else if (now - stateStartMs > postEventWindowMs) { resetCrashState() }
            }
            else -> {}
        }
    }

    private fun triggerEmergency() {
        emergencyActive = true
        emergencyActiveGlobal = true
        txtMechanismStatus.text = "Activando mecanismo de auxilio..."
        updateMainButton()
        bleManager.sendCommand("ALERT_ON")

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(8000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L).toInt()
                txtCountdown.text = "$sec"
                showEmergencyNotification(sec)
                if (cancelEmergencyFromNotification) { cancelEmergencyFromNotification = false; cancelEmergency() }
            }
            override fun onFinish() {
                if (!emergencyActive) return
                txtMechanismStatus.text = "Mecanismo de auxilio activado"
                txtCountdown.text = "OK"
                sendEmergencySmsToAllContacts()
                NotificationManagerCompat.from(this@MainActivity).cancel(countdownNotificationId)
            }
        }.start()
    }

    private fun triggerEmergencyTest() { if (bleConnected && bleSubscribed) triggerEmergency() }
    private fun openEmergencyContacts() { startActivity(Intent(this, EmergencyContactsActivity::class.java)) }

    private fun sendEmergencySmsToAllContacts() {
        val enabled = prefs.getBoolean("sms_enabled", true)
        if (!enabled) return
        val rawNumbers = EmergencyContactsStore.getEnabledPhoneNumbers(this)
        if (rawNumbers.isEmpty()) return

        val includeLocation = prefs.getBoolean("location_enabled", true)
        if (includeLocation && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            val locStr = " Ubicación: https://www.google.com/maps?q=${location.latitude},${location.longitude}"
                            performSmsSend(rawNumbers, "🚨 SOS Biker: Posible accidente detectado. Necesito ayuda.$locStr")
                        } else {
                            performSmsSend(rawNumbers, "🚨 SOS Biker: Posible accidente detectado. Necesito ayuda.")
                        }
                    }
            } catch (e: Exception) {
                performSmsSend(rawNumbers, "🚨 SOS Biker: Posible accidente detectado. Necesito ayuda.")
            }
        } else {
            performSmsSend(rawNumbers, "🚨 SOS Biker: Posible accidente detectado. Necesito ayuda.")
        }
    }

    private fun performSmsSend(rawNumbers: List<String>, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) this.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            val sentPI = PendingIntent.getBroadcast(this, 100, Intent(SMS_SENT_ACTION), PendingIntent.FLAG_IMMUTABLE)
            val sentIntents = ArrayList<PendingIntent>()
            for (i in 0 until parts.size) sentIntents.add(sentPI)

            for (rawNum in rawNumbers) {
                var number = rawNum.replace(Regex("[^0-9+]"), "")
                if (!number.startsWith("+")) {
                    if (number.length == 10) number = "+52$number"
                    else if (number.startsWith("52")) number = "+$number"
                }
                smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            }
        } catch (e: Exception) { Log.e("SOSBIKER", "SMS Send failed", e) }
    }

    private fun cancelEmergency() {
        bleManager.sendCommand("ALERT_OFF")
        emergencyActive = false
        emergencyActiveGlobal = false
        countdownTimer?.cancel()
        countdownTimer = null
        txtCountdown.text = ""
        resetCrashState()
        NotificationManagerCompat.from(this).cancelAll()
        updateMainButton()
        showHome()
    }

    private fun updateMainButton() {
        runOnUiThread {
            when {
                emergencyActive -> { btnMain.text = "CANCEL EMERGENCY"; btnMain.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#D93D33")) }
                !bleConnected -> { btnMain.text = "BLE OFFLINE"; btnMain.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#9E9E9E")) }
                else -> { btnMain.text = "SYSTEM NORMAL"; btnMain.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2ECC71")) }
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
        return try { Triple(parts[0].trim().toDouble(), parts[1].trim().toDouble(), parts[2].trim().toDouble()) } catch (_: Exception) { null }
    }

    private fun magnitude(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)
}