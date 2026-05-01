#include <Arduino.h>
#include <bluefruit.h>
#include "LSM6DS3.h"
#include "Wire.h"
#include <PDM.h>
#include "Edgar9206-project-1_inferencing.h"
#include <DFRobotDFPlayerMini.h>

// =====================================================
// CONFIG
// =====================================================
static const char* DEVICE_NAME = "SOS_Biker";

// ---------------- ML tuning ----------------
static const float HELP_THRESHOLD = 0.25f;
static const uint8_t HELP_MIN_HITS = 1;

// ---------------- botón ----------------
static const int CANCEL_BUTTON_PIN = 2;

// ---------------- tiempos ----------------
static const uint32_t COUNTDOWN_MS = 10000;
static const uint32_t INTRO_AUDIO_MS = 2600;
static const uint32_t BUTTON_DEBOUNCE_MS = 40;
static const uint32_t CANCEL_COOLDOWN_MS = 5000;

// ---------------- UUIDs custom ----------------
BLEService sosService("19B10000-E8F2-537E-4F6C-D104768A1214");
BLECharacteristic statusChar ("19B10001-E8F2-537E-4F6C-D104768A1214");
BLECharacteristic imuChar    ("19B10002-E8F2-537E-4F6C-D104768A1214");
BLECharacteristic alertChar  ("19B10003-E8F2-537E-4F6C-D104768A1214");
BLECharacteristic commandChar("19B10004-E8F2-537E-4F6C-D104768A1214");

// =====================================================
// IMU
// =====================================================
LSM6DS3 myIMU(I2C_MODE, 0x6A);
bool imuOk = false;

// crudo
float imuAx = 0.0f;
float imuAy = 0.0f;
float imuAz = 0.0f;

// filtrado
float imuAxF = 0.0f;
float imuAyF = 0.0f;
float imuAzF = 1.0f;

// métricas limpias
float imuG = 1.0f;
float imuTiltDeg = 0.0f;
float imuImpact = 0.0f;

// filtro
static float IMU_FILTER_ALPHA = 0.70f;

// =====================================================
// DETECCIÓN DE CAÍDA
// =====================================================
static bool fallEnabled = true;

// umbrales ajustables
static float FALL_IMPACT_THRESHOLD_G = 1.10f;
static float FALL_TILT_THRESHOLD_DEG = 20.0f;
static uint32_t FALL_CONFIRM_WINDOW_MS = 2200;
static uint32_t FALL_EVENT_COOLDOWN_MS = 8000;
static uint32_t FALL_TILT_HOLD_MS = 400;
static uint32_t FALL_MIN_REARM_MS = 1200;

enum FallStage {
  FALL_IDLE = 0,
  FALL_MONITORING
};

FallStage fallStage = FALL_IDLE;
uint32_t fallStageStartMs = 0;
uint32_t lastFallTriggerMs = 0;
uint32_t lastFallResetMs = 0;

bool tiltHoldActive = false;
uint32_t tiltHoldStartMs = 0;

// =====================================================
// DFPLAYER
// =====================================================
DFRobotDFPlayerMini dfPlayer;
bool dfPlayerOk = false;

// =====================================================
// MACHINE STATE
// =====================================================
enum SystemMode {
  MODE_NORMAL = 0,
  MODE_PRE_ALERT,
  MODE_CONFIRMED,
  MODE_CANCELED
};

SystemMode systemMode = MODE_NORMAL;

// origen del disparo
enum TriggerSource {
  TRIGGER_NONE = 0,
  TRIGGER_VOICE,
  TRIGGER_FALL
};

TriggerSource triggerSource = TRIGGER_NONE;

// =====================================================
// GENERAL STATE
// =====================================================
bool bleConnected = false;
bool mlReady = false;

float lastHelpScore = 0.0f;
uint8_t helpHits = 0;

// intro y countdown
bool introPlaying = false;
bool countdownActive = false;
uint32_t introStartMs = 0;
uint32_t countdownStartMs = 0;
uint8_t lastCountdownSecond = 255;

// cooldown cancelación
bool cancelCooldownActive = false;
uint32_t cancelCooldownStartMs = 0;

// button
bool lastButtonReading = HIGH;
uint32_t lastButtonDebounceMs = 0;

// timers
uint32_t lastImuReadMs = 0;
uint32_t lastImuSerialMs = 0;
uint32_t lastBleImuMs = 0;
uint32_t lastBleStatusMs = 0;
uint32_t lastSystemLogMs = 0;

