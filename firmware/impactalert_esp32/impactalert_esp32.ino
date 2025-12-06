#include <WiFi.h>
#include <WebServer.h>
#include "secrets.h"

const char* ssid = WIFI_SSID;
const char* password = WIFI_PASSWORD;

bool emergencyActive = false;
bool powerOn = false;

WebServer server(80);

// ----------------------------------
// CANCELAR EMERGENCIA
// ----------------------------------
void handleCancel() {
  Serial.println("Comando recibido: CANCELAR EMERGENCIA");
  emergencyActive = false;

  String json = "{ \"status\": \"canceled\", \"emergency\": false }";

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}

// ----------------------------------
// STATUS (ÚNICA VERSIÓN CORRECTA)
// ----------------------------------
void handleStatus() {
  String json = "{";
  json += "\"connected\": true, ";
  json += "\"emergency\": " + String(emergencyActive ? "true" : "false") + ",";
  json += "\"power\": " + String(powerOn ? "true" : "false");
  json += "}";

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}

// ----------------------------------
// ENCENDER
// ----------------------------------
void handleEncender() {
  Serial.println("Comando recibido: ENCENDER");
  powerOn = true;

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", "{\"status\":\"OK\",\"action\":\"encender\"}");
}

// ----------------------------------
// APAGAR
// ----------------------------------
void handleApagar() {
  Serial.println("Comando recibido: APAGAR");
  powerOn = false;

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", "{\"status\":\"OK\",\"action\":\"apagar\"}");
}

// ----------------------------------
// LEER SENSOR (simulado)
// ----------------------------------
void handleLeer() {
  float temp = 20 + random(0, 100) / 10.0;

  String json = "{\"temp\": " + String(temp, 2) + "}";
  Serial.println("Enviando datos simulados: " + json);

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}

// ----------------------------------
// TRIGGER MANUAL DE EMERGENCIA
// ----------------------------------
void handleTriggerEmergency() {
  Serial.println("Emergencia ACTIVADA manualmente");
  emergencyActive = true;

  String json = "{ \"status\": \"emergency_on\", \"emergency\": true }";

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}

// ----------------------------------
// SETUP
// ----------------------------------
void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, password);
  Serial.print("Conectando a WiFi");

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println("\nWiFi conectado!");
  Serial.println(WiFi.localIP());

  server.on("/encender", handleEncender);
  server.on("/apagar", handleApagar);
  server.on("/status", handleStatus);
  server.on("/leer", handleLeer);
  server.on("/cancel", handleCancel);
  server.on("/trigger", handleTriggerEmergency);

  server.begin();
  Serial.println("Servidor iniciado!");
}

void loop() {
  server.handleClient();
}
