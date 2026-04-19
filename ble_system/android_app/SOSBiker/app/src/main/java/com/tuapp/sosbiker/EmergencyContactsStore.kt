package com.tuapp.sosbiker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object EmergencyContactsStore {

    private const val PREFS_NAME = "sos_biker_contacts_prefs"
    private const val KEY_CONTACTS = "emergency_contacts"

    fun getContacts(context: Context): MutableList<EmergencyContact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"

        val result = mutableListOf<EmergencyContact>()

        try {
            val jsonArray = JSONArray(raw)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                val phoneNumber = item.optString("phoneNumber", "")
                val enabled = item.optBoolean("enabled", true)

                if (phoneNumber.isNotBlank()) {
                    result.add(
                        EmergencyContact(
                            phoneNumber = phoneNumber,
                            enabled = enabled
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        return result
    }

    fun saveContacts(context: Context, contacts: List<EmergencyContact>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()

        contacts.forEach { contact ->
            val obj = JSONObject().apply {
                put("phoneNumber", contact.phoneNumber)
                put("enabled", contact.enabled)
            }
            jsonArray.put(obj)
        }

        prefs.edit()
            .putString(KEY_CONTACTS, jsonArray.toString())
            .apply()
    }

    fun addContact(context: Context, phoneNumber: String): Boolean {
        val normalized = normalizePhone(phoneNumber)
        if (normalized.isBlank()) return false

        val contacts = getContacts(context)
        val alreadyExists = contacts.any { it.phoneNumber == normalized }

        if (alreadyExists) return false

        contacts.add(
            EmergencyContact(
                phoneNumber = normalized,
                enabled = true
            )
        )

        saveContacts(context, contacts)
        return true
    }

    fun removeContact(context: Context, phoneNumber: String) {
        val normalized = normalizePhone(phoneNumber)

        val contacts = getContacts(context)
            .filterNot { it.phoneNumber == normalized }

        saveContacts(context, contacts)
    }

    fun setEnabled(context: Context, phoneNumber: String, enabled: Boolean) {
        val normalized = normalizePhone(phoneNumber)

        val updated = getContacts(context).map { contact ->
            if (contact.phoneNumber == normalized) {
                contact.copy(enabled = enabled)
            } else {
                contact
            }
        }

        saveContacts(context, updated)
    }

    fun getEnabledPhoneNumbers(context: Context): List<String> {
        return getContacts(context)
            .filter { it.enabled }
            .map { it.phoneNumber }
    }

    /**
     * 🔥 FIX: Ahora permitimos el símbolo '+' para soporte internacional real.
     */
    fun normalizePhone(raw: String): String {
        return raw.filter { it.isDigit() || it == '+' }.trim()
    }
}