#include <Arduino.h>
#include <ArduinoBLE.h>
#include <Wire.h>
#include "LSM6DS3.h"
#include <math.h>
#include <string.h>

static const char* BLE_NAME = "SOS_Biker_XIAO";

BLEService imuService("19B10000-E8F2-537E-4F6C-D104768A1214");

BLECharacteristic statusChar(
  "19B10001-E8F2-537E-4F6C-D104768A1214",
  BLERead | BLENotify,
  32
);

BLECharacteristic accChar(
  "19B10002-E8F2-537E-4F6C-D104768A1214",
  BLERead | BLENotify,
  20
);

BLECharacteristic gyroChar(
  "19B10003-E8F2-537E-4F6C-D104768A1214",
  BLERead | BLENotify,
  20
);

BLECharacteristic commandChar(
  "19B10004-E8F2-537E-4F6C-D104768A1214",
  BLEWrite | BLEWriteWithoutResponse,
  20
);

LSM6DS3 myIMU(I2C_MODE, 0x6A);

bool imuOk = false;
bool wasConnected = false;

// Timers
uint32_t lastSensorRead = 0;
uint32_t lastBleAccSend = 0;
uint32_t lastBleGyroSend = 0;
uint32_t lastStatusMsg = 0;
uint32_t lastDebugMsg = 0;

// Raw data
float ax = 0, ay = 0, az = 0;
float gx = 0, gy = 0, gz = 0;

// Magnitudes
float accMag = 0.0f;
float gyroMag = 0.0f;
float lastAccMag = 0.0f;
float lastGyroMag = 0.0f;
float deltaAccMag = 0.0f;
float deltaGyroMag = 0.0f;

// Peaks
float peakAccMag = 0.0f;
float peakGyroMag = 0.0f;

// Local alert
bool localAlertActive = false;

// Emergency cooldown
uint32_t lastEmergencyTime = 0;
const uint32_t EMERGENCY_COOLDOWN_MS = 7000;

// ===== State machine =====
enum CrashState {
  STATE_NORMAL,
  STATE_EVENT_DETECTED,
  STATE_WAITING_FOR_STILLNESS,
  STATE_CONFIRMED
};

CrashState crashState = STATE_NORMAL;
uint32_t stateStartMs = 0;
uint32_t stillnessStartMs = 0;

// ===== Thresholds endurecidos =====
const float IMPACT_THRESHOLD = 8.0f;
const float DELTA_ACC_THRESHOLD = 3.5f;
const float DELTA_GYRO_THRESHOLD = 350.0f;
const float MIN_ACC_FOR_ROTATION_EVENT = 5.0f;

const float STILL_ACC_MIN = 0.95f;
const float STILL_ACC_MAX = 1.08f;
const float STILL_GYRO_THRESHOLD = 8.0f;
const uint32_t STILLNESS_REQUIRED_MS = 2500;

const uint32_t EVENT_WINDOW_MS = 1200;
const uint32_t POST_EVENT_WINDOW_MS = 6000;

void triggerLocalAlert() {
  if (!localAlertActive) {
    localAlertActive = true;
    Serial.println(">>> LOCAL ALERT ON <<<");
    Serial.println(">>> Aqui activarias buzzer/LED <<<");
  }
}

void clearLocalAlert() {
  if (localAlertActive) {
    localAlertActive = false;
    Serial.println(">>> LOCAL ALERT OFF <<<");
  }
}

const char* stateToString(CrashState s) {
  switch (s) {
    case STATE_NORMAL: return "NORMAL";
    case STATE_EVENT_DETECTED: return "EVENT_DETECTED";
    case STATE_WAITING_FOR_STILLNESS: return "WAITING_FOR_STILLNESS";
    case STATE_CONFIRMED: return "CONFIRMED";
    default: return "UNKNOWN";
  }
}

void setCrashState(CrashState newState, const char* reason) {
  if (crashState != newState) {
    Serial.print("[STATE] ");
    Serial.print(stateToString(crashState));
    Serial.print(" -> ");
    Serial.print(stateToString(newState));
    Serial.print(" | ");
    Serial.println(reason);

    crashState = newState;
    stateStartMs = millis();
  }
}

