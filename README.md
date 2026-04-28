# SOS Biker

Sistema IoT para detección de impactos y asistencia en emergencias, orientado a motociclistas y usuarios de vehículos personales.

El proyecto evoluciona desde un prototipo basado en WiFi (HTTP + WebApp) hacia un sistema más robusto basado en Bluetooth Low Energy (BLE) con integración a una aplicación Android.

---

## 🚀 Descripción

ImpactAlert es un sistema embebido que detecta posibles accidentes mediante sensores inerciales (acelerómetro y giroscopio) y genera alertas automáticas cuando se identifica una situación de riesgo.

El sistema está diseñado para reducir el tiempo de respuesta en emergencias, especialmente en casos donde el usuario no puede solicitar ayuda manualmente.

---

## 🧱 Arquitectura del proyecto

El repositorio está dividido en dos enfoques principales:

### 🔹 http_prototype/

Prototipo inicial basado en ESP32 y comunicación WiFi.

Incluye:

* Firmware con API REST (JSON)
* WebApp ligera para monitoreo y control
* Simulación de eventos de impacto
* Comunicación en tiempo real vía HTTP

Este módulo sirvió como base para validar:

* flujo de datos
* lógica de detección
* interacción usuario-sistema

---

### 🔹 ble_system/

Sistema actual basado en BLE y dispositivo dedicado.

Incluye:

* Firmware para XIAO nRF52840 (IMU + BLE)
* Aplicación Android en Kotlin
* Comunicación en tiempo real vía Bluetooth Low Energy
* Base para detección de impacto con sensores reales

Este enfoque permite:

* menor latencia
* menor consumo energético
* operación independiente del uso activo del celular

---

## ⚙️ Funcionalidades

* Lectura de datos inerciales (aceleración y rotación)
* Detección de eventos de impacto (en desarrollo)
* Comunicación en tiempo real (HTTP / BLE)
* Interfaz de usuario para monitoreo y control
* Cancelación manual de alertas

---

## 🔧 Próximos pasos

* Implementación de lógica robusta de crash detection
* Configuración de thresholds dinámicos
* Sistema de cuenta regresiva y cancelación
* Integración con envío de alertas (SMS / llamada)
* Obtención de ubicación mediante el celular
* Mejora de estabilidad en conexión BLE

---

## 🧠 Enfoque del sistema

Aunque los teléfonos inteligentes ya incluyen sensores, este proyecto utiliza un dispositivo dedicado para:

* obtener mediciones más confiables (posición fija en vehículo o casco)
* ejecutar monitoreo continuo
* reducir dependencia del estado del celular
* permitir interacción física (botón, buzzer, LEDs)

El celular actúa como:

* interfaz de usuario
* canal de comunicación
* proveedor de ubicación y notificaciones

---

## 🎯 Objetivo

Construir una plataforma modular y escalable para sistemas de seguridad en movilidad personal, que pueda evolucionar hacia un producto real con capacidad de respuesta automática ante accidentes.

---

## 📌 Tecnologías

* ESP32 / XIAO nRF52840
* Sensores IMU (MPU6050 / LSM6DS3)
* Arduino / C++
* Kotlin (Android)
* Bluetooth Low Energy (BLE)
* HTTP / REST
* WebApp (HTML, CSS, JavaScript)

---

## 🧩 Estado del proyecto

🟡 En desarrollo activo
Transición de prototipo HTTP a sistema BLE con sensores reales.
