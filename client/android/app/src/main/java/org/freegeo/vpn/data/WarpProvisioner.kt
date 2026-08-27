package org.freegeo.vpn.data

import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID

data class WarpAccount(
    val privateKeyB64: String,
    val publicKeyB64: String,
    val peerPublicKey: String,
    val endpoint: String,
    val addressV4: String,
    val addressV6: String,
    val clientIdB64: String
) {
    fun reservedBytes(): IntArray {
        val decoded = android.util.Base64.decode(clientIdB64, android.util.Base64.DEFAULT)
        return if (decoded.size >= 4) {
            intArrayOf(decoded[1].toInt() and 0xFF, decoded[2].toInt() and 0xFF, decoded[3].toInt() and 0xFF)
        } else {
            intArrayOf(0, 0, 0)
        }
    }

    companion object {
        fun fromJson(json: String): WarpAccount {
            val o = JSONObject(json)
            return WarpAccount(
                privateKeyB64 = o.getString("privateKey"),
                publicKeyB64 = o.getString("publicKey"),
                peerPublicKey = o.getString("peerKey"),
                endpoint = o.getString("endpoint"),
                addressV4 = o.getString("v4"),
                addressV6 = o.getString("v6"),
                clientIdB64 = o.getString("clientId")
            )
        }
    }

    fun toJson(): String = JSONObject()
        .put("privateKey", privateKeyB64)
        .put("publicKey", publicKeyB64)
        .put("peerKey", peerPublicKey)
        .put("endpoint", endpoint)
        .put("v4", addressV4)
        .put("v6", addressV6)
        .put("clientId", clientIdB64)
        .toString()
}

object WarpProvisioner {

    private const val API = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"

    fun register(): Result<WarpAccount> = runCatching {
        val keypair = generateX25519Keypair()

        val body = JSONObject()
            .put("key", keypair.publicB64)
            .put("install_id", UUID.randomUUID().toString())
            .put("fcm_token", "")
            .put("warp_enabled", true)
            .put("tos", System.currentTimeMillis() / 1000)
            .put("type", "Android")
            .put("model", "Android")
            .put("locale", "en_US")

        val conn = URL(API).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "okhttp/3.12.1")
        conn.doOutput = true
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                error("WARP registration failed: HTTP ${conn.responseCode}")
            }
            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
            parseResponse(resp, keypair.privateB64, keypair.publicB64)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(resp: JSONObject, privB64: String, pubB64: String): WarpAccount {
        val config = resp.getJSONObject("config")
        val iface = config.getJSONObject("interface")
        val addresses = iface.getJSONObject("addresses")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val endpointHost = peer.getJSONObject("endpoint").optString("host").ifBlank { DEFAULT_ENDPOINT }
        return WarpAccount(
            privateKeyB64 = privB64,
            publicKeyB64 = pubB64,
            peerPublicKey = peer.getString("public_key"),
            endpoint = normalizeEndpoint(endpointHost),
            addressV4 = addresses.getString("v4"),
            addressV6 = addresses.getString("v6"),
            clientIdB64 = resp.getString("client_id")
        )
    }

    private fun normalizeEndpoint(host: String): String =
        if (host.contains(":")) host else "$host:2408"

    data class Keypair(val privateB64: String, val publicB64: String)

    private fun generateX25519Keypair(): Keypair {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val priv = pair.private as X25519PrivateKeyParameters
        val pub = pair.public as X25519PublicKeyParameters
        return Keypair(
            privateB64 = android.util.Base64.encodeToString(priv.encoded, android.util.Base64.NO_WRAP),
            publicB64 = android.util.Base64.encodeToString(pub.encoded, android.util.Base64.NO_WRAP)
        )
    }
}
