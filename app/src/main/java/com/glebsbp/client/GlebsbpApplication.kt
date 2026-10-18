package com.glebsbp.client

import android.app.Application
import android.content.Context
import com.wireguard.android.backend.GoBackend

class GlebsbpApplication : Application() {
    @Volatile
    private var backendInstance: GoBackend? = null

    val backend: GoBackend
        get() = getBackend(this)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureTunnelChannel(this)
        DeployManager.init(this)
        AppShortcuts.refreshAsync(this)
    }

    fun getBackend(context: Context): GoBackend {
        return backendInstance ?: synchronized(this) {
            backendInstance ?: GoBackend(context.applicationContext).also { backendInstance = it }
        }
    }
}
