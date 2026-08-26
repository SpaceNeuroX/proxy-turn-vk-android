package com.wdtt.client

import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Прозрачный экран только для системного VPN-consent.
 * Используется виджетом и плиткой Quick Settings — без открытия главного UI.
 */
class VpnPermissionActivity : ComponentActivity() {
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startTunnelAndFinish()
        } else {
            Toast.makeText(this, "VPN-подключение не разрешено", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        val permissionIntent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (permissionIntent == null) {
            startTunnelAndFinish()
        } else {
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun startTunnelAndFinish() {
        TunnelControl.startFromSavedSettings(applicationContext)
        finish()
    }
}
