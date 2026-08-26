package com.wdtt.client

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            if (TunnelManager.running.value || TunnelManager.isConnecting.value) {
                TunnelControl.stop(applicationContext)
                updateTile(false)
                return
            }

            val needsVpnPermission = runCatching {
                VpnService.prepare(this@QuickToggleTileService) != null
            }.getOrDefault(true)
            if (!needsVpnPermission) {
                TunnelControl.startFromSavedSettings(applicationContext)
                updateTileConnecting()
                return
            }

            TunnelManager.scope.launch {
                try {
                    val mode = SettingsStore(applicationContext).connectionMode.first()
                    withContext(Dispatchers.Main) {
                        if (mode == SettingsStore.CONNECTION_MODE_SOCKS) {
                            TunnelControl.startFromSavedSettings(applicationContext)
                            updateTileConnecting()
                        } else {
                            Toast.makeText(
                                this@QuickToggleTileService,
                                "Разрешите qWDTT создать VPN-подключение",
                                Toast.LENGTH_LONG,
                            ).show()
                            openVpnPermissionActivity()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "QS tile start failed", e)
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "QS tile onClick failed", e)
        }
    }

    private fun updateTileState() {
        if (TunnelManager.isConnecting.value || TunnelManager.isReconnecting.value) {
            updateTileConnecting()
        } else {
            updateTile(TunnelManager.running.value)
        }
    }

    private fun updateTileConnecting() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.label = "qWDTT"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = "Подключение…"
        }
        tile.updateTile()
    }

    private fun updateTile(running: Boolean) {
        val tile = qsTile ?: return
        if (running) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "qWDTT: Вкл"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = "Активен"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "qWDTT: Выкл"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = "Отключен"
            }
        }
        tile.updateTile()
    }

    private fun openVpnPermissionActivity() {
        openActivity(Intent(this, VpnPermissionActivity::class.java), 101)
    }

    private fun openActivity(intent: Intent, requestCode: Int) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to open activity", e)
        }
    }

    companion object {
        private const val TAG = "QuickToggleTile"

        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    requestListeningState(
                        context,
                        ComponentName(context, QuickToggleTileService::class.java),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "requestListeningState failed", e)
                }
            }
        }
    }
}
