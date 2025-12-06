let debugEnabled = false;
let sensorInterval = null;

/* ===============================
   LOAD CURRENT THRESHOLD
=============================== */
document.addEventListener("DOMContentLoaded", () => {
    const saved = localStorage.getItem("impactThreshold") || 30;

    document.getElementById("current-threshold").textContent = saved;
    document.getElementById("threshold-input").value = saved;

    document.getElementById("sensor-box").textContent = "DEBUG OFF";
});

/* ===============================
   SAVE THRESHOLD
=============================== */
function saveThreshold() {
    const val = document.getElementById("threshold-input").value.trim();

    if (!val) {
        alert("Please enter a valid value.");
        return;
    }

    localStorage.setItem("impactThreshold", val);

    document.getElementById("current-threshold").textContent = val;

    alert("Threshold saved!");
}

/* ===============================
   DEBUG MODE TOGGLE
=============================== */
function toggleDebug() {
    debugEnabled = document.getElementById("debug-toggle").checked;

    if (debugEnabled) {
        startSensorUpdates();
    } else {
        stopSensorUpdates();
        document.getElementById("sensor-box").textContent = "DEBUG OFF";
    }
}

/* ===============================
   SENSOR POLLING
=============================== */
function startSensorUpdates() {
    stopSensorUpdates(); // evitar duplicados

    sensorInterval = setInterval(() => {
        fetch("http://192.168.100.29/leer")
            .then(res => res.json())
            .then(data => {
                document.getElementById("sensor-box").textContent =
                    data.temp.toFixed(2);
            })
            .catch(() => {
                document.getElementById("sensor-box").textContent = "--";
            });
    }, 700);
}

function stopSensorUpdates() {
    if (sensorInterval) {
        clearInterval(sensorInterval);
        sensorInterval = null;
    }
}

/* ===============================
   BACK BUTTON
=============================== */
function goBack() {
    window.location.href = "index.html";
}
