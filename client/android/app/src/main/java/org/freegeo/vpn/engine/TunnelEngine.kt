package org.freegeo.vpn.engine

import android.content.Context
import android.util.Log
import org.freegeo.vpn.data.Node
import org.freegeo.vpn.data.WarpAccount
import org.json.JSONObject
import java.io.File

object LibXrayBridge {

    private val API_VERSIONS_TO_TRY = intArrayOf(2, 1, 3, 0)

    fun invoke(method: String, payload: JSONObject?): JSONObject {
        var lastErr: Throwable? = null
        for (ver in API_VERSIONS_TO_TRY) {
            try {
                val request = JSONObject().put("method", method)
                if (ver != 0) request.put("apiVersion", ver)
                if (payload != null) request.put("payload", payload)
                val raw = try {
                    libXray.LibXray.invoke(request.toString())
                } catch (t: Throwable) {
                    throw RuntimeException("libXray invoke failed (${t::class.simpleName}): ${t.message}", t)
                }
                val obj = JSONObject(raw)
                if (!obj.optBoolean("success") && obj.optString("error").contains("unsupported apiVersion", ignoreCase = true)) {
                    lastErr = RuntimeException(obj.optString("error"))
                    android.util.Log.w("TunnelEngine", "apiVersion $ver unsupported, trying next")
                    continue
                }
                return obj
            } catch (t: Throwable) {
                if (t.message?.contains("unsupported apiVersion", ignoreCase = true) == true) {
                    lastErr = t
                    continue
                }
                throw t
            }
        }
        throw lastErr ?: RuntimeException("unsupported apiVersion: all versions failed")
    }

    fun runXray(xrayJson: String): Result<Unit> = runCatching {
        val resp = invoke("runXray", JSONObject().put("xrayJson", xrayJson))
        if (!resp.optBoolean("success")) {
            val err = resp.optString("error", "runXray failed")
            Log.e("TunnelEngine", "runXray failed: $err\nConfig: ${xrayJson.take(500)}")
            error(err)
        }
    }

    fun stopXray(): Result<Unit> = runCatching {
        try {
            val resp = invoke("stopXray", null)
            if (!resp.optBoolean("success")) {
                Log.w("TunnelEngine", "stopXray: ${resp.optString("error")}")
            }
        } catch (t: Throwable) {
            Log.w("TunnelEngine", "stopXray exception", t)
        }
    }

    fun isRunning(): Boolean = runCatching {
        invoke("getXrayState", null).optJSONObject("data")?.optBoolean("running") ?: false
    }.getOrDefault(false)
}

class TunnelEngine(private val context: Context) {

    private var tun2socks: Process? = null
    @Volatile private var lastTunError: String? = null

    fun start(node: Node, tunFd: Int): Result<Unit> =
        LibXrayBridge.runXray(XrayConfigBuilder.build(node)).mapCatching {
            startTun2Socks(tunFd)
        }

    fun startWarp(account: WarpAccount, tunFd: Int): Result<Unit> =
        LibXrayBridge.runXray(XrayConfigBuilder.buildWarp(account)).mapCatching {
            startTun2Socks(tunFd)
        }

    fun stop() {
        try { tun2socks?.destroy() } catch (_: Throwable) {}
        try { tun2socks?.waitFor() } catch (_: Throwable) {}
        tun2socks = null
        LibXrayBridge.stopXray()
    }

    fun isAlive(): Boolean {
        val procAlive = tun2socks?.isAlive == true
        val xrayAlive = LibXrayBridge.isRunning()
        if (!procAlive && tun2socks != null) {
            Log.w("TunnelEngine", "tun2socks died, lastError=$lastTunError xray=$xrayAlive")
        }
        return procAlive && xrayAlive
    }

    fun getLastError(): String? = lastTunError

    private fun startTun2Socks(tunFd: Int) {
        lastTunError = null
        val cfg = File(context.cacheDir, "tun2socks.yml")
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
        if (!bin.exists()) {
            error("tun2socks binary not found at ${bin.absolutePath} (ABI mismatch?)")
        }
        try { bin.setExecutable(true) } catch (_: Throwable) {}
        if (!bin.canExecute()) {
            Log.w("TunnelEngine", "tun2socks not executable, trying chmod")
            try { Runtime.getRuntime().exec(arrayOf("chmod", "755", bin.absolutePath)).waitFor() } catch (_: Throwable) {}
        }
        Log.i("TunnelEngine", "Starting tun2socks: ${bin.absolutePath} ${cfg.absolutePath} $tunFd")
        val proc = ProcessBuilder(bin.absolutePath, cfg.absolutePath, tunFd.toString())
            .redirectErrorStream(true)
            .start()
        tun2socks = proc
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    lastTunError = line
                    Log.w("TunnelEngine", "tun2socks: $line")
                }
                val code = proc.waitFor()
                if (code != 0) {
                    lastTunError = "tun2socks exit $code"
                    Log.w("TunnelEngine", "tun2socks exited $code")
                }
            } catch (e: Throwable) {
                Log.w("TunnelEngine", "tun2socks reader failed", e)
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(400)
        if (proc.isAlive.not()) {
            val err = lastTunError ?: "unknown"
            error("tun2socks failed to start: $err")
        }
    }
}
