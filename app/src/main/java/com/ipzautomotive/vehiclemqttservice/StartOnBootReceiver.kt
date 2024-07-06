package com.ipzautomotive.vehiclemqttservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class StartOnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Intent(context, VehicleMqttClientService::class.java).also {
                Log.d("VehicleMqttClient", "onBoot: started!")
                context.startForegroundService(it)
                return
            }
        }
    }
}