// =====================================================
// EDGE IMPULSE / AUDIO BUFFER
// =====================================================
typedef struct {
  int16_t *buffer;
  volatile uint8_t buf_ready;
  volatile uint32_t buf_count;
  uint32_t n_samples;
} inference_t;

static inference_t inference;
static signed short sampleBuffer[2048];
static bool debug_nn = false;

// =====================================================
// LOG HELPERS
// =====================================================
void logBoot(const char* msg) {
  Serial.print("[BOOT] ");
  Serial.println(msg);
}

void logInit(const char* msg) {
  Serial.print("[INIT] ");
  Serial.println(msg);
}

void logBle(const char* msg) {
  Serial.print("[BLE ] ");
  Serial.println(msg);
}

void logSys(const char* msg) {
  Serial.print("[SYS ] ");
  Serial.println(msg);
}

void logML(const char* msg) {
  Serial.print("[ML  ] ");
  Serial.println(msg);
}

void logAudio(const char* msg) {
  Serial.print("[AUD ] ");
  Serial.println(msg);
}

void logFall(const char* msg) {
  Serial.print("[FALL] ");
  Serial.println(msg);
}

void logImuMetrics(float g, float tilt, float impact) {
  Serial.print("[IMU ] G=");
  Serial.print(g, 2);
  Serial.print(" Tilt=");
  Serial.print(tilt, 1);
  Serial.print(" Impact=");
  Serial.println(impact, 2);
}

// =====================================================
// MATH HELPERS
// =====================================================
float calcMagnitudeG(float x, float y, float z) {
  return sqrtf((x * x) + (y * y) + (z * z));
}

float calcTiltDeg(float x, float y, float z) {
  return atan2f(sqrtf((x * x) + (y * y)), z) * 180.0f / PI;
}

void updateFilteredImu(float rawX, float rawY, float rawZ) {
  imuAxF = (IMU_FILTER_ALPHA * imuAxF) + ((1.0f - IMU_FILTER_ALPHA) * rawX);
  imuAyF = (IMU_FILTER_ALPHA * imuAyF) + ((1.0f - IMU_FILTER_ALPHA) * rawY);
  imuAzF = (IMU_FILTER_ALPHA * imuAzF) + ((1.0f - IMU_FILTER_ALPHA) * rawZ);
}

void updateImuMetrics() {
  imuG = calcMagnitudeG(imuAxF, imuAyF, imuAzF);
  imuTiltDeg = calcTiltDeg(imuAxF, imuAyF, imuAzF);
  imuImpact = fabsf(imuG - 1.0f);
}

// =====================================================
// TEXT HELPERS
// =====================================================
const char* modeToText(SystemMode mode) {
  switch (mode) {
    case MODE_NORMAL:    return "N";
    case MODE_PRE_ALERT: return "P";
    case MODE_CONFIRMED: return "E";
    case MODE_CANCELED:  return "C";
    default:             return "U";
  }
}

const char* fallStageToText(FallStage stage) {
  switch (stage) {
    case FALL_IDLE:       return "I";
    case FALL_MONITORING: return "M";
    default:              return "U";
  }
}

const char* triggerToText(TriggerSource source) {
  switch (source) {
    case TRIGGER_NONE:  return "N";
    case TRIGGER_VOICE: return "V";
    case TRIGGER_FALL:  return "F";
    default:            return "U";
  }
}

bool isAlertMode() {
  return (systemMode == MODE_PRE_ALERT || systemMode == MODE_CONFIRMED);
}

uint8_t getAlertFlag() {
  return isAlertMode() ? 1 : 0;
}

// =====================================================
// BLE CALLBACKS
// =====================================================
void connect_callback(uint16_t conn_handle) {
  (void)conn_handle;
  bleConnected = true;
  logBle("Connected");
}

void disconnect_callback(uint16_t conn_handle, uint8_t reason) {
  (void)conn_handle;
  (void)reason;
  bleConnected = false;
  logBle("Disconnected");
}

// =====================================================
// BLE SEND HELPERS
// =====================================================
void bleSendStatus(const char* msg) {
  statusChar.write((const uint8_t*)msg, strlen(msg));
  if (bleConnected) statusChar.notify((const uint8_t*)msg, strlen(msg));
}

