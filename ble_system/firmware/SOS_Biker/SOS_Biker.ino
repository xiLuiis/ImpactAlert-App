#include <Arduino.h>
#include <bluefruit.h>
#include <Wire.h>
#include "LSM6DS3.h"
#include <math.h>
#include <string.h>

#define ENABLE_VOICE_ML 0

#if ENABLE_VOICE_ML
#include <PDM.h>
#include "Edgar9206-project-1_inferencing.h"
#endif

#include <DFRobotDFPlayerMini.h>

// =====================================================
// BLE
// =====================================================

static const char* BLE_NAME = "SOS_Biker_XIAO";

BLEService imuService("19B10000-E8F2-537E-4F6C-D104768A1214");

BLECharacteristic statusChar("19B10001-E8F2-537E-4F6C-D104768A1214");
BLECharacteristic accChar   ("19B10002-E8F2-537E-4F6C-D104768A1214");
BLECharacteristic gyroChar  ("19B10003-E8F2-537E-4F6C-D104768A1214");
BLECharacteristic commandChar("19B10004-E8F2-537E-4F6C-D104768A1214");

// =====================================================
// IMU
// =====================================================
LSM6DS3 myIMU(I2C_MODE, 0x6A);

bool imuOk = false;
bool bleConnected = false;

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

// =====================================================
// ALERTA LOCAL
// =====================================================
bool localAlertActive = false;
uint32_t lastEmergencyTime = 0;
const uint32_t EMERGENCY_COOLDOWN_MS = 7000;

// =====================================================
// BOTON CANCELAR
// =====================================================
static const int CANCEL_BUTTON_PIN = 2;
static const uint32_t BUTTON_DEBOUNCE_MS = 40;
bool lastButtonReading = HIGH;
uint32_t lastButtonDebounceMs = 0;

// =====================================================
// DFPLAYER
// =====================================================
DFRobotDFPlayerMini dfPlayer;
bool dfPlayerOk = false;

// =====================================================
// DETECCION DE CAIDA
// =====================================================
enum CrashState {
  STATE_NORMAL,
  STATE_EVENT_DETECTED,
  STATE_WAITING_FOR_STILLNESS,
  STATE_CONFIRMED
};

CrashState crashState = STATE_NORMAL;
uint32_t stateStartMs = 0;
uint32_t stillnessStartMs = 0;

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

// =====================================================
// EDGE IMPULSE / MICROFONO
// =====================================================
#if ENABLE_VOICE_ML
static const float HELP_THRESHOLD = 0.25f;
static const uint8_t HELP_MIN_HITS = 1;

bool mlReady = false;
float lastHelpScore = 0.0f;
uint8_t helpHits = 0;
static bool debug_nn = false;

typedef struct {
  int16_t *buffer;
  volatile uint8_t buf_ready;
  volatile uint32_t buf_count;
  uint32_t n_samples;
} inference_t;

static inference_t inference;
static signed short sampleBuffer[2048];

#endif

// =====================================================
// HELPERS
// =====================================================
const char* stateToString(CrashState s) {
  switch (s) {
    case STATE_NORMAL: return "NORMAL";
    case STATE_EVENT_DETECTED: return "EVENT_DETECTED";
    case STATE_WAITING_FOR_STILLNESS: return "WAITING_FOR_STILLNESS";
    case STATE_CONFIRMED: return "CONFIRMED";
    default: return "UNKNOWN";
  }
}

void playMp3Track(uint16_t trackNumber) {
  if (!dfPlayerOk) return;
  dfPlayer.playMp3Folder(trackNumber);
}

void stopAudio() {
  if (!dfPlayerOk) return;
  dfPlayer.stop();
}

void triggerLocalAlert() {
  if (localAlertActive) return;

  localAlertActive = true;
  crashState = STATE_CONFIRMED;
  lastEmergencyTime = millis();

  Serial.println(">>> LOCAL ALERT ON <<<");
  Serial.println(">>> Aqui activarias buzzer/LED <<<");

  playMp3Track(11);

  statusChar.write((const uint8_t*)"EMERGENCY_ACTIVE", strlen("EMERGENCY_ACTIVE"));
  if (bleConnected) {
    statusChar.notify((const uint8_t*)"EMERGENCY_ACTIVE", strlen("EMERGENCY_ACTIVE"));
  }
}

