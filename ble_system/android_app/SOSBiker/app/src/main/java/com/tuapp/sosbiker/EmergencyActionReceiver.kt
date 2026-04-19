package com.tuapp.sosbiker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class EmergencyActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.tuapp.sosbiker.ACTION_CANCEL_EMERGENCY") {
            // Marcamos la bandera global
            MainActivity.cancelEmergencyFromNotification = true
            
            // Intentamos avisar a la actividad de forma inmediata si está viva
            val updateIntent = Intent("com.tuapp.sosbiker.REFRESH_UI")
            context.sendBroadcast(updateIntent)

            // Cancelamos AMBAS notificaciones (la de choque y la de cuenta atrás)
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(1001) // crashDetectedNotificationId
            nm.cancel(1002) // countdownNotificationId
        }
    }
}