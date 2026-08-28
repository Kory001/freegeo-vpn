package org.freegeo.vpn.engine

import android.content.Context
import android.util.Log
import hev.htproxy.TProxyService
import org.freegeo.vpn.data.Node
import org.freegeo.vpn.data.WarpAccount
import org.json.JSONObject
import java.io.File

object LibXrayBridge {

    private val API_VERSIONS_TO_TRY = intArrayOf(1, 0, 2, 3)

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
                val errStr = obj.optString("error")
                if (!obj.optBoolean("success") && errStr.contains("unsupported apiVersion", ignoreCase = true)) {
                    lastErr = RuntimeException(errStr)
                    Log.w("TunnelEngine", "apiVersion $ver unsupported, trying next")
                    continue
                }
                if (!obj.optBoolean("success") && errStr.contains("unknown method", ignoreCase = true)) {
                    lastErr = RuntimeException(errStr)
                    Log.w("TunnelEngine", "method $method unknown for ver $ver")
                    continue
                }
                return obj
            } catch (t: Throwable) {
                if (t.message?.contains("unsupported apiVersion", ignoreCase = true) == true ||
                    t.message?.contains("unknown method", ignoreCase = true) == true) {
                    lastErr = t
                    continue
                }
                throw t
            }
        }
        throw lastErr ?: RuntimeException("unsupported apiVersion: all versions failed")
    }

    private fun tryRunXray(json: String): Result<Unit> {
        val payloads = listOf(
            "runXrayFromJson" to JSONObject().put("configJSON", json),
            "runXray" to JSONObject().put("xrayJson", json),
            "runXray" to JSONObject().put("configPath", json)
        )
        var last: Throwable? = null
        for ((method, payload) in payloads) {
            val res = runCatching {
                val resp = invoke(method, payload)
                if (!resp.optBoolean("success")) {
                    val err = resp.optString("error", "$method failed")
                    if (err.contains("unknown method", ignoreCase = true)) throw RuntimeException(err)
                    Log.e("TunnelEngine", "$method failed: $err")
                    error(err)
                }
            }
            if (res.isSuccess) return res
            val ex = res.exceptionOrNull()
            if (ex?.message?.contains("unknown method", ignoreCase = true) == true) {
                last = ex
                continue
            }
            return res
        }
        return Result.failure(last ?: RuntimeException("runXray failed"))
    }

    fun runXray(xrayJson: String): Result<Unit> = tryRunXray(xrayJson)

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
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        LibXrayBridge.stopXray()
    }

    fun isAlive(): Boolean {
        val procAlive = when {
            tun2socks != null -> tun2socks?.isAlive == true
            else -> try { TProxyService.TProxyIsRunning() } catch (_: Throwable) { false }
        }
        val xrayAlive = LibXrayBridge.isRunning()
        if (!procAlive) {
            Log.w("TunnelEngine", "tun2socks not alive, lastError=$lastTunError xray=$xrayAlive")
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
        Log.i("TunnelEngine", "Starting tun2socks JNI: fd=$tunFd config=${cfg.absolutePath}")
        try {
            val ok = TProxyService.TProxyStartService(cfg.absolutePath, tunFd)
            if (!ok) {
                lastTunError = "TProxyStartService returned false"
                Log.e("TunnelEngine", lastTunError!!)
                error(lastTunError!!)
            }
            Thread.sleep(500)
            val running = try { TProxyService.TProxyIsRunning() } catch (_: Throwable) { true }
            if (!running) {
                lastTunError = "tun2socks JNI started but not running"
                Log.e("TunnelEngine", lastTunError!!)
                error(lastTunError!!)
            }
            tun2socks = null
            Log.i("TunnelEngine", "tun2socks JNI connected")
        } catch (e: Throwable) {
            lastTunError = "tun2socks JNI: ${e.message}"
            Log.e("TunnelEngine", "tun2socks JNI failed", e)
            error("tun2socks JNI failed: ${e.message}")
        }
    }
}
