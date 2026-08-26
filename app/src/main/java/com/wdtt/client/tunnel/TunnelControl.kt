package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.os.Build

object TunnelControl {

    fun stop(context: Context) {
        val stopIntent = Intent(context, TunnelService::class.java).apply { action = "STOP" }
        context.startService(stopIntent)
    }

    fun startFromSavedSettings(context: Context) {
        val appContext = context.applicationContext
        val startIntent = Intent(appContext, TunnelService::class.java).apply {
            action = "START_SAVED"
        }
        if (Build.VERSION.SDK_INT >= 26) {
            appContext.startForegroundService(startIntent)
        } else {
            appContext.startService(startIntent)
        }
    }
}
