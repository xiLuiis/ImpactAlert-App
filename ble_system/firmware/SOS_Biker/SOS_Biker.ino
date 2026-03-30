#include <ArduinoBLE.h>
#include <Wire.h>
#include "LSM6DS3.h"
#include <PDM.h>
#include <math.h>

// IMU
LSM6DS3 myIMU(I2C_MODE, 0x6A);

// BLE
BLEService sosService("19B10000-E8F2-537E-4F6C-D104768A1214");
BLEStringCharacteristic sensorCharacteristic(
  "19B10001-E8F2-537E-4F6C-D104768A1214",
  BLERead | BLENotify,
  120
);

// Tiempo
unsigned long lastSample = 0;
const unsigned long sampleIntervalMs = 100;

// Micrófono
short sampleBuffer[256];
volatile int samplesRead = 0;
int micLevel = 0;

// Detección de audio
bool loudSoundDetected = false;
int micThreshold = 1200;
int audioCounter = 0;
const int audioTriggerCount = 3;

void onPDMdata() {
  int bytesAvailable = PDM.available();
  PDM.read(sampleBuffer, bytesAvailable);
  samplesRead = bytesAvailable / 2;
}

void setup() {
  Serial.begin(115200);
  while (!Serial) {
    delay(10);
  }

  Serial.println("Iniciando SOS Biker...");

  Wire.begin();

  // IMU
  if (myIMU.begin() != 0) {
    Serial.println("Error IMU");
    while (1) {
      delay(1000);
    }
  }
  Serial.println("IMU OK");

  // MIC
  PDM.onReceive(onPDMdata);
  if (!PDM.begin(1, 16000)) {
    Serial.println("Error MIC");
    while (1) {
      delay(1000);
    }
  }
  Serial.println("MIC OK");

  // BLE
  if (!BLE.begin()) {
    Serial.println("Error BLE");
    while (1) {
      delay(1000);
    }
  }

  BLE.setLocalName("SOS_Biker");
  BLE.setDeviceName("SOS_Biker");
  BLE.setAdvertisedService(sosService);

  sosService.addCharacteristic(sensorCharacteristic);
  BLE.addService(sosService);

  sensorCharacteristic.writeValue("Listo");
  BLE.advertise();

  Serial.println("BLE listo");
  Serial.println("Formato: ax,ay,az,gx,gy,gz,amag,mic,alerta");
}

void loop() {
  unsigned long now = millis();

  // Procesar audio siempre
  if (samplesRead) {
    long sum = 0;

    for (int i = 0; i < samplesRead; i++) {
      sum += abs(sampleBuffer[i]);
    }

    micLevel = sum / samplesRead;
    samplesRead = 0;
  }

  // Detección estable de sonido fuerte
  if (micLevel > micThreshold) {
    audioCounter++;
  } else {
    audioCounter = 0;
  }

  loudSoundDetected = (audioCounter >= audioTriggerCount);

  // Lectura periódica
  if (now - lastSample >= sampleIntervalMs) {
    lastSample = now;

    float ax = myIMU.readFloatAccelX();
    float ay = myIMU.readFloatAccelY();
    float az = myIMU.readFloatAccelZ();

    float gx = myIMU.readFloatGyroX();
    float gy = myIMU.readFloatGyroY();
    float gz = myIMU.readFloatGyroZ();

    float amag = sqrt(ax * ax + ay * ay + az * az);

    // Debug serial
    Serial.print(ax, 3); Serial.print(",");
    Serial.print(ay, 3); Serial.print(",");
    Serial.print(az, 3); Serial.print(",");
    Serial.print(gx, 3); Serial.print(",");
    Serial.print(gy, 3); Serial.print(",");
    Serial.print(gz, 3); Serial.print(",");
    Serial.print(amag, 3); Serial.print(",");
    Serial.print(micLevel); Serial.print(",");
    Serial.println(loudSoundDetected ? "1" : "0");

    if (loudSoundDetected) {
      Serial.print("🚨 ALERTA AUDIO | micLevel = ");
      Serial.println(micLevel);
      sensorCharacteristic.writeValue("SOS,AUDIO=1");
    } else {
      String payload = String(ax, 3) + "," +
                       String(ay, 3) + "," +
                       String(az, 3) + "," +
                       String(gx, 3) + "," +
                       String(gy, 3) + "," +
                       String(gz, 3) + "," +
                       String(amag, 3) + "," +
                       String(micLevel) + "," +
                       String("0");

      sensorCharacteristic.writeValue(payload);
    }
  }

  BLE.poll();
}
