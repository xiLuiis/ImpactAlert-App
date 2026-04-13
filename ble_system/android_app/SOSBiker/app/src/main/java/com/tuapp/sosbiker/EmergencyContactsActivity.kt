package com.tuapp.sosbiker

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

        btnAddContact.setOnClickListener {
            addContact()
        }

        btnBackContacts.setOnClickListener {
            finish()
        }

        renderContacts()
    }

    private fun addContact() {
        val raw = edtPhoneNumber.text.toString().trim()

        if (raw.isBlank()) {
            Toast.makeText(this, "Enter a phone number", Toast.LENGTH_SHORT).show()
            return
        }

        val added = EmergencyContactsStore.addContact(this, raw)

        if (!added) {
            Toast.makeText(this, "Invalid or duplicated number", Toast.LENGTH_SHORT).show()
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
                text = "No emergency contacts yet"
                textSize = 16f
            }
            contactsContainer.addView(emptyText)
            return
        }

        contacts.forEach { contact ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 16
                }
            }

            val checkBox = CheckBox(this).apply {
                isChecked = contact.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    EmergencyContactsStore.setEnabled(
                        this@EmergencyContactsActivity,
                        contact.phoneNumber,
                        isChecked
                    )
                }
            }

            val txtPhone = TextView(this).apply {
                text = contact.phoneNumber
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val btnDelete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    EmergencyContactsStore.removeContact(
                        this@EmergencyContactsActivity,
                        contact.phoneNumber
                    )
                    renderContacts()
                }
            }

            row.addView(checkBox)
            row.addView(txtPhone)
            row.addView(btnDelete)

            contactsContainer.addView(row)
        }
    }
}