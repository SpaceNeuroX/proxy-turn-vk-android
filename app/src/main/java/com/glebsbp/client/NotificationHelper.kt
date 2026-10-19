package com.glebsbp.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val TUNNEL_CHANNEL_ID = "glebsbp_tunnel_v5"

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        if (!hasPostNotificationsPermission(context)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureTunnelChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(TUNNEL_CHANNEL_ID)
        if (existing != null && existing.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            return
        }
        val channel = NotificationChannel(
            TUNNEL_CHANNEL_ID,
            "GLEBSBP Туннель",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Статус VPN-туннеля и переподключение"
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun ensureAuxChannel(context: Context, channelId: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            channelId,
            name,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            this.description = description
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= 26 -> {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                else -> {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