void bleSendAlert(const char* msg) {
  alertChar.write((const uint8_t*)msg, strlen(msg));
  if (bleConnected) alertChar.notify((const uint8_t*)msg, strlen(msg));
}

void bleSendSystemPacket() {
  char buf[32];

  snprintf(
    buf,
    sizeof(buf),
    "S,%s,%.1f,%.0f,%.0f,%s,%u,%s",
    modeToText(systemMode),
    imuG,
    imuTiltDeg,
    imuImpact * 10.0f,
    fallStageToText(fallStage),
    getAlertFlag(),
    triggerToText(triggerSource)
  );

  imuChar.write((const uint8_t*)buf, strlen(buf));
  if (bleConnected) imuChar.notify((const uint8_t*)buf, strlen(buf));
}

void updateBleState() {
  bleSendAlert(isAlertMode() ? "1" : "0");

  switch (systemMode) {
    case MODE_NORMAL:
      bleSendStatus(imuOk ? "OK" : "FAIL");
      break;
    case MODE_PRE_ALERT:
      bleSendStatus("PRE");
      break;
    case MODE_CONFIRMED:
      bleSendStatus("EMG");
      break;
    case MODE_CANCELED:
      bleSendStatus("CAN");
      break;
  }
}

// =====================================================
// DFPLAYER HELPERS
// =====================================================
void initDFPlayer() {
  Serial1.begin(9600);
  delay(1500);

  logAudio("Inicializando DFPlayer...");

  if (dfPlayer.begin(Serial1, true, true)) {
    dfPlayerOk = true;
    dfPlayer.volume(30);
    dfPlayer.outputDevice(DFPLAYER_DEVICE_SD);
    logInit("DFPLAYER OK");
  } else {
    dfPlayerOk = false;
    logInit("DFPLAYER FAIL");
  }
}

void playMp3Track(uint16_t trackNumber) {
  if (!dfPlayerOk) {
    logAudio("DFPlayer no disponible");
    return;
  }

  dfPlayer.playMp3Folder(trackNumber);

  Serial.print("[AUD ] Play /mp3/");
  if (trackNumber < 10) Serial.print("000");
  else if (trackNumber < 100) Serial.print("00");
  else if (trackNumber < 1000) Serial.print("0");
  Serial.print(trackNumber);
  Serial.println(".mp3");
}

void stopAudio() {
  if (!dfPlayerOk) return;
  dfPlayer.stop();
  logAudio("Stop");
}

// =====================================================
// SYSTEM FLOW HELPERS
// =====================================================
void resetFallDetector() {
  fallStage = FALL_IDLE;
  fallStageStartMs = 0;
  tiltHoldActive = false;
  tiltHoldStartMs = 0;
  lastFallResetMs = millis();
}

void enterNormalMode() {
  systemMode = MODE_NORMAL;
  introPlaying = false;
  countdownActive = false;
  cancelCooldownActive = false;
  helpHits = 0;
  triggerSource = TRIGGER_NONE;
  resetFallDetector();
  updateBleState();
  logSys("Modo NORMAL");
}

void enterCanceledMode() {
  systemMode = MODE_CANCELED;
  introPlaying = false;
  countdownActive = false;
  helpHits = 0;

  cancelCooldownActive = true;
  cancelCooldownStartMs = millis();

  updateBleState();
  playMp3Track(13);
  logSys("Alerta cancelada");
}

void enterConfirmedMode() {
  systemMode = MODE_CONFIRMED;
  introPlaying = false;
  countdownActive = false;
  updateBleState();
  playMp3Track(11);
  logSys("Emergencia confirmada");
}

void startPreAlert() {
  if (systemMode == MODE_PRE_ALERT || systemMode == MODE_CONFIRMED) return;
  if (cancelCooldownActive) return;

  systemMode = MODE_PRE_ALERT;
  introPlaying = true;
  countdownActive = false;
  introStartMs = millis();
  lastCountdownSecond = 255;

  updateBleState();
  playMp3Track(1);

  logBle("Alert state = 1");
  logSys("Protocolo de emergencia iniciado");
}

void startPreAlertByVoice() {
  triggerSource = TRIGGER_VOICE;
  logML("AYUDA DETECTADA");
  startPreAlert();
}

void startPreAlertByFall() {
  triggerSource = TRIGGER_FALL;
  logFall("Caida confirmada -> inicia pre-alerta");
  startPreAlert();
}

