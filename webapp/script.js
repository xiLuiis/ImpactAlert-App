// ===============================================
//  GLOBAL STATE (COMPARTIDO ENTRE TODAS LAS PÁGINAS)
// ===============================================
let emergencyActive = false;
let lastEmergencyState = false;

// ===============================================
//  UI: ACTUALIZAR BOTÓN PRINCIPAL
// ===============================================
function updateSystemStatus(connected, emergency) {
    const statusBtn = document.getElementById("main-btn");

    if (!statusBtn) return; // algunas páginas no tienen el botón

    if (!connected) {
        statusBtn.style.background = "#999";
        statusBtn.textContent = "NO CONNECTION";
        return;
    }

    if (emergency) {
        statusBtn.style.background = "#d93d33";
        statusBtn.textContent = "CANCEL EMERGENCY";
    } else {
        statusBtn.style.background = "#2ecc71";
        statusBtn.textContent = "SYSTEM NORMAL";
    }
}

// ===============================================
//  POLLING AL ESP32 (CADA PÁGINA LO HACE)
// ===============================================
function checkConnection() {
    fetch("http://192.168.100.29/status")
        .then(res => res.json())
        .then(data => {
            // Estado actual del ESP32
            emergencyActive = data.emergency;

            // Mostrar estado de MCU si existe en la página
            const mcuStatus = document.getElementById("mcu-status");
            if (mcuStatus) {
                mcuStatus.textContent = data.connected
                    ? "MCU Connected"
                    : "MCU Offline";
            }

            // Si emergencia ACTIVADA y no existe timestamp → crearlo
            if (data.emergency && !localStorage.getItem("emergencyStart")) {
                localStorage.setItem("emergencyStart", Date.now());
            }

            // Si emergencia DESACTIVADA → borrar timestamp
            if (!data.emergency) {
                localStorage.removeItem("emergencyStart");
            }

            updateSystemStatus(true, data.emergency);
        })
        .catch(() => {
            // Sin conexión
            updateSystemStatus(false, false);

            const mcuStatus = document.getElementById("mcu-status");
            if (mcuStatus) mcuStatus.textContent = "MCU Offline";
        });
}

// ===============================================
//  CONTADOR GLOBAL (BASADO EN TIMESTAMP)
// ===============================================
function updateCountdown() {
    const box = document.getElementById("countdown-box");
    const text = document.getElementById("countdown-text");

    if (!box || !text) return; // esta página no tiene contador

    const ts = localStorage.getItem("emergencyStart");

    if (!ts) {
        box.classList.add("hidden");
        return;
    }

    box.classList.remove("hidden");

    const diff = (Date.now() - ts) / 1000;
    const remaining = 8 - diff;

    if (remaining > 0) {
        text.textContent = `Calling in ${Math.ceil(remaining)}...`;
    } else {
        text.textContent = "Llamando...";
    }
}

// ===============================================
//  CANCELACIÓN DE EMERGENCIA (ESP32 + UI GLOBAL)
// ===============================================
function cancelEmergency() {
    if (!emergencyActive) return;

    fetch("http://192.168.100.29/cancel")
        .then(() => {
            localStorage.removeItem("emergencyStart");
            emergencyActive = false;
            updateSystemStatus(true, false);
        })
        .catch(() => alert("No connection with the device"));
}

// ===============================================
//  LLAMAR AL 911 (POR AHORA SOLO SIMULADO)
// ===============================================
function call911() {
    alert("Calling 911...");
}

// ===============================================
//  LOOP GLOBAL PARA TODAS LAS PÁGINAS
// ===============================================
setInterval(() => {
    checkConnection();
    updateCountdown();
}, 600);

// Primera ejecución inmediata
checkConnection();
updateCountdown();
