/* ===============================
   LOAD CONTACTS ON START
=============================== */
document.addEventListener("DOMContentLoaded", () => {
    renderContacts();
});

/* ===============================
   SAVE NEW CONTACT
=============================== */
function addContact() {
    const name = document.getElementById("name-input").value.trim();
    const phone = document.getElementById("phone-input").value.trim();

    if (!name || !phone) {
        alert("Please fill name and phone.");
        return;
    }

    let contacts = JSON.parse(localStorage.getItem("contacts") || "[]");

    contacts.push({ name, phone });

    localStorage.setItem("contacts", JSON.stringify(contacts));

    // limpiar campos
    document.getElementById("name-input").value = "";
    document.getElementById("phone-input").value = "";

    renderContacts();
}

/* ===============================
   RENDER CONTACT LIST
=============================== */
function renderContacts() {
    const list = document.getElementById("contact-list");
    list.innerHTML = "";

    let contacts = JSON.parse(localStorage.getItem("contacts") || "[]");

    contacts.forEach((c, index) => {
        const card = document.createElement("div");
        card.className = "contact-card";

        card.innerHTML = `
            <div class="contact-info">
                <strong>${c.name}</strong>
                <span>${c.phone}</span>
            </div>

            <div class="contact-actions">
                <button class="action-btn" onclick="callContact('${c.phone}')">📞</button>
                <button class="action-btn" onclick="editContact(${index})">✏️</button>
                <button class="action-btn" onclick="deleteContact(${index})">🗑️</button>
            </div>
        `;

        list.appendChild(card);
    });
}

/* ===============================
   DELETE CONTACT
=============================== */
function deleteContact(index) {
    let contacts = JSON.parse(localStorage.getItem("contacts") || "[]");
    contacts.splice(index, 1);
    localStorage.setItem("contacts", JSON.stringify(contacts));
    renderContacts();
}

/* ===============================
   EDIT CONTACT
=============================== */
function editContact(index) {
    let contacts = JSON.parse(localStorage.getItem("contacts") || "[]");

    const newName = prompt("New name:", contacts[index].name);
    const newPhone = prompt("New phone:", contacts[index].phone);

    if (!newName || !newPhone) return;

    contacts[index] = { name: newName, phone: newPhone };
    localStorage.setItem("contacts", JSON.stringify(contacts));
    renderContacts();
}

/* ===============================
   DIAL CONTACT
=============================== */
function callContact(phone) {
    alert("Simulating call to " + phone);
}

/* ===============================
   BACK
=============================== */
function goBack() {
    window.location.href = "index.html";
}