void cancelAlert() {
  if (systemMode == MODE_NORMAL) return;
  stopAudio();
  enterCanceledMode();
}

// =====================================================
// COMMAND RX
// =====================================================
void command_write_callback(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  (void)conn_hdl;
  (void)chr;

  if (len == 0) return;

  char cmd[32];
  uint16_t copyLen = (len < sizeof(cmd) - 1) ? len : (sizeof(cmd) - 1);
  memcpy(cmd, data, copyLen);
  cmd[copyLen] = '\0';

  Serial.print("[CMD ] RX: ");
  Serial.println(cmd);

  if (
    strcmp(cmd, "CANCEL") == 0 ||
    strcmp(cmd, "ALERT_OFF") == 0 ||
    strcmp(cmd, "STOP") == 0
  ) {
    cancelAlert();
    return;
  }

  if (
    strcmp(cmd, "VOICE") == 0 ||
    strcmp(cmd, "HELP") == 0 ||
    strcmp(cmd, "ALERT_ON") == 0
  ) {
    startPreAlertByVoice();
    return;
  }

  if (strcmp(cmd, "FALL") == 0) {
    startPreAlertByFall();
    return;
  }

  if (strcmp(cmd, "NORMAL") == 0) {
    stopAudio();
    enterNormalMode();
    return;
  }
}

// =====================================================
// BUTTON TASK
// =====================================================
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
        Serial.println("[BTN ] Cancel button pressed");
        cancelAlert();
      }
    }
  }
}

// =====================================================
// EDGE IMPULSE HELPERS
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
  ei_printf("Inferencing settings:\n");
  ei_printf("\tInterval: %.2f ms.\n", (float)EI_CLASSIFIER_INTERVAL_MS);
  ei_printf("\tFrame size: %d\n", EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE);
  ei_printf("\tSample length: %d ms.\n", EI_CLASSIFIER_RAW_SAMPLE_COUNT / 16);
  ei_printf("\tNo. of classes: %d\n", EI_CLASSIFIER_LABEL_COUNT);

  for (size_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
    Serial.print("[ML  ] label ");
    Serial.print(i);
    Serial.print(": ");
    Serial.println(ei_classifier_inferencing_categories[i]);
  }

  if (!microphone_inference_start(EI_CLASSIFIER_RAW_SAMPLE_COUNT)) {
    logInit("ML MIC FAIL");
    return false;
  }

  int helpIndex = getHelpIndex();
  if (helpIndex < 0) {
    logML("Etiqueta ayuda no encontrada");
  } else {
    Serial.print("[ML  ] helpIndex=");
    Serial.println(helpIndex);
  }

  logInit("ML MIC OK");
  return true;
}

// =====================================================
// INIT
// =====================================================
void initIMU() {
  Wire.begin();

  if (myIMU.begin() == 0) {
    imuOk = true;
    logInit("IMU OK");
  } else {
    imuOk = false;
    logInit("IMU FAIL");
  }
}

void initBLE() {
  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName(DEVICE_NAME);

  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);

  sosService.begin();

  statusChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  statusChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  statusChar.setMaxLen(20);
  statusChar.begin();

  imuChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  imuChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  imuChar.setMaxLen(32);
  imuChar.begin();

  alertChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  alertChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  alertChar.setMaxLen(8);
  alertChar.begin();

  commandChar.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  commandChar.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  commandChar.setMaxLen(20);
  commandChar.setWriteCallback(command_write_callback);
  commandChar.begin();

  updateBleState();

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(sosService);
  Bluefruit.ScanResponse.addName();

  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);

  logInit("BLE OK");
  logBle("Advertising...");
}

// =====================================================
// TASKS
// =====================================================
void taskHeartbeat() {
  static uint32_t lastHeartbeatMs = 0;
  if (millis() - lastHeartbeatMs < 1000) return;
  lastHeartbeatMs = millis();
  Serial.println("[ALIVE]");
}

void taskIMURead() {
  if (!imuOk) return;
  if (millis() - lastImuReadMs < 100) return;

  lastImuReadMs = millis();

  imuAx = myIMU.readFloatAccelX();
  imuAy = myIMU.readFloatAccelY();
  imuAz = myIMU.readFloatAccelZ();

  updateFilteredImu(imuAx, imuAy, imuAz);
  updateImuMetrics();
}

void taskIMUSerial() {
  if (!imuOk) return;
  if (millis() - lastImuSerialMs < 800) return;

  lastImuSerialMs = millis();
  logImuMetrics(imuG, imuTiltDeg, imuImpact);
}

