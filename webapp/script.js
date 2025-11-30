let emergencyActive = false;

// ------------------------------
// 1. ESTADO DEL SISTEMA
// ------------------------------
function updateSystemStatus(connected, emergency) {
    const statusBtn = document.getElementById("main-btn");

    if (!connected) {
        statusBtn.style.background = "#999";
        statusBtn.textContent = "NO CONNECTION";
        return;
    }

    if (emergency) {
        statusBtn.style.background = "#d93d33"; // rojo
        statusBtn.textContent = "CANCEL EMERGENCY";
    } else {
        statusBtn.style.background = "#2ecc71"; // verde
        statusBtn.textContent = "SYSTEM NORMAL";
    }
}

// ------------------------------
// 2. CONSULTAR AL ESP32
// ------------------------------
function checkConnection() {
    fetch("http://192.168.100.29/status")
        .then(res => res.json())
        .then(data => {
            emergencyActive = data.emergency;
            updateSystemStatus(true, emergencyActive);
            document.getElementById("mcu-status").textContent = "MCU Connected";
        })
        .catch(() => {
            updateSystemStatus(false, false);
            document.getElementById("mcu-status").textContent = "MCU Offline";
        });
}

// Consulta cada 2 segundos
setInterval(checkConnection, 2000);
checkConnection();

// ------------------------------
// 3. BOTONES
// ------------------------------
function cancelEmergency() {
    if (!emergencyActive) {
        console.log("SYSTEM NORMAL presionado.");
        return;
    }

    // Emergencia activa → sí enviar comando
    fetch("http://192.168.100.29/cancel")
        .then(() => {
            console.log("Cancel sent");
            emergencyActive = false; // Actualizar estado local
            updateSystemStatus(true, false);
        })
        .catch(() => alert("No connection with the device"));
}

function call911() {
    console.log("CALL 911 triggered");
    alert("Calling 911...");
}