void resetCrashState() {
  if (crashState != STATE_NORMAL) {
    Serial.print("[STATE] ");
    Serial.print(stateToString(crashState));
    Serial.println(" -> NORMAL | reset");
  }

  crashState = STATE_NORMAL;
  stateStartMs = 0;
  stillnessStartMs = 0;
  peakAccMag = 0.0f;
  peakGyroMag = 0.0f;
}

void updateMagnitudes() {
  lastAccMag = accMag;
  lastGyroMag = gyroMag;

  accMag = sqrt((ax * ax) + (ay * ay) + (az * az));
  gyroMag = sqrt((gx * gx) + (gy * gy) + (gz * gz));

  deltaAccMag = fabs(accMag - lastAccMag);
  deltaGyroMag = fabs(gyroMag - lastGyroMag);

  if (accMag > peakAccMag) peakAccMag = accMag;
  if (gyroMag > peakGyroMag) peakGyroMag = gyroMag;
}

bool isStillNow() {
  return (
    accMag >= STILL_ACC_MIN &&
    accMag <= STILL_ACC_MAX &&
    gyroMag <= STILL_GYRO_THRESHOLD
  );
}

void evaluateLocalCrash(bool bleConnected) {
  if (millis() - lastEmergencyTime < EMERGENCY_COOLDOWN_MS) {
    return;
  }

  bool stillNow = isStillNow();

  if (stillNow) {
    if (stillnessStartMs == 0) {
      stillnessStartMs = millis();
    }
  } else {
    stillnessStartMs = 0;
  }

  uint32_t stillnessDuration = (stillnessStartMs == 0) ? 0 : (millis() - stillnessStartMs);
  bool sustainedStillness = stillnessDuration >= STILLNESS_REQUIRED_MS;

  switch (crashState) {
    case STATE_NORMAL: {
      bool strongImpact = accMag >= IMPACT_THRESHOLD;

      bool rotationWithImpact =
        deltaGyroMag >= DELTA_GYRO_THRESHOLD &&
        accMag >= MIN_ACC_FOR_ROTATION_EVENT &&
        deltaAccMag >= DELTA_ACC_THRESHOLD;

      if (strongImpact || rotationWithImpact) {
        char reason[120];
        snprintf(reason, sizeof(reason),
                 "event | acc=%.2f dAcc=%.2f gyro=%.2f dGyro=%.2f",
                 accMag, deltaAccMag, gyroMag, deltaGyroMag);
        setCrashState(STATE_EVENT_DETECTED, reason);
      }
      break;
    }

    case STATE_EVENT_DETECTED: {
      uint32_t elapsed = millis() - stateStartMs;

      if (elapsed > EVENT_WINDOW_MS) {
        char reason[120];
        snprintf(reason, sizeof(reason),
                 "wait stillness | peakAcc=%.2f peakGyro=%.2f",
                 peakAccMag, peakGyroMag);
        setCrashState(STATE_WAITING_FOR_STILLNESS, reason);
      }
      break;
    }

    case STATE_WAITING_FOR_STILLNESS: {
      uint32_t elapsed = millis() - stateStartMs;

      if (sustainedStillness) {
        char reason[140];
        snprintf(reason, sizeof(reason),
                 "confirmed | peakAcc=%.2f peakGyro=%.2f stillMs=%lu",
                 peakAccMag, peakGyroMag, (unsigned long)stillnessDuration);
        setCrashState(STATE_CONFIRMED, reason);

        lastEmergencyTime = millis();

        // Por ahora el micro siempre puede activar su alerta local.
        // Si después quieres que con BLE la app tenga prioridad total, lo cambiamos.
        triggerLocalAlert();
      }

      if (elapsed > POST_EVENT_WINDOW_MS) {
        resetCrashState();
      }
      break;
    }

    case STATE_CONFIRMED:
      // Se queda en emergencia hasta que la app o el usuario limpien.
      break;
  }
}

void handleBleCommand() {
  if (!commandChar.written()) {
    return;
  }

  int len = commandChar.valueLength();
  if (len <= 0) return;
  if (len > 20) len = 20;

  char cmdBuffer[21];
  memcpy(cmdBuffer, commandChar.value(), len);
  cmdBuffer[len] = '\0';

  Serial.print("BLE COMMAND RECEIVED: ");
  Serial.println(cmdBuffer);

  if (strcmp(cmdBuffer, "ALERT_ON") == 0) {
    triggerLocalAlert();
    crashState = STATE_CONFIRMED;
  } else if (strcmp(cmdBuffer, "ALERT_OFF") == 0) {
    clearLocalAlert();
    resetCrashState();
  }
}

