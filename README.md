# SOS Biker / ImpactAlert-App

Sistema de detección de impactos para motociclistas, basado en un microcontrolador con IMU y una aplicación Android que monitorea eventos de posible caída o accidente mediante BLE.

El objetivo del proyecto es detectar un posible choque, evaluar si el usuario quedó inmóvil o presenta movimiento anormal después del impacto, y activar un mecanismo de emergencia con notificación, alerta local y envío de SMS a contactos registrados.

---

## Estado actual del proyecto

El proyecto cuenta con dos líneas principales:

- `http_prototype/` o versión anterior basada en ESP32, WiFi, API REST y WebApp.
- `ble_system/` o versión actual basada en Seeed XIAO nRF52840 Sense, BLE y aplicación Android nativa.

La versión más reciente se centra en el sistema BLE con Android.

---

## Funcionalidades principales

- Conexión BLE automática con el dispositivo `SOS_Biker_XIAO`.
- Lectura en tiempo real de acelerómetro y giroscopio.
- Detección de posible choque usando aceleración, cambios bruscos de movimiento y rotación.
- Máquina de estados para evaluar el evento antes de confirmar emergencia.
- Confirmación por falta de movimiento después del impacto.
- Confirmación por movimiento anormal después del impacto, como rodamiento, rebote o giro continuo.
- Activación de alerta local mediante comandos BLE hacia el microcontrolador.
- Cuenta regresiva antes de enviar la emergencia.
- Cancelación manual desde la app o desde la notificación.
- Envío automático de SMS a contactos habilitados.
- Inclusión opcional de ubicación GPS en el mensaje.
- Gestión de contactos de emergencia desde la app.
- Modo Debug para visualizar datos BLE y calibrar sensibilidad.

---

## Hardware utilizado

- Seeed XIAO nRF52840 Sense
- IMU integrada
- Buzzer
- LED indicador
- Botón físico de cancelación o control
- Batería LiPo
- Componentes auxiliares para montaje y pruebas

---

## Comunicación BLE

El microcontrolador transmite datos de sensores hacia la app Android mediante BLE.

Nombre esperado del dispositivo:

```text
SOS_Biker_XIAO
