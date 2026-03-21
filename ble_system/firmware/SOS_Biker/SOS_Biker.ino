#include <Arduino.h>
#include <ArduinoBLE.h>
#include <Wire.h>
#include "LSM6DS3.h"

static const char* BLE_NAME = "SOS_Biker_XIAO";

BLEService imuService("19B10000-E8F2-537E-4F6C-D104768A1214");

BLECharacteristic statusChar(
  "19B10001-E8F2-537E-4F6C-D104768A1214",
  BLERead | BLENotify,
  20
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

LSM6DS3 myIMU(I2C_MODE, 0x6A);

bool imuOk = false;
bool wasConnected = false;

// TIMERS
uint32_t lastSensorRead = 0;
uint32_t lastBleAccSend = 0;
uint32_t lastBleGyroSend = 0;
uint32_t lastStatusMsg = 0;
uint32_t lastDebugMsg = 0;

// DATA
float ax = 0, ay = 0, az = 0;
float gx = 0, gy = 0, gz = 0;

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
  BLE.addService(imuService);

  BLE.advertise();
  Serial.print("Advertising as: ");
  Serial.println(BLE_NAME);
}

void loop() {
  BLE.poll();

  BLEDevice central = BLE.central();

  // ===== DEBUG GENERAL CADA 1s =====
  if (millis() - lastDebugMsg >= 1000) {
    lastDebugMsg = millis();

    Serial.print("[DEBUG] imuOk=");
    Serial.print(imuOk ? "true" : "false");
    Serial.print(" | wasConnected=");
    Serial.print(wasConnected ? "true" : "false");
    Serial.print(" | central=");
    Serial.print(central ? "yes" : "no");

    if (central) {
      Serial.print(" | connected=");
      Serial.print(central.connected() ? "true" : "false");
      Serial.print(" | addr=");
      Serial.print(central.address());
    }

    Serial.println();
  }

  // ===== CONEXION =====
  if (central && !wasConnected) {
    wasConnected = true;
    Serial.println("Central detected -> writing CONNECTED");
    statusChar.writeValue("CONNECTED");
  }

  // ===== LECTURA SIEMPRE =====
  if (imuOk && millis() - lastSensorRead >= 200) {
    lastSensorRead = millis();

    ax = myIMU.readFloatAccelX();
    ay = myIMU.readFloatAccelY();
    az = myIMU.readFloatAccelZ();

    gx = myIMU.readFloatGyroX();
    gy = myIMU.readFloatGyroY();
    gz = myIMU.readFloatGyroZ();

    Serial.print("ACC: ");
    Serial.print(ax, 2);
    Serial.print(", ");
    Serial.print(ay, 2);
    Serial.print(", ");
    Serial.print(az, 2);

    Serial.print(" | GYRO: ");
    Serial.print(gx, 2);
    Serial.print(", ");
    Serial.print(gy, 2);
    Serial.print(", ");
    Serial.println(gz, 2);
  }

  // ===== ENVIO BLE =====
  if (central && central.connected()) {

    // ACC
    if (millis() - lastBleAccSend >= 100) {
      lastBleAccSend = millis();

      String accMsg = String(ax, 1) + "," + String(ay, 1) + "," + String(az, 1);
      bool ok = accChar.writeValue(accMsg.c_str());

      Serial.print("BLE ACC -> ");
      Serial.print(accMsg);
      Serial.print(" | write ok = ");
      Serial.println(ok ? "true" : "false");
    }

    // GYRO
    if (millis() - lastBleGyroSend >= 100) {
      lastBleGyroSend = millis();

      String gyroMsg = String(gx, 1) + "," + String(gy, 1) + "," + String(gz, 1);
      bool ok = gyroChar.writeValue(gyroMsg.c_str());

      Serial.print("BLE GYRO -> ");
      Serial.print(gyroMsg);
      Serial.print(" | write ok = ");
      Serial.println(ok ? "true" : "false");
    }

    // STATUS
    if (millis() - lastStatusMsg >= 300) {
      lastStatusMsg = millis();

      const char* statusMsg = imuOk ? "IMU_OK" : "IMU_FAIL";
      bool ok = statusChar.writeValue(statusMsg);

      Serial.print("BLE STATUS -> ");
      Serial.print(statusMsg);
      Serial.print(" | write ok = ");
      Serial.println(ok ? "true" : "false");
    }
  } else {
    // Esto ayuda a comprobar si el problema es que nunca entra a connected()
    static uint32_t lastNoConnMsg = 0;
    if (millis() - lastNoConnMsg >= 1000) {
      lastNoConnMsg = millis();
      Serial.println("BLE not connected yet -> no ACC/GYRO notify sent");
    }
  }

  // ===== DESCONEXION =====
  if (wasConnected && (!central || !central.connected())) {
    wasConnected = false;
    Serial.println("Central disconnected -> advertising again");
    BLE.advertise();
  }
}