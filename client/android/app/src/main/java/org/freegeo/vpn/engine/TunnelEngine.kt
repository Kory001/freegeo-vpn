package org.freegeo.vpn.engine

import android.content.Context
import org.freegeo.vpn.data.Node
import org.json.JSONObject
import java.io.File

object LibXrayBridge {

    const val API_VERSION = 2

    fun invoke(method: String, payload: JSONObject?): JSONObject {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", method)
        if (payload != null) {
            request.put("payload", payload)
        }
        val response = libXray.LibXray.invoke(request.toString())
        return JSONObject(response)
    }

    fun runXray(xrayJson: String): Result<Unit> = runCatching {
        val resp = invoke("runXray", JSONObject().put("xrayJson", xrayJson))
        require(resp.optBoolean("success")) { resp.optString("error", "runXray failed") }
    }

    fun stopXray(): Result<Unit> = runCatching {
        val resp = invoke("stopXray", null)
        require(resp.optBoolean("success")) { resp.optString("error", "stopXray failed") }
    }

    fun isRunning(): Boolean = runCatching {
        invoke("getXrayState", null).optJSONObject("data")?.optBoolean("running") ?: false
    }.getOrDefault(false)
}

class TunnelEngine(private val context: Context) {

    private var tun2socks: Process? = null

    fun start(node: Node, tunFd: Int): Result<Unit> =
        LibXrayBridge.runXray(XrayConfigBuilder.build(node)).mapCatching {
            startTun2Socks(tunFd)
        }

    fun stop() {
        runCatching { tun2socks?.destroy() }
        tun2socks = null
        LibXrayBridge.stopXray()
    }

    fun isAlive(): Boolean =
        tun2socks?.isAlive == true && LibXrayBridge.isRunning()

    private fun startTun2Socks(tunFd: Int) {
        val cfg = context.cacheDir.resolve("tun2socks.yml")
        cfg.writeText(
            """
            tunnel:
              name: tun0
              mtu: 8500
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
              icmp: 'off'
            socks5:
              port: ${XrayConfigBuilder.SOCKS_PORT}
              address: 127.0.0.1
              udp: 'udp'
            misc:
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        val bin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
        tun2socks = ProcessBuilder(bin.absolutePath, cfg.absolutePath, tunFd.toString())
            .redirectErrorStream(true)
            .start()
    }
}