void taskBLEImu() {
  if (!imuOk) return;
  if (millis() - lastBleImuMs < 400) return;

  lastBleImuMs = millis();
  bleSendSystemPacket();
}

void taskBLEStatus() {
  if (millis() - lastBleStatusMs < 1000) return;

  lastBleStatusMs = millis();
  updateBleState();
}

void taskSystemLog() {
  if (millis() - lastSystemLogMs < 2000) return;

  lastSystemLogMs = millis();

  Serial.print("[SYS ] MODE=");
  Serial.print(modeToText(systemMode));
  Serial.print(" IMU=");
  Serial.print(imuOk ? "OK" : "FAIL");
  Serial.print(" BLE=");
  Serial.print(bleConnected ? "CONNECTED" : "DISCONNECTED");
  Serial.print(" INTRO=");
  Serial.print(introPlaying ? "ON" : "OFF");
  Serial.print(" COUNTDOWN=");
  Serial.print(countdownActive ? "ON" : "OFF");
  Serial.print(" COOLDOWN=");
  Serial.print(cancelCooldownActive ? "ON" : "OFF");
  Serial.print(" FALL=");
  Serial.print(fallStageToText(fallStage));
  Serial.print(" TRIGGER=");
  Serial.print(triggerToText(triggerSource));
  Serial.print(" G=");
  Serial.print(imuG, 2);
  Serial.print(" T=");
  Serial.println(imuTiltDeg, 1);
}

void taskFallDetection() {
  if (!imuOk) return;
  if (!fallEnabled) return;

  if (systemMode != MODE_NORMAL || cancelCooldownActive) {
    resetFallDetector();
    return;
  }

  uint32_t now = millis();

  if (now - lastFallTriggerMs < FALL_EVENT_COOLDOWN_MS) return;
  if (now - lastFallResetMs < FALL_MIN_REARM_MS) return;

  switch (fallStage) {
    case FALL_IDLE:
      if (imuImpact >= FALL_IMPACT_THRESHOLD_G) {
        fallStage = FALL_MONITORING;
        fallStageStartMs = now;
        tiltHoldActive = false;

        Serial.print("[FALL] Impacto detectado | G=");
        Serial.print(imuG, 2);
        Serial.print(" Tilt=");
        Serial.print(imuTiltDeg, 1);
        Serial.print(" Impact=");
        Serial.println(imuImpact, 2);
      }
      break;

    case FALL_MONITORING:
      if ((now - fallStageStartMs) > FALL_CONFIRM_WINDOW_MS) {
        logFall("Falso positivo");
        resetFallDetector();
        return;
      }

      if (imuTiltDeg >= FALL_TILT_THRESHOLD_DEG) {
        if (!tiltHoldActive) {
          tiltHoldActive = true;
          tiltHoldStartMs = now;
        }

        if ((now - tiltHoldStartMs) >= FALL_TILT_HOLD_MS) {
          Serial.print("[FALL] CONFIRMADA | G=");
          Serial.print(imuG, 2);
          Serial.print(" Tilt=");
          Serial.print(imuTiltDeg, 1);
          Serial.print(" Impact=");
          Serial.println(imuImpact, 2);

          lastFallTriggerMs = now;
          resetFallDetector();
          startPreAlertByFall();
        }
      } else {
        tiltHoldActive = false;
      }
      break;
  }
}

void taskIntroAudio() {
  if (!introPlaying) return;
  if (systemMode != MODE_PRE_ALERT) return;

  if (millis() - introStartMs >= INTRO_AUDIO_MS) {
    introPlaying = false;
    countdownActive = true;
    countdownStartMs = millis();
    lastCountdownSecond = 11;
    logSys("Inicio conteo 10 a 1");
  }
}

