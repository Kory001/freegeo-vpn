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
                    android.util.Log.w("TunnelEngine", "apiVersion $ver unsupported, trying next")
                    continue
                }
                if (!obj.optBoolean("success") && errStr.contains("unknown method", ignoreCase = true)) {
                    lastErr = RuntimeException(errStr)
                    android.util.Log.w("TunnelEngine", "method $method unknown for ver $ver")
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
            Log.w("TunnelEngine", "tun2socks not alive, lastError=$lastTunError xray=$xrayAlive tun2socks=$tun2socks")
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
        // Primary: JNI via hev.htproxy.TProxyService (correct for VpnService fd)
        try {
            Log.i("TunnelEngine", "Starting tun2socks JNI: ${cfg.absolutePath} fd=$tunFd")
            val ok = TProxyService.TProxyStartService(cfg.absolutePath, tunFd)
            if (!ok) {
                lastTunError = "TProxyStartService returned false"
                Log.e("TunnelEngine", lastTunError!!)
                error(lastTunError!!)
            }
            // Give it a moment to report running
            Thread.sleep(400)
            val running = try { TProxyService.TProxyIsRunning() } catch (_: Throwable) { true }
            if (!running) {
                lastTunError = "TProxy not running after start"
                error(lastTunError!!)
            }
            tun2socks = null
            Log.i("TunnelEngine", "tun2socks JNI started")
            return
        } catch (e: Throwable) {
            // If JNI class not found or load failed, fall back to exec with detailed error
            if (e.message?.contains("failed to start") == true) throw e
            val stack = android.util.Log.getStackTraceString(e)
            Log.e("TunnelEngine", "JNI start failed: ${e::class.simpleName} ${e.message}\n$stack")
            lastTunError = "JNI ${e::class.simpleName}: ${e.message}"
            e.printStackTrace()
        }
        val jniError = lastTunError
        // Fallback: exec (legacy, will exit 254 with VpnService but useful for diagnostics)
        val bin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
        if (!bin.exists()) {
            // also try libhev
            val alt = File(context.applicationInfo.nativeLibraryDir, "libhev-socks5-tunnel.so")
            if (alt.exists()) {
                error("tun2socks JNI failed and exec fallback not suitable for VpnService (use JNI). JNI error: $lastTunError")
            }
            error("tun2socks binary not found at ${bin.absolutePath} (ABI mismatch?) JNI error: $lastTunError")
        }
        try { bin.setExecutable(true) } catch (_: Throwable) {}
        if (!bin.canExecute()) {
            Log.w("TunnelEngine", "tun2socks not executable, trying chmod")
            try { Runtime.getRuntime().exec(arrayOf("chmod", "755", bin.absolutePath)).waitFor() } catch (_: Throwable) {}
        }
        Log.i("TunnelEngine", "Starting tun2socks exec: ${bin.absolutePath} ${cfg.absolutePath} $tunFd")
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
            error("tun2socks failed to start: $err (JNI was $jniError)")
        }
    }
}
