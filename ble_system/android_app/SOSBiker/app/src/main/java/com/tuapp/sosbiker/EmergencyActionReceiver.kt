package com.tuapp.sosbiker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class EmergencyActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.tuapp.sosbiker.ACTION_CANCEL_EMERGENCY" -> {
                MainActivity.emergencyActiveGlobal = false
                MainActivity.collisionHitsGlobal = 0
                MainActivity.cancelEmergencyFromNotification = true

                NotificationManagerCompat.from(context).cancel(1001)
            }
        }
    }
}