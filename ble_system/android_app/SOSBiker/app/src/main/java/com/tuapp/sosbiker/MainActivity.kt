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

class MainActivity : AppCompatActivity(), BleManager.Listener {
    private lateinit var txtCalibrationValues: TextView

    private lateinit var bleManager: BleManager

    private lateinit var btnStillnessMinus: Button
    private lateinit var btnStillnessPlus: Button
    private lateinit var btnEventWindowMinus: Button
    private lateinit var btnEventWindowPlus: Button
    private lateinit var btnPostEventMinus: Button
    private lateinit var btnPostEventPlus: Button
    private lateinit var btnImpactMinus: Button
    private lateinit var btnImpactPlus: Button
    private lateinit var btnGyroMinus: Button
    private lateinit var btnGyroPlus: Button
    private lateinit var btnDeltaAccMinus: Button
    private lateinit var btnDeltaAccPlus: Button
    private lateinit var btnResetMediumCalibration: Button
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
        POSSIBLE_CRASH,
        EVALUATING,
        WAITING_FOR_STILLNESS,
        CONFIRMED
    }

    private var crashStateMachine = CrashState.NORMAL
    private var stateStartMs = 0L
    private var stillnessStartMs = 0L

    private var impactThreshold = 6.5
    private var stillnessRequiredMs = 1800L
    private var eventWindowMs = 1000L
    private var postEventWindowMs = 5000L
    private var lastEmergencyMs = 0L
    private val emergencyCooldownMs = 6000L
    private var lastCancelMs = 0L

    private var stillAccMin = 0.90
    private var stillAccMax = 1.10
    private var stillGyroThreshold = 10.0
    private var deltaGyroThreshold = 220.0
    private var minAccForRotationEvent = 3.5
    private var deltaAccThreshold = 2.5

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
                Activity.RESULT_OK -> "SUCCESS (Enviado)"
                else -> "ERROR (Fallo)"
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

        txtCalibrationValues = findViewById(R.id.txtCalibrationValues)

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

        btnStillnessMinus = findViewById(R.id.btnStillnessMinus)
        btnStillnessPlus = findViewById(R.id.btnStillnessPlus)
        btnEventWindowMinus = findViewById(R.id.btnEventWindowMinus)
        btnEventWindowPlus = findViewById(R.id.btnEventWindowPlus)
        btnPostEventMinus = findViewById(R.id.btnPostEventMinus)
        btnPostEventPlus = findViewById(R.id.btnPostEventPlus)

        btnStillnessMinus.setOnClickListener {
            stillnessRequiredMs = maxOf(200L, stillnessRequiredMs - 200L)
            saveCurrentProfileCalibration()
        }

        btnStillnessPlus.setOnClickListener {
            stillnessRequiredMs += 200L
            saveCurrentProfileCalibration()
        }

        btnEventWindowMinus.setOnClickListener {
            eventWindowMs = maxOf(100L, eventWindowMs - 100L)
            saveCurrentProfileCalibration()
        }

        btnEventWindowPlus.setOnClickListener {
            eventWindowMs += 100L
            saveCurrentProfileCalibration()
        }

        btnPostEventMinus.setOnClickListener {
            postEventWindowMs = maxOf(500L, postEventWindowMs - 500L)
            saveCurrentProfileCalibration()
        }

        btnPostEventPlus.setOnClickListener {
            postEventWindowMs += 500L
            saveCurrentProfileCalibration()
        }

        btnImpactMinus = findViewById(R.id.btnImpactMinus)
        btnImpactPlus = findViewById(R.id.btnImpactPlus)
        btnGyroMinus = findViewById(R.id.btnGyroMinus)
        btnGyroPlus = findViewById(R.id.btnGyroPlus)
        btnDeltaAccMinus = findViewById(R.id.btnDeltaAccMinus)
        btnDeltaAccPlus = findViewById(R.id.btnDeltaAccPlus)
        btnResetMediumCalibration = findViewById(R.id.btnResetMediumCalibration)

        btnImpactMinus.setOnClickListener {
            impactThreshold -= 0.5
            saveCurrentProfileCalibration()
            updateCalibrationText()
        }

        btnImpactPlus.setOnClickListener {
            impactThreshold += 0.5
            saveCurrentProfileCalibration()
            updateCalibrationText()
        }

        btnGyroMinus.setOnClickListener {
            deltaGyroThreshold -= 20.0
            saveCurrentProfileCalibration()
            updateCalibrationText()
        }

        btnGyroPlus.setOnClickListener {
            deltaGyroThreshold += 20.0
            saveCurrentProfileCalibration()
            updateCalibrationText()
        }

        btnDeltaAccMinus.setOnClickListener {
            deltaAccThreshold -= 0.2
            saveCurrentProfileCalibration()
            updateCalibrationText()
        }

        btnDeltaAccPlus.setOnClickListener {
            deltaAccThreshold += 0.2
            saveCurrentProfileCalibration()
            updateCalibrationText()
        }

        btnResetMediumCalibration.setOnClickListener {
            resetCurrentProfileCalibration()
        }

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
        val profile = when (checkedId) {
            R.id.radioHigh -> "high"
            R.id.radioLow -> "low"
            else -> "medium"
        }

        loadProfileCalibration(profile)
    }

    private fun applyDefaultProfileValues(profile: String) {
        when (profile) {
            "high" -> {
                impactThreshold = 4.5
                deltaAccThreshold = 2.0
                deltaGyroThreshold = 160.0
                minAccForRotationEvent = 2.8
                stillGyroThreshold = 14.0
                stillnessRequiredMs = 1400L
                eventWindowMs = 900L
                postEventWindowMs = 5500L
            }

            "low" -> {
                impactThreshold = 8.5
                deltaAccThreshold = 3.5
                deltaGyroThreshold = 300.0
                minAccForRotationEvent = 4.5
                stillGyroThreshold = 8.0
                stillnessRequiredMs = 2200L
                eventWindowMs = 1100L
                postEventWindowMs = 4500L
            }

            else -> {
                impactThreshold = 6.5
                deltaAccThreshold = 2.5
                deltaGyroThreshold = 220.0
                minAccForRotationEvent = 3.5
                stillGyroThreshold = 10.0
                stillnessRequiredMs = 1800L
                eventWindowMs = 1000L
                postEventWindowMs = 5000L
            }
        }
    }

    private fun loadProfileCalibration(profile: String) {
        applyDefaultProfileValues(profile)

        impactThreshold = prefs.getFloat("${profile}_impactThreshold", impactThreshold.toFloat()).toDouble()
        deltaAccThreshold = prefs.getFloat("${profile}_deltaAccThreshold", deltaAccThreshold.toFloat()).toDouble()
        deltaGyroThreshold = prefs.getFloat("${profile}_deltaGyroThreshold", deltaGyroThreshold.toFloat()).toDouble()
        minAccForRotationEvent = prefs.getFloat("${profile}_minAccForRotationEvent", minAccForRotationEvent.toFloat()).toDouble()
        stillGyroThreshold = prefs.getFloat("${profile}_stillGyroThreshold", stillGyroThreshold.toFloat()).toDouble()
        stillnessRequiredMs = prefs.getLong("${profile}_stillnessRequiredMs", stillnessRequiredMs)
        eventWindowMs = prefs.getLong("${profile}_eventWindowMs", eventWindowMs)
        postEventWindowMs = prefs.getLong("${profile}_postEventWindowMs", postEventWindowMs)

        updateCalibrationText()
    }

    private fun saveCurrentProfileCalibration() {
        val profile = currentProfileKey()

        prefs.edit()
            .putFloat("${profile}_impactThreshold", impactThreshold.toFloat())
            .putFloat("${profile}_deltaAccThreshold", deltaAccThreshold.toFloat())
            .putFloat("${profile}_deltaGyroThreshold", deltaGyroThreshold.toFloat())
            .putFloat("${profile}_minAccForRotationEvent", minAccForRotationEvent.toFloat())
            .putFloat("${profile}_stillGyroThreshold", stillGyroThreshold.toFloat())
            .putLong("${profile}_stillnessRequiredMs", stillnessRequiredMs)
            .putLong("${profile}_eventWindowMs", eventWindowMs)
            .putLong("${profile}_postEventWindowMs", postEventWindowMs)
            .apply()

        updateCalibrationText()
    }

    private fun currentProfileKey(): String {
        return when (radioGroupSensitivity.checkedRadioButtonId) {
            R.id.radioHigh -> "high"
            R.id.radioLow -> "low"
            else -> "medium"
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
            bleConnected = text == "BLE: connected" || text == "BLE: subscribed"
            bleSubscribed = text == "BLE: subscribed"
            
            if (!bleConnected) {
                txtMcuStatus.text = "MCU Status: Offline"
                txtCrashState.text = "State: OFFLINE"
            } else {
                txtMcuStatus.text = "MCU Status: Connected"
                if (crashStateMachine == CrashState.NORMAL) txtCrashState.text = "State: NORMAL"
            }
            
            updateMainButton()
            updateTestAlertButton()
        }
    }

    override fun onStatusValue(text: String) {
        runOnUiThread {
            val now = System.currentTimeMillis()
            val upperText = text.uppercase()
            when {
                upperText.contains("EMERGENCY_ACTIVE") -> {
                    txtMcuStatus.text = "MCU Status: EMERGENCY ACTIVE"
                    // Sincronizar estado visual si el micro ya está en emergencia
                    if (crashStateMachine != CrashState.CONFIRMED) {
                        crashStateMachine = CrashState.CONFIRMED
                        txtCrashState.text = "State: CONFIRMED"
                    }
                    
                    if (!emergencyActive && (now - lastCancelMs > 3000)) {
                        triggerEmergency("microcontrolador")
                    }
                }
                upperText.contains("IMU_OK") -> {
                    txtMcuStatus.text = "MCU Status: Connected"
                    if (!emergencyActive) {
                        txtMechanismStatus.text = "Sistema de monitoreo activo"
                        // NO llamamos a resetCrashState aquí porque el mensaje periódico del Arduino
                        // interrumpía la lógica de detección de la App.
                    }
                    updateMainButton()
                }
                upperText.contains("IMU_FAIL") -> {
                    txtMcuStatus.text = "MCU Status: SENSOR FAIL"
                }
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
                    crashStateMachine = CrashState.POSSIBLE_CRASH
                    stateStartMs = now
                    txtCrashState.text = "State: POSSIBLE_CRASH"
                }
            }
            CrashState.POSSIBLE_CRASH -> {
                crashStateMachine = CrashState.EVALUATING
                txtCrashState.text = "State: EVALUATING"
            }

            CrashState.EVALUATING -> {
                if (now - stateStartMs > eventWindowMs) {
                    crashStateMachine = CrashState.WAITING_FOR_STILLNESS
                    stateStartMs = now
                    txtCrashState.text = "State: WAITING_FOR_STILLNESS"
                }
            }
            CrashState.WAITING_FOR_STILLNESS -> {
                if (stillnessDuration >= stillnessRequiredMs) {
                    crashStateMachine = CrashState.CONFIRMED
                    txtCrashState.text = "State: CONFIRMED"
                    triggerEmergency()
                } else if (now - stateStartMs > postEventWindowMs) { resetCrashState() }
            }
            else -> {}
        }
    }

    private fun triggerEmergency(source: String = "app") {
        if (emergencyActive) return
        emergencyActive = true
        emergencyActiveGlobal = true

        // Sincronizar estado visual
        crashStateMachine = CrashState.CONFIRMED
        runOnUiThread {
            txtCrashState.text = "State: CONFIRMED"
            txtMechanismStatus.text = if (source == "microcontrolador") "Emergencia detectada por dispositivo" else "Activando mecanismo de auxilio..."
            updateMainButton()
        }

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
        if (!enabled) {
            runOnUiThread { txtSmsLog.text = "SMS Status: Deshabilitado en ajustes" }
            return
        }
        val rawNumbers = EmergencyContactsStore.getEnabledPhoneNumbers(this)
        if (rawNumbers.isEmpty()) {
            runOnUiThread { txtSmsLog.text = "SMS Status: Sin contactos habilitados" }
            return
        }

        runOnUiThread { txtSmsLog.text = "SMS Status: Enviando a ${rawNumbers.size} contactos..." }

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
        lastCancelMs = System.currentTimeMillis()
        bleManager.sendCommand("ALERT_OFF")
        emergencyActive = false
        emergencyActiveGlobal = false
        countdownTimer?.cancel()
        countdownTimer = null
        txtCountdown.text = ""
        txtSmsLog.text = "SMS Status: Alerta cancelada"
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

    private fun updateCalibrationText() {
        if (!::txtCalibrationValues.isInitialized) return

        val profile = currentProfileKey().uppercase()

        txtCalibrationValues.text =
            """
        $profile PROFILE CALIBRATION

        Impact threshold: $impactThreshold
        Delta Acc threshold: $deltaAccThreshold
        Delta Gyro threshold: $deltaGyroThreshold
        Min Acc for rotation: $minAccForRotationEvent
        Still Gyro threshold: $stillGyroThreshold

        Stillness required: ${stillnessRequiredMs}ms
        Event window: ${eventWindowMs}ms
        Post-event window: ${postEventWindowMs}ms
        """.trimIndent()
    }

    private fun resetCurrentProfileCalibration() {
        val profile = currentProfileKey()

        applyDefaultProfileValues(profile)

        prefs.edit()
            .remove("${profile}_impactThreshold")
            .remove("${profile}_deltaAccThreshold")
            .remove("${profile}_deltaGyroThreshold")
            .remove("${profile}_minAccForRotationEvent")
            .remove("${profile}_stillGyroThreshold")
            .remove("${profile}_stillnessRequiredMs")
            .remove("${profile}_eventWindowMs")
            .remove("${profile}_postEventWindowMs")
            .apply()

        updateCalibrationText()
    }
    private fun magnitude(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)


}