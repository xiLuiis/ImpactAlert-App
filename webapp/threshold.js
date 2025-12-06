// ------------------------------
//  THRESHOLD STORAGE (PERSISTENTE)
// ------------------------------
let threshold = Number(localStorage.getItem("impactThreshold") || 30);

document.addEventListener("DOMContentLoaded", () => {

    // Mostrar el threshold actual
    document.getElementById("current-threshold").textContent = threshold;

    // Cargar el valor en el input
    document.getElementById("threshold-input").value = threshold;

    // Guardar threshold
    document.getElementById("save-btn").onclick = () => {
        threshold = Number(document.getElementById("threshold-input").value);

        // Guardar en memoria del navegador
        localStorage.setItem("impactThreshold", threshold);

        // Refrescar el texto
        document.getElementById("current-threshold").textContent = threshold;

        alert("Threshold saved: " + threshold);
    };

    // Botón Back
    document.getElementById("back-btn").onclick = () => {
        window.location.href = "index.html";
    };
});


// ------------------------------
//  SENSOR DEBUG
// ------------------------------
function readSensor() {
    fetch("http://192.168.100.29/leer", { cache: "no-store" })
        .then(res => res.json())
        .then(data => {
            document.getElementById("sensor-value").textContent =
                "Sensor: " + data.temp;
        })
        .catch(() => {
            document.getElementById("sensor-value").textContent =
                "Sensor: offline";
        });
}

setInterval(readSensor, 800);
readSensor();
