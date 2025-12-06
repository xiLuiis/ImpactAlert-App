// ---------------------------------------------
// GLOBAL STATE SHARED ACROSS ALL TABS
// ---------------------------------------------
const channel = new BroadcastChannel("impactalert_channel");

// Estado global compartido
let GLOBAL = {
    emergency: false,
    emergencyStart: null,
    connected: false,
    lastUpdate: 0
};

// Detectar si esta pestaña será la PESTAÑA MAESTRA
function isMasterTab() {
    return localStorage.getItem("masterTab") === "true";
}

// Intentar ser master si no hay una activa
if (!localStorage.getItem("masterTab")) {
    localStorage.setItem("masterTab", "true");
}

// Si otra pestaña manda "soy el master"
channel.onmessage = (e) => {
    if (e.data.type === "iAmMaster") {
        localStorage.setItem("masterTab", "false");
    }
};

// Si otra pestaña manda "soy el master"
channel.onmessage = (e) => {
    if (e.data.type === "stateUpdate") {
        GLOBAL = e.data.state;
        updateUIFromGlobal();
    }

    if (e.data.type === "iAmMaster") {
        localStorage.setItem("masterTab", "false");
    }
};

// Avisar a otras pestañas que existimos (para que haya solo 1 master)
channel.postMessage({ type: "iAmMaster" });

// ---------------------------------------------
// MASTER TAB: HACE POLLING
// ---------------------------------------------
function startMasterPolling() {
    setInterval(() => {
        fetch("http://192.168.100.29/status")
            .then(r => r.json())
            .then(data => {
                GLOBAL.connected = true;
                GLOBAL.emergency = data.emergency;

                // Si inicia emergencia, registrar timestamp si no existe
                if (data.emergency && !GLOBAL.emergencyStart) {
                    GLOBAL.emergencyStart = Date.now();
                }

                // Si se cancela, borrar todo
                if (!data.emergency) {
                    GLOBAL.emergencyStart = null;
                }

                // Enviar estado a todas las pestañas
                channel.postMessage({ type: "stateUpdate", state: GLOBAL });

                updateUIFromGlobal(); // esta pestaña también actualiza
            })
            .catch(() => {
                GLOBAL.connected = false;
                channel.postMessage({ type: "stateUpdate", state: GLOBAL });
                updateUIFromGlobal();
            });
    }, 700); // bastante rápido, pero estable
}

// Solo inicia polling si esta pestaña es master
if (isMasterTab()) {
    startMasterPolling();
}