void clearLocalAlert() {
  if (!localAlertActive && crashState == STATE_NORMAL) return;

  localAlertActive = false;
  stopAudio();

  Serial.println(">>> LOCAL ALERT OFF <<<");
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

  accMag = sqrtf((ax * ax) + (ay * ay) + (az * az));
  gyroMag = sqrtf((gx * gx) + (gy * gy) + (gz * gz));

  deltaAccMag = fabsf(accMag - lastAccMag);
  deltaGyroMag = fabsf(gyroMag - lastGyroMag);

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

// =====================================================
// CAIDA LOCAL
// =====================================================
void evaluateLocalCrash() {
  if (millis() - lastEmergencyTime < EMERGENCY_COOLDOWN_MS) return;
  if (localAlertActive) return;

  bool stillNow = isStillNow();

  if (stillNow) {
    if (stillnessStartMs == 0) stillnessStartMs = millis();
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
        triggerLocalAlert();
      }

      if (elapsed > POST_EVENT_WINDOW_MS) {
        resetCrashState();
      }
      break;
    }

    case STATE_CONFIRMED:
      break;
  }
}

// =====================================================
// BLE CALLBACKS BLUEFRUIT
// =====================================================
void connect_callback(uint16_t conn_handle) {
  (void)conn_handle;
  bleConnected = true;
  Serial.println("Central connected");

  const char* msg = localAlertActive ? "EMERGENCY_ACTIVE" : "CONNECTED";
  statusChar.write((const uint8_t*)msg, strlen(msg));
  statusChar.notify((const uint8_t*)msg, strlen(msg));
}

void disconnect_callback(uint16_t conn_handle, uint8_t reason) {
  (void)conn_handle;
  (void)reason;
  bleConnected = false;
  Serial.println("Central disconnected -> advertising again");
}

void command_write_callback(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  (void)conn_hdl;
  (void)chr;

  if (len == 0) return;
  if (len > 20) len = 20;

  char cmdBuffer[21];
  memcpy(cmdBuffer, data, len);
  cmdBuffer[len] = '\0';

  Serial.print("BLE COMMAND RECEIVED: ");
  Serial.println(cmdBuffer);

  if (strcmp(cmdBuffer, "ALERT_ON") == 0 ||
      strcmp(cmdBuffer, "HELP") == 0 ||
      strcmp(cmdBuffer, "VOICE") == 0 ||
      strcmp(cmdBuffer, "FALL") == 0) {
    triggerLocalAlert();
  } else if (strcmp(cmdBuffer, "ALERT_OFF") == 0 ||
             strcmp(cmdBuffer, "CANCEL") == 0 ||
             strcmp(cmdBuffer, "STOP") == 0 ||
             strcmp(cmdBuffer, "NORMAL") == 0) {
    clearLocalAlert();
    resetCrashState();
  }
}

void writeStatus() {
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

  statusChar.write((const uint8_t*)msg, strlen(msg));
  if (bleConnected) statusChar.notify((const uint8_t*)msg, strlen(msg));
}

#if ENABLE_VOICE_ML
// =====================================================
// MICROFONO / EDGE IMPULSE
// =====================================================
int getHelpIndex() {
  for (size_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
    if (strcmp(ei_classifier_inferencing_categories[i], "ayuda") == 0 ||
        strcmp(ei_classifier_inferencing_categories[i], "AYUDA") == 0 ||
        strcmp(ei_classifier_inferencing_categories[i], "help") == 0 ||
        strcmp(ei_classifier_inferencing_categories[i], "HELP") == 0) {
      return (int)i;
    }
  }
  return -1;
}

static void pdm_data_ready_inference_callback(void) {
  int bytesAvailable = PDM.available();
  int bytesRead = PDM.read((char *)&sampleBuffer[0], bytesAvailable);

  if (inference.buf_ready == 0) {
    for (int i = 0; i < (bytesRead >> 1); i++) {
      inference.buffer[inference.buf_count++] = sampleBuffer[i];

      if (inference.buf_count >= inference.n_samples) {
        inference.buf_count = 0;
        inference.buf_ready = 1;
        break;
      }
    }
  }
}

