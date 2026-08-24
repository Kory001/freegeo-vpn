package org.freegeo.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.freegeo.vpn.service.FreeGeoVpnService
import org.freegeo.vpn.ui.FreeGeoApp
import org.freegeo.vpn.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeGeoApp(
                viewModel = viewModel,
                onConnectClick = { requestVpnAndConnect() },
                onDisconnectClick = { stopService(Intent(this, FreeGeoVpnService::class.java)) }
            )
        }
    }

    private fun requestVpnAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            startTunnel()
        } else {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startTunnel()
        } else {
            viewModel.failed("VPN permission denied")
        }
    }

    private fun startTunnel() {
        viewModel.connect()
        startForegroundService(
            Intent(this, FreeGeoVpnService::class.java)
        )
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }
}
