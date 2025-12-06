let contacts = JSON.parse(localStorage.getItem("emergencyContacts") || "[]");

// Render contacts
function renderContacts() {
    const list = document.getElementById("contact-list");
    list.innerHTML = "";

    contacts.forEach((c, index) => {

        const box = document.createElement("div");
        box.style.display = "flex";
        box.style.alignItems = "center";
        box.style.justifyContent = "space-between";
        box.style.gap = "15px";
        box.style.border = "1px solid #ddd";
        box.style.padding = "12px 15px";
        box.style.borderRadius = "12px";
        box.style.marginBottom = "12px";
        box.style.width = "90%";
        box.style.maxWidth = "420px";
        box.style.marginLeft = "auto";
        box.style.marginRight = "auto";
        box.style.background = "#fff";
        box.style.boxShadow = "0 2px 6px rgba(0,0,0,0.12)";

        // Columna izquierda: Nombre y número
        const left = document.createElement("div");
        left.style.flex = "1";
        left.innerHTML = `
            <div style="font-weight:600; font-size:18px;">${c.name}</div>
            <div style="color:#555; font-size:15px;">${c.phone}</div>
        `;

        // Columna derecha: botones
        const right = document.createElement("div");
        right.style.display = "flex";
        right.style.flexDirection = "column";
        right.style.gap = "6px";

        right.innerHTML = `
            <button style="padding:6px 10px; font-size:14px;" onclick="editContact(${index})">Edit</button>
            <button style="padding:6px 10px; font-size:14px;" onclick="deleteContact(${index})">Delete</button>
            <button style="padding:6px 10px; font-size:14px;" onclick="callContact('${c.phone}')">Call</button>
        `;

        box.appendChild(left);
        box.appendChild(right);

        list.appendChild(box);
    });
}


// Add contact
function addContact() {
    const name = document.getElementById("name-input").value.trim();
    const phone = document.getElementById("phone-input").value.trim();

    if (!name || !phone) {
        alert("Fill both fields");
        return;
    }

    contacts.push({ name, phone });
    localStorage.setItem("emergencyContacts", JSON.stringify(contacts));

    document.getElementById("name-input").value = "";
    document.getElementById("phone-input").value = "";

    renderContacts();
}

// Edit contact
function editContact(index) {
    const newName = prompt("New name:", contacts[index].name);
    const newPhone = prompt("New phone:", contacts[index].phone);

    if (!newName || !newPhone) return;

    contacts[index].name = newName;
    contacts[index].phone = newPhone;

    localStorage.setItem("emergencyContacts", JSON.stringify(contacts));
    renderContacts();
}

// Delete contact
function deleteContact(index) {
    if (!confirm("Delete this contact?")) return;

    contacts.splice(index, 1);
    localStorage.setItem("emergencyContacts", JSON.stringify(contacts));
    renderContacts();
}

// Call feature
function callContact(phone) {
    alert("Calling " + phone);
}

// Back button
function goBack() {
    window.location.href = "index.html";
}

document.addEventListener("DOMContentLoaded", renderContacts);
