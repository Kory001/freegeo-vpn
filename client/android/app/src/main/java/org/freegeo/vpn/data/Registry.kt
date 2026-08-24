package org.freegeo.vpn.data

import org.json.JSONArray
import org.json.JSONObject

enum class Bandwidth { LOW, HIGH }

data class Reality(
    val publicKey: String,
    val shortId: String?,
    val fingerprint: String?
)

data class Tls(
    val sni: String?,
    val alpn: List<String>?,
    val reality: Reality?
)

data class Node(
    val id: String,
    val country: String,
    val flag: String,
    val name: String,
    val platform: String,
    val protocol: String,
    val network: String,
    val host: String,
    val port: Int,
    val path: String?,
    val tls: Tls?,
    val uuid: String,
    val warp: Boolean,
    val status: String,
    val latencyMs: Int?,
    val bandwidth: Bandwidth
) {
    val isConnectable get() = status == "ok"
    val isReality get() = tls?.reality != null
}

data class DomainRoute(val country: String, val domains: List<String>)

data class Registry(
    val updatedAt: String,
    val defaultCountry: String,
    val domainRoutes: List<DomainRoute>,
    val nodes: List<Node>
) {
    fun healthyNodes(): List<Node> = nodes.filter { it.isConnectable }
}

object RegistryParser {

    fun parse(raw: String): Registry {
        val root = JSONObject(raw)
        return Registry(
            updatedAt = root.optString("updatedAt"),
            defaultCountry = root.optString("defaultCountry", "us"),
            domainRoutes = parseRoutes(root.optJSONArray("domainRoutes")),
            nodes = parseNodes(root.optJSONArray("nodes"))
        )
    }

    private fun parseRoutes(arr: JSONArray?): List<DomainRoute> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val domains = o.optJSONArray("domains")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it) }
            } ?: emptyList()
            DomainRoute(o.optString("country"), domains)
        }
    }

    private fun parseNodes(arr: JSONArray?): List<Node> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val bw = when (o.optString("bandwidth", "low")) {
                "high" -> Bandwidth.HIGH
                else -> Bandwidth.LOW
            }
            Node(
                id = o.getString("id"),
                country = o.getString("country"),
                flag = o.optString("flag", ""),
                name = o.optString("name", ""),
                platform = o.optString("platform", ""),
                protocol = o.optString("protocol", "vless"),
                network = o.optString("network", "tcp"),
                host = o.getString("host"),
                port = o.getInt("port"),
                path = o.optStringOrNull("path"),
                tls = parseTls(o.optJSONObject("tls")),
                uuid = o.getString("uuid"),
                warp = o.optBoolean("warp", false),
                status = o.optString("status", "disabled"),
                latencyMs = if (o.isNull("latencyMs")) null else o.optInt("latencyMs"),
                bandwidth = bw
            )
        }
    }

    private fun parseTls(o: JSONObject?): Tls? {
        o ?: return null
        val reality = o.optJSONObject("reality")?.let {
            Reality(
                publicKey = it.getString("pbk"),
                shortId = it.optStringOrNull("sid"),
                fingerprint = it.optStringOrNull("fp")
            )
        }
        val alpn = o.optJSONArray("alpn")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it) }
        }
        return Tls(sni = o.optStringOrNull("sni"), alpn = alpn, reality = reality)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
