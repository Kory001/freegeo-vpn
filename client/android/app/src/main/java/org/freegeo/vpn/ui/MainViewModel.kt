package org.freegeo.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freegeo.vpn.data.Bandwidth
import org.freegeo.vpn.data.Node
import org.freegeo.vpn.data.Registry
import org.freegeo.vpn.data.RegistryRepository
import org.freegeo.vpn.data.SecurePrefs
import org.freegeo.vpn.service.ConnectionState
import org.freegeo.vpn.service.FreeGeoVpnService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UiState(
    val registry: Registry? = null,
    val loading: Boolean = true,
    val registryError: String? = null,
    val selectedNode: Node? = null,
    val favorites: Set<String> = emptySet(),
    val searchQuery: String = "",
    val ipCheckResult: String? = null,
    val ipChecking: Boolean = false,
    val registryUrlDraft: String = ""
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RegistryRepository(app)
    private val prefs = SecurePrefs(app)

    private val _ui = MutableStateFlow(UiState(registryUrlDraft = prefs.registryUrl))
    val ui: StateFlow<UiState> = _ui

    val connection: StateFlow<ConnectionState> = FreeGeoVpnService.state

    init {
        refreshRegistry()
        if (FreeGeoVpnService.state.value == ConnectionState.DISCONNECTED) {
            FreeGeoVpnService.bypassApps = emptyList()
        }
    }

    fun refreshRegistry() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, registryError = null)
            repo.fetch().fold(
                onSuccess = { reg ->
                    val selected = resolveSelectedNode(reg)
                    _ui.value = _ui.value.copy(
                        registry = reg,
                        loading = false,
                        selectedNode = selected
                    )
                    prefs.selectedNodeId = selected?.id
                },
                onFailure = { e ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        registryError = e.message ?: "Failed to load registry"
                    )
                }
            )
        }
    }

    private fun resolveSelectedNode(reg: Registry): Node? {
        val healthy = reg.healthyNodes().ifEmpty { return null }
        return healthy.firstOrNull { it.id == prefs.selectedNodeId }
            ?: healthy.sortedWith(
                compareBy<Node> { it.bandwidth != Bandwidth.HIGH }
                    .thenBy { it.latencyMs ?: Int.MAX_VALUE }
            ).first()
    }

    fun selectNode(node: Node) {
        prefs.selectedNodeId = node.id
        _ui.value = _ui.value.copy(selectedNode = node)
    }

    fun setSearch(query: String) {
        _ui.value = _ui.value.copy(searchQuery = query)
    }

    fun toggleFavorite(id: String) {
        _ui.value = _ui.value.copy(favorites = prefs.toggleFavorite(id))
    }

    fun visibleNodes(): List<Node> {
        val reg = _ui.value.registry ?: return emptyList()
        val query = _ui.value.searchQuery.trim().lowercase()
        val favs = _ui.value.favorites
        val nodes = reg.healthyNodes()
            .filter { query.isEmpty() || it.country.contains(query) || it.name.lowercase().contains(query) }
            .sortedWith(
                compareBy<Node> { it.id !in favs }
                    .thenBy { it.latencyMs ?: Int.MAX_VALUE }
                    .thenBy { it.country }
            )
        return nodes
    }

    fun setRegistryUrlDraft(value: String) {
        _ui.value = _ui.value.copy(registryUrlDraft = value)
    }

    fun saveRegistryUrl() {
        prefs.registryUrl = _ui.value.registryUrlDraft.trim()
        refreshRegistry()
    }

    fun connect() {
        val node = _ui.value.selectedNode ?: return
        FreeGeoVpnService.currentConfiguredNode = node
        FreeGeoVpnService.state.value = ConnectionState.CONNECTING
    }

    fun connected() {
        FreeGeoVpnService.state.value = ConnectionState.CONNECTED
    }

    fun failed(message: String) {
        FreeGeoVpnService.reportError(message)
    }

    fun checkIp() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(ipChecking = true, ipCheckResult = null)
            val result = withContext(Dispatchers.IO) { fetchIpInfo() }
            _ui.value = _ui.value.copy(ipChecking = false, ipCheckResult = result)
        }
    }

    private fun fetchIpInfo(): String = runCatching {
        val conn = URL("https://ipinfo.io/json").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            "${json.optString("ip")} · ${json.optString("country", "?")}${json.optString("org").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
        } finally {
            conn.disconnect()
        }
    }.getOrElse { "IP check failed: ${it.message}" }
}
