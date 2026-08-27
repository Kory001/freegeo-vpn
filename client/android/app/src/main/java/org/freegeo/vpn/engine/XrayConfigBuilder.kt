package org.freegeo.vpn.engine

import org.freegeo.vpn.data.Node
import org.freegeo.vpn.data.WarpAccount
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    const val SOCKS_PORT = 10808

    fun build(node: Node): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("dns", buildDns())
        root.put("inbounds", JSONArray().put(buildSocksInbound()))
        root.put("outbounds", buildOutbounds(node))
        root.put("routing", buildRouting())
        return root.toString()
    }

    fun buildWarp(account: WarpAccount): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("dns", buildDns())
        root.put("inbounds", JSONArray().put(buildSocksInbound()))

        val wg = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", account.privateKeyB64)
                    .put("address", JSONArray().put(account.addressV4).put(account.addressV6))
                    .put("peers", JSONArray().put(
                        JSONObject()
                            .put("publicKey", account.peerPublicKey)
                            .put("endpoint", account.endpoint)
                    ))
                    .put("mtu", 1280)
                    .put("reserved", JSONArray(account.reservedBytes().toList()))
                    .put("noKernelTun", true)
            )

        root.put(
            "outbounds",
            JSONArray()
                .put(wg)
                .put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        )
        root.put("routing", buildRouting())
        return root.toString()
    }

    private fun buildDns(): JSONObject {
        val servers = JSONArray()
            .put("https://1.1.1.1/dns-query")
            .put("https://dns.google/dns-query")
        return JSONObject()
            .put("servers", servers)
            .put("queryStrategy", "UseIP")
            .put("disableFallbackIfMatch", true)
    }

    private fun buildSocksInbound(): JSONObject =
        JSONObject()
            .put("listen", "127.0.0.1")
            .put("port", SOCKS_PORT)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put("auth", "noauth").put("udp", true)
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    .put("routeOnly", false)
            )

    private fun buildOutbounds(node: Node): JSONArray {
        val proxy = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", node.host)
                            .put("port", node.port)
                            .put(
                                "users",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", node.uuid)
                                        .put("encryption", "none")
                                        .put("flow", if (node.isReality) FLOW_VISION else "")
                                )
                            )
                    )
                )
            )
            .put("streamSettings", buildStream(node))
            .put("mux", JSONObject().put("enabled", false).put("concurrency", -1))

        return JSONArray()
            .put(proxy)
            .put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
            .put(
                JSONObject()
                    .put("tag", "direct")
                    .put("protocol", "freedom")
                    .put("settings", JSONObject())
            )
            .put(
                JSONObject()
                    .put("tag", "block")
                    .put("protocol", "blackhole")
                    .put("settings", JSONObject())
            )
    }

    private fun buildStream(node: Node): JSONObject {
        val stream = JSONObject().put("network", node.network)

        when {
            node.isReality -> {
                val reality = node.tls!!.reality!!
                stream.put("security", "reality")
                stream.put(
                    "realitySettings",
                    JSONObject()
                        .put("serverName", node.tls.sni ?: node.host)
                        .put("fingerprint", reality.fingerprint ?: "chrome")
                        .put("publicKey", reality.publicKey)
                        .put("shortId", reality.shortId ?: "")
                        .put("spiderX", "")
                )
            }
            node.tls != null && (node.tls.sni != null || node.tls.alpn != null) -> {
                stream.put("security", "tls")
                val tlsSettings = JSONObject()
                    .put("serverName", node.tls.sni ?: node.host)
                    .put("allowInsecure", false)
                node.tls.alpn?.let { alpn ->
                    tlsSettings.put("alpn", JSONArray(alpn))
                }
                stream.put("tlsSettings", tlsSettings)
            }
            else -> stream.put("security", "none")
        }

        if (node.network == "ws") {
            val ws = JSONObject().put("path", node.path ?: "/")
            stream.put("wsSettings", ws)
        }
        return stream
    }

    private fun buildRouting(): JSONObject {
        fun rule(block: JSONObject.() -> Unit): JSONObject =
            JSONObject().apply { put("type", "field"); block() }

        val rules = JSONArray()
            .put(rule { put("port", "53"); put("network", "tcp,udp"); put("outboundTag", "dns-out") })
            .put(
                rule {
                    put(
                        "ip",
                        JSONArray()
                            .put("10.0.0.0/8")
                            .put("172.16.0.0/12")
                            .put("192.168.0.0/16")
                            .put("127.0.0.0/8")
                            .put("::1/128")
                    )
                    put("outboundTag", "direct")
                }
            )

        return JSONObject().put("rules", rules)
    }

    private const val FLOW_VISION = "xtls-rprx-vision"
}
