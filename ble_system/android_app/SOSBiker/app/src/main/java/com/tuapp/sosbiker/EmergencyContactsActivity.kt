package com.tuapp.sosbiker

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class EmergencyContactsActivity : AppCompatActivity() {

    private lateinit var edtPhoneNumber: EditText
    private lateinit var btnAddContact: Button
    private lateinit var btnBackContacts: Button
    private lateinit var contactsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency_contacts)

        edtPhoneNumber = findViewById(R.id.edtPhoneNumber)
        btnAddContact = findViewById(R.id.btnAddContact)
        btnBackContacts = findViewById(R.id.btnBackContacts)
        contactsContainer = findViewById(R.id.contactsContainer)

        edtPhoneNumber.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && edtPhoneNumber.text.isBlank()) {
                edtPhoneNumber.setText("+52")
                edtPhoneNumber.setSelection(3)
            }
        }

        btnAddContact.setOnClickListener { addContact() }
        btnBackContacts.setOnClickListener { finish() }

        renderContacts()
    }

    private fun addContact() {
        var raw = edtPhoneNumber.text.toString().trim()
        if (raw.isBlank() || raw == "+52") {
            Toast.makeText(this, "Ingresa un número válido", Toast.LENGTH_SHORT).show()
            return
        }
        if (raw.length == 10 && raw.all { it.isDigit() }) raw = "+52$raw"
        else if (raw.length == 12 && raw.startsWith("52") && raw.all { it.isDigit() }) raw = "+$raw"

        val added = EmergencyContactsStore.addContact(this, raw)
        if (!added) {
            Toast.makeText(this, "El número ya existe", Toast.LENGTH_SHORT).show()
            return
        }

        edtPhoneNumber.setText("")
        renderContacts()
    }

    private fun renderContacts() {
        contactsContainer.removeAllViews()
        val contacts = EmergencyContactsStore.getContacts(this)

        if (contacts.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "No tienes contactos registrados"
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 0)
                setTextColor(Color.GRAY)
            }
            contactsContainer.addView(emptyText)
            return
        }

        contacts.forEach { contact ->
            // Card Layout para cada contacto
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }
                setPadding(24, 24, 24, 24)
                setBackgroundColor(Color.WHITE)
                elevation = 4f
                gravity = Gravity.CENTER_VERTICAL
            }

            val checkBox = CheckBox(this).apply {
                isChecked = contact.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    EmergencyContactsStore.setEnabled(this@EmergencyContactsActivity, contact.phoneNumber, isChecked)
                }
            }

            val txtPhone = TextView(this).apply {
                text = contact.phoneNumber
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#2D3436"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnDelete = Button(this).apply {
                text = "BORRAR"
                textSize = 11f
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF7675"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    EmergencyContactsStore.removeContact(this@EmergencyContactsActivity, contact.phoneNumber)
                    renderContacts()
                }
            }

            card.addView(checkBox)
            card.addView(txtPhone)
            card.addView(btnDelete)
            contactsContainer.addView(card)
        }
    }
}