static bool microphone_inference_start(uint32_t n_samples) {
  inference.buffer = (int16_t *)malloc(n_samples * sizeof(int16_t));
  if (inference.buffer == NULL) return false;

  inference.buf_count = 0;
  inference.n_samples = n_samples;
  inference.buf_ready = 0;

  PDM.onReceive(&pdm_data_ready_inference_callback);
  PDM.setBufferSize(4096);

  if (!PDM.begin(1, EI_CLASSIFIER_FREQUENCY)) {
    ei_printf("Failed to start PDM!\n");
    PDM.end();
    free(inference.buffer);
    inference.buffer = nullptr;
    return false;
  }

  PDM.setGain(127);
  return true;
}

static int microphone_audio_signal_get_data(size_t offset, size_t length, float *out_ptr) {
  numpy::int16_to_float(&inference.buffer[offset], out_ptr, length);
  return 0;
}

bool initML() {
  Serial.println("[ML] Init microphone inference");

  if (!microphone_inference_start(EI_CLASSIFIER_RAW_SAMPLE_COUNT)) {
    Serial.println("[ML] MIC FAIL");
    return false;
  }

  int helpIndex = getHelpIndex();
  if (helpIndex < 0) {
    Serial.println("[ML] Etiqueta ayuda/help no encontrada");
  } else {
    Serial.print("[ML] helpIndex=");
    Serial.println(helpIndex);
  }

  Serial.println("[ML] MIC OK");
  return true;
}

void taskVoiceML() {
  if (!mlReady) return;
  if (localAlertActive || crashState != STATE_NORMAL) return;
  if (inference.buf_ready == 0) return;

  signal_t signal;
  signal.total_length = EI_CLASSIFIER_RAW_SAMPLE_COUNT;
  signal.get_data = &microphone_audio_signal_get_data;

  ei_impulse_result_t result = { 0 };

  EI_IMPULSE_ERROR r = run_classifier(&signal, &result, debug_nn);
  if (r != EI_IMPULSE_OK) {
    Serial.print("[ML] ERR classifier=");
    Serial.println((int)r);
    inference.buf_ready = 0;
    inference.buf_count = 0;
    return;
  }

  int helpIndex = getHelpIndex();
  if (helpIndex >= 0) {
    float ayudaScore = result.classification[helpIndex].value;
    float maxOther = 0.0f;

    for (size_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
      if ((int)i == helpIndex) continue;
      if (result.classification[i].value > maxOther) maxOther = result.classification[i].value;
    }

    lastHelpScore = ayudaScore;

    Serial.print("[ML] AYUDA=");
    Serial.print(ayudaScore, 5);
    Serial.print(" OTHER_MAX=");
    Serial.println(maxOther, 5);

    if (ayudaScore >= HELP_THRESHOLD && ayudaScore > maxOther) {
      if (helpHits < 255) helpHits++;
    } else {
      helpHits = 0;
    }

    if (helpHits >= HELP_MIN_HITS) {
      Serial.println("[ML] AYUDA DETECTADA");
      triggerLocalAlert();
      helpHits = 0;
    }
  }

  inference.buf_ready = 0;
  inference.buf_count = 0;
}

#endif

// =====================================================
// INIT
// =====================================================
void initIMU() {
  Wire.begin();

  if (myIMU.begin() == 0) {
    imuOk = true;
    Serial.println("IMU init OK");
  } else {
    imuOk = false;
    Serial.println("IMU init FAIL");
  }
}

void initBLE() {
  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName(BLE_NAME);

  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);

  imuService.begin();

  statusChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  statusChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  statusChar.setMaxLen(32);
  statusChar.begin();

  accChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  accChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  accChar.setMaxLen(20);
  accChar.begin();

  gyroChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  gyroChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  gyroChar.setMaxLen(20);
  gyroChar.begin();

  commandChar.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  commandChar.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  commandChar.setMaxLen(20);
  commandChar.setWriteCallback(command_write_callback);
  commandChar.begin();

  writeStatus();

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(imuService);
  Bluefruit.ScanResponse.addName();

  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);

  Serial.print("Advertising as: ");
  Serial.println(BLE_NAME);
}

