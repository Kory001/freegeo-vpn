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
    private var pendingWarp: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeGeoApp(
                viewModel = viewModel,
                onConnectClick = { requestVpnAndConnectNode() },
                onWarpConnectClick = { requestVpnAndConnectWarp() },
                onDisconnectClick = { stopService(Intent(this, FreeGeoVpnService::class.java)) }
            )
        }
    }

    private fun requestVpnAndConnectNode() {
        pendingWarp = false
        val intent = VpnService.prepare(this)
        if (intent == null) {
            startTunnelNode()
        } else {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        }
    }

    private fun requestVpnAndConnectWarp() {
        viewModel.connectWarp(
            onReady = {
                pendingWarp = true
                val intent = VpnService.prepare(this@MainActivity)
                if (intent == null) {
                    startTunnelWarp()
                } else {
                    startActivityForResult(intent, VPN_REQUEST_CODE)
                }
            },
            onError = { msg -> viewModel.failed(msg) }
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (pendingWarp) startTunnelWarp() else startTunnelNode()
        } else if (requestCode == VPN_REQUEST_CODE) {
            viewModel.failed("VPN permission denied")
        }
    }

    private fun startTunnelNode() {
        pendingWarp = false
        viewModel.connect()
        startForegroundService(Intent(this, FreeGeoVpnService::class.java))
    }

    private fun startTunnelWarp() {
        startForegroundService(Intent(this, FreeGeoVpnService::class.java))
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }
}
