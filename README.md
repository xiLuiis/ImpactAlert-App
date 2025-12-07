# ImpactAlert-App

Sistema IoT basado en ESP32 para la detección de impactos y activación de emergencias en motocicletas u otros vehículos personales.
El proyecto integra sensores inerciales como el MPU6050, conectividad WiFi/Bluetooth, y una WebApp ligera para monitoreo y control en tiempo real.



Incluye:

* Firmware para ESP32 con API REST en JSON
* WebApp móvil para mostrar el estado del sistema y cancelar alertas
* Simulación y lectura de eventos de impacto mediante IMU
* Comunicación en tiempo real por WiFi para consultar status y enviar comandos

Arquitectura lista para incorporar:

* detección real de caídas/accidentes
* thresholds configurables
* envío de alertas a contactos
* llamadas o notificaciones desde el dispositivo del usuario

El sistema está diseñado como una base modular para proyectos de seguridad vehicular, asistencia en accidentes y monitoreo preventivo, permitiendo a otros desarrolladores adaptar o expandir la funcionalidad según sus necesidades.

