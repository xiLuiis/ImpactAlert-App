let emergencyActive = false;
let deviceOn = false;
let lastEmergencyState = false;
let countdownTimer = null;
let countdownActive = false;
let connectionFails = 0;
// ------------------------------
// TIMEOUT REAL (AbortController)
// ------------------------------
function fetchWithTimeout(url, timeout = 1900) {
    const controller = new AbortController();
    const signal = controller.signal;

    const timer = setTimeout(() => {
        controller.abort();
    }, timeout);

    return fetch(url, { signal })
        .finally(() => clearTimeout(timer));
}

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
// 2. CONSULTAR AL ESP32 (con timeout)
// ------------------------------
function checkConnection() {
    fetchWithTimeout("http://192.168.100.29/status", 1800)
        .then(res => {
            if (!res.ok) throw new Error("bad http");
            return res.json();
        })
        .then(data => {

            connectionFails = 0;  // ← resetea fallos

            emergencyActive = data.emergency;
            updateSystemStatus(true, emergencyActive);
            document.getElementById("mcu-status").textContent = "MCU Connected";

            // ---- CAMBIO DE ESTADO ----
            if (data.emergency !== lastEmergencyState) {
                if (data.emergency) {
                    startCountdown();
                } else {
                    stopCountdown();
                }
            }

            lastEmergencyState = data.emergency;
        })
        .catch(() => {

            connectionFails++;

            if (connectionFails >= 2) {
                updateSystemStatus(false, false);
                document.getElementById("mcu-status").textContent = "MCU Offline";
            }
        });
}

// ------------------------------
// 3. 911 CALL
// ------------------------------
function startCountdown() {
    if (countdownActive) return; // evita reinicios dobles
    countdownActive = true;

    const box = document.getElementById("countdown-box");
    const text = document.getElementById("countdown-text");

    box.classList.remove("hidden");

    let seconds = 3;

    text.textContent = `Calling in ${seconds}...`;

    countdownTimer = setInterval(() => {
        seconds--;

        if (seconds > 0) {
            text.textContent = `Calling in ${seconds}...`;
        } else {
            clearInterval(countdownTimer);
            text.textContent = "Llamando...";
            countdownActive = false;
        }

    }, 1000);
}

// Oculta la cuenta regresiva y cancela timers
function stopCountdown() {
    const box = document.getElementById("countdown-box");
    box.classList.add("hidden");

    if (countdownTimer) {
        clearInterval(countdownTimer);
        countdownTimer = null;
    }

    countdownActive = false;
}

// Consulta cada 1 segundos
setInterval(checkConnection, 1000);
checkConnection();

// ------------------------------
// 3. BOTONES
// ------------------------------
function cancelEmergency() {
    if (!emergencyActive) {
        console.log("SYSTEM NORMAL presionado.");
        return;
    }

    fetch("http://192.168.100.29/cancel")
        .then(() => {
            console.log("Cancel sent");
            emergencyActive = false;
            updateSystemStatus(true, false);
            stopCountdown();
        })
        .catch(() => alert("No connection with the device"));
}

function call911() {
    console.log("CALL 911 triggered");
    alert("Calling 911...");
}
