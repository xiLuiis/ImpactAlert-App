#include <WiFi.h>
#include <WebServer.h>

const char* ssid = "...";
const char* password = "a0919c310b";
bool emergencyActive = false;

WebServer server(80);

void handleCancel() {
  Serial.println("Comando recibido: CANCELAR EMERGENCIA");
  emergencyActive = false;

  String json = "{ \"status\": \"canceled\", \"emergency\": false }";

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}

void handleStatus() {
  String json = "{";
  json += "\"connected\": true, ";
  json += "\"emergency\": " + String(emergencyActive ? "true" : "false");
  json += "}";

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}
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

void handleEncender() {
  powerOn = true;
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", "{\"status\":\"OK\",\"action\":\"encender\"}");
}

void handleApagar() {
  powerOn = false;
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", "{\"status\":\"OK\",\"action\":\"apagar\"}");
}

void handleLeer() {
  float temp = 20 + random(0, 100) / 10.0;
  String json = "{\"temp\": " + String(temp, 2) + "}";
  Serial.println("Enviando datos simulados: " + json);
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}

void handleTriggerEmergency() {
  Serial.println("Emergencia ACTIVADA manualmente");
  emergencyActive = true;

  String json = "{ \"status\": \"emergency_on\", \"emergency\": true }";
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}

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
  server.on("/leer", handleLeer);
  server.on("/cancel", handleCancel);
  server.on("/status", handleStatus);
  server.on("/trigger", handleTriggerEmergency);

  server.begin();
  Serial.println("Servidor iniciado!");
}

void loop() {
  server.handleClient();
}