void writeStatus(bool bleConnected) {
  if (!bleConnected) return;

  const char* msg = "NORMAL";

  if (!imuOk) {
    msg = "IMU_FAIL";
  } else if (localAlertActive) {
    msg = "EMERGENCY_ACTIVE";
  } else if (crashState == STATE_EVENT_DETECTED || crashState == STATE_WAITING_FOR_STILLNESS) {
    msg = "CRASH_MONITORING";
  } else {
    msg = "IMU_OK";
  }

  statusChar.writeValue(msg);
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("=== SOS Biker XIAO Boot ===");

  if (myIMU.begin() == 0) {
    imuOk = true;
    Serial.println("IMU init OK");
  } else {
    Serial.println("IMU init FAIL");
  }

  if (!BLE.begin()) {
    Serial.println("BLE begin FAIL");
    while (1);
  }

  Serial.println("BLE begin OK");

  BLE.setLocalName(BLE_NAME);
  BLE.setAdvertisedService(imuService);

  imuService.addCharacteristic(statusChar);
  imuService.addCharacteristic(accChar);
  imuService.addCharacteristic(gyroChar);
  imuService.addCharacteristic(commandChar);
  BLE.addService(imuService);

  BLE.advertise();
  Serial.print("Advertising as: ");
  Serial.println(BLE_NAME);
}

void loop() {
  BLE.poll();

  BLEDevice central = BLE.central();
  bool bleConnected = central && central.connected();

  if (central && !wasConnected) {
    wasConnected = true;
    Serial.println("Central connected");

    if (localAlertActive) {
      statusChar.writeValue("EMERGENCY_ACTIVE");
    } else {
      statusChar.writeValue("CONNECTED");
    }
  }

  // Leer sensores siempre
  if (imuOk && millis() - lastSensorRead >= 20) {
    lastSensorRead = millis();

    ax = myIMU.readFloatAccelX();
    ay = myIMU.readFloatAccelY();
    az = myIMU.readFloatAccelZ();

    gx = myIMU.readFloatGyroX();
    gy = myIMU.readFloatGyroY();
    gz = myIMU.readFloatGyroZ();

    updateMagnitudes();
    evaluateLocalCrash(bleConnected);
  }

  // BLE: recibir comandos y enviar datos
  if (bleConnected) {
    handleBleCommand();

    if (millis() - lastBleAccSend >= 100) {
      lastBleAccSend = millis();
      String accMsg = String(ax, 2) + "," + String(ay, 2) + "," + String(az, 2);
      accChar.writeValue(accMsg.c_str());
    }

    if (millis() - lastBleGyroSend >= 100) {
      lastBleGyroSend = millis();
      String gyroMsg = String(gx, 2) + "," + String(gy, 2) + "," + String(gz, 2);
      gyroChar.writeValue(gyroMsg.c_str());
    }

    if (millis() - lastStatusMsg >= 300) {
      lastStatusMsg = millis();
      writeStatus(true);
    }
  }

  // Debug claro
  if (millis() - lastDebugMsg >= 500) {
    lastDebugMsg = millis();

    Serial.print("[DBG] state=");
    Serial.print(stateToString(crashState));

    Serial.print(" | accNow=");
    Serial.print(accMag, 2);

    Serial.print(" | accPeak=");
    Serial.print(peakAccMag, 2);

    Serial.print(" | dAcc=");
    Serial.print(deltaAccMag, 2);

    Serial.print(" | gyroNow=");
    Serial.print(gyroMag, 2);

    Serial.print(" | gyroPeak=");
    Serial.print(peakGyroMag, 2);

    Serial.print(" | dGyro=");
    Serial.print(deltaGyroMag, 2);

    Serial.print(" | still=");
    Serial.print(isStillNow() ? "true" : "false");

    Serial.print(" | alert=");
    Serial.print(localAlertActive ? "true" : "false");

    Serial.print(" | ble=");
    Serial.println(bleConnected ? "true" : "false");
  }

  if (wasConnected && (!central || !central.connected())) {
    wasConnected = false;
    Serial.println("Central disconnected -> advertising again");
    BLE.advertise();
  }
}