void taskCountdown() {
  if (!countdownActive) return;
  if (systemMode != MODE_PRE_ALERT) return;

  uint32_t now = millis();
  uint32_t elapsed = now - countdownStartMs;

  if (elapsed >= COUNTDOWN_MS) {
    enterConfirmedMode();
    return;
  }

  uint32_t remainingMs = COUNTDOWN_MS - elapsed;
  uint8_t remainingSec = (remainingMs + 999) / 1000;

  if (remainingSec != lastCountdownSecond) {
    lastCountdownSecond = remainingSec;

    Serial.print("[CNT ] ");
    Serial.print(remainingSec);
    Serial.println(" s");

    switch (remainingSec) {
      case 10: playMp3Track(10); break;
      case 9:  playMp3Track(9);  break;
      case 8:  playMp3Track(8);  break;
      case 7:  playMp3Track(7);  break;
      case 6:  playMp3Track(6);  break;
      case 5:  playMp3Track(5);  break;
      case 4:  playMp3Track(4);  break;
      case 3:  playMp3Track(3);  break;
      case 2:  playMp3Track(2);  break;
      case 1:  playMp3Track(12); break;
      default: break;
    }
  }
}

void taskCancelCooldown() {
  if (!cancelCooldownActive) return;

  if (millis() - cancelCooldownStartMs >= CANCEL_COOLDOWN_MS) {
    cancelCooldownActive = false;
    enterNormalMode();
    logSys("Fin cooldown cancelacion");
  }
}

void taskVoiceML() {
  if (!mlReady) return;

  if (systemMode == MODE_PRE_ALERT ||
      systemMode == MODE_CONFIRMED ||
      systemMode == MODE_CANCELED ||
      cancelCooldownActive) {
    return;
  }

  if (inference.buf_ready == 0) return;

  signal_t signal;
  signal.total_length = EI_CLASSIFIER_RAW_SAMPLE_COUNT;
  signal.get_data = &microphone_audio_signal_get_data;

  ei_impulse_result_t result = { 0 };

  EI_IMPULSE_ERROR r = run_classifier(&signal, &result, debug_nn);
  if (r != EI_IMPULSE_OK) {
    Serial.print("[ML  ] ERR classifier=");
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
      if (result.classification[i].value > maxOther) {
        maxOther = result.classification[i].value;
      }
    }

    lastHelpScore = ayudaScore;

    Serial.print("[ML  ] AYUDA=");
    Serial.print(ayudaScore, 5);
    Serial.print(" OTHER_MAX=");
    Serial.println(maxOther, 5);

    if (ayudaScore >= HELP_THRESHOLD && ayudaScore > maxOther) {
      if (helpHits < 255) helpHits++;
    } else {
      helpHits = 0;
    }

    if (helpHits >= HELP_MIN_HITS) {
      startPreAlertByVoice();
      helpHits = 0;
    }
  }

  inference.buf_ready = 0;
  inference.buf_count = 0;
}

// =====================================================
// SETUP
// =====================================================
void setup() {
  Serial.begin(115200);

  uint32_t serialWaitStart = millis();
  while (!Serial && (millis() - serialWaitStart < 3000)) {
  }

  pinMode(CANCEL_BUTTON_PIN, INPUT_PULLUP);

  logBoot("SOS_Biker PROTOCOL");

  initIMU();
  initBLE();
  initDFPlayer();
  mlReady = initML();

  enterNormalMode();
  logSys("System ready");
}

// =====================================================
// LOOP
// =====================================================
void loop() {
  taskHeartbeat();
  taskIMURead();
  taskIMUSerial();
  taskBLEImu();
  taskBLEStatus();
  taskSystemLog();
  taskCancelButton();
  taskFallDetection();
  taskIntroAudio();
  taskCountdown();
  taskCancelCooldown();
  taskVoiceML();

  if (Serial.available()) {
    char c = Serial.read();

    if (c == '1') {
      startPreAlertByVoice();
    } else if (c == '0') {
      stopAudio();
      enterNormalMode();
    } else if (c == 'p' || c == 'P') {
      playMp3Track(1);
    } else if (c == 'c' || c == 'C') {
      cancelAlert();
    } else if (c == 'f' || c == 'F') {
      enterConfirmedMode();
    } else if (c == 'd' || c == 'D') {
      playMp3Track(10);
    } else if (c == 'n' || c == 'N') {
      playMp3Track(9);
    } else if (c == 'u' || c == 'U') {
      playMp3Track(12);
    } else if (c == 'i' || c == 'I') {
      logImuMetrics(imuG, imuTiltDeg, imuImpact);
    } else if (c == 'k' || c == 'K') {
      logFall("Simulada por serial");
      startPreAlertByFall();
    }
  }
}

#if !defined(EI_CLASSIFIER_SENSOR) || EI_CLASSIFIER_SENSOR != EI_CLASSIFIER_SENSOR_MICROPHONE
#error "Invalid model for current sensor."
#endif