void initDFPlayer() {
  Serial1.begin(9600);
  delay(1500);

  Serial.println("[AUD] Inicializando DFPlayer...");

  if (dfPlayer.begin(Serial1, true, true)) {
    dfPlayerOk = true;
    dfPlayer.volume(30);
    dfPlayer.outputDevice(DFPLAYER_DEVICE_SD);
    Serial.println("[AUD] DFPLAYER OK");
  } else {
    dfPlayerOk = false;
    Serial.println("[AUD] DFPLAYER FAIL");
  }
}

void taskCancelButton() {
  bool reading = digitalRead(CANCEL_BUTTON_PIN);

  if (reading != lastButtonReading) {
    lastButtonDebounceMs = millis();
    lastButtonReading = reading;
  }

  if ((millis() - lastButtonDebounceMs) > BUTTON_DEBOUNCE_MS) {
    static bool stableState = HIGH;

    if (reading != stableState) {
      stableState = reading;

      if (stableState == LOW) {
        Serial.println("[BTN] Cancel button pressed");
        clearLocalAlert();
        resetCrashState();
      }
    }
  }
}

// =====================================================
// SETUP
// =====================================================
void setup() {
  Serial.begin(115200);

  uint32_t serialWaitStart = millis();
  while (!Serial && (millis() - serialWaitStart < 3000)) {}

  pinMode(CANCEL_BUTTON_PIN, INPUT_PULLUP);

  Serial.println("=== SOS Biker XIAO Boot ===");

  initIMU();
  initBLE();
  initDFPlayer();
#if ENABLE_VOICE_ML
  mlReady = initML();
#endif

  Serial.println("System ready");
}

// =====================================================
// LOOP
// =====================================================
void loop() {
  if (imuOk && millis() - lastSensorRead >= 20) {
    lastSensorRead = millis();

    ax = myIMU.readFloatAccelX();
    ay = myIMU.readFloatAccelY();
    az = myIMU.readFloatAccelZ();

    gx = myIMU.readFloatGyroX();
    gy = myIMU.readFloatGyroY();
    gz = myIMU.readFloatGyroZ();

    updateMagnitudes();
    evaluateLocalCrash();
  }

  if (bleConnected && millis() - lastBleAccSend >= 100) {
    lastBleAccSend = millis();

    char accMsg[20];
    snprintf(accMsg, sizeof(accMsg), "%.2f,%.2f,%.2f", ax, ay, az);

    accChar.write((const uint8_t*)accMsg, strlen(accMsg));
    accChar.notify((const uint8_t*)accMsg, strlen(accMsg));
  }

  if (bleConnected && millis() - lastBleGyroSend >= 100) {
    lastBleGyroSend = millis();

    char gyroMsg[20];
    snprintf(gyroMsg, sizeof(gyroMsg), "%.2f,%.2f,%.2f", gx, gy, gz);

    gyroChar.write((const uint8_t*)gyroMsg, strlen(gyroMsg));
    gyroChar.notify((const uint8_t*)gyroMsg, strlen(gyroMsg));
  }

  if (millis() - lastStatusMsg >= 300) {
    lastStatusMsg = millis();
    writeStatus();
  }

  taskCancelButton();
#if ENABLE_VOICE_ML
  taskVoiceML();
#endif

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

  if (Serial.available()) {
    char c = Serial.read();

    if (c == '1') {
      triggerLocalAlert();
    } else if (c == '0') {
      clearLocalAlert();
      resetCrashState();
    } else if (c == 'i' || c == 'I') {
      Serial.print("ACC: ");
      Serial.print(ax); Serial.print(",");
      Serial.print(ay); Serial.print(",");
      Serial.println(az);

      Serial.print("GYRO: ");
      Serial.print(gx); Serial.print(",");
      Serial.print(gy); Serial.print(",");
      Serial.println(gz);
    }
  }
}

#if ENABLE_VOICE_ML
#if !defined(EI_CLASSIFIER_SENSOR) || EI_CLASSIFIER_SENSOR != EI_CLASSIFIER_SENSOR_MICROPHONE
#error "Invalid model for current sensor."
#endif
#endif
