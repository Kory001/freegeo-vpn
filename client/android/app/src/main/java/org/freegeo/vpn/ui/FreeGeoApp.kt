package org.freegeo.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freegeo.vpn.data.Node
import org.freegeo.vpn.service.ConnectionState
import org.freegeo.vpn.service.FreeGeoVpnService

private val DarkBg = Color(0xFF101418)
private val DarkSurface = Color(0xFF181D23)
private val Accent = Color(0xFF3DDC84)
private val Danger = Color(0xFFFF5370)

@Composable
fun FreeGeoApp(
    viewModel: MainViewModel,
    onConnectClick: () -> Unit,
    onWarpConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val ui by viewModel.ui.collectAsState()
    val connection by viewModel.connection.collectAsState()

    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = DarkBg,
            surface = DarkSurface,
            error = Danger
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBg) {
            Scaffold(
                containerColor = DarkBg,
                floatingActionButton = {
                    ConnectFab(connection, onConnectClick, onDisconnectClick)
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WarpQuickConnectCard(viewModel, ui, connection, onWarpConnectClick, onDisconnectClick)
                    StatusCard(ui, connection)
                    IpCheckRow(viewModel, ui)

                    OutlinedTextField(
                        value = ui.searchQuery,
                        onValueChange = viewModel::setSearch,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search country…") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (ui.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearch("") }) {
                                    Icon(Icons.Filled.Close, null)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    when {
                        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent)
                        }
                        ui.registryError != null -> Text(
                            "Registry unavailable: ${ui.registryError}\nPull down to retry later.",
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> NodeList(viewModel, ui)
                    }

                    if (ui.registryUrlDraft.isNotBlank() || connection == ConnectionState.ERROR) {
                        RegistryUrlEditor(viewModel, ui)
                    } else {
                        TextButton(onClick = { viewModel.setRegistryUrlDraft(" ") }) {
                            Text("Registry URL…", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectFab(
    connection: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val (label, color, action) = when (connection) {
        ConnectionState.CONNECTED -> Triple("DISCONNECT", Danger, onDisconnect)
        ConnectionState.CONNECTING -> Triple("CANCEL", Color.Gray, onDisconnect)
        ConnectionState.DISCONNECTED -> Triple("CONNECT", Accent, onConnect)
        ConnectionState.ERROR -> Triple("RETRY", Accent, onConnect)
    }
    ExtendedFloatingActionButton(
        onClick = action,
        containerColor = color,
        contentColor = Color.Black
    ) {
        if (connection == ConnectionState.CONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.Black
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun WarpQuickConnectCard(
    viewModel: MainViewModel,
    ui: UiState,
    connection: ConnectionState,
    onWarpConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isWarpConnected = connection == ConnectionState.CONNECTED && FreeGeoVpnService.useWarp
    val isWarpConnecting = (connection == ConnectionState.CONNECTING && FreeGeoVpnService.useWarp) || ui.warpProvisioning
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isWarpConnected) Accent.copy(alpha = 0.18f) else Color(0xFF1E2530)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Quick Connect — WARP", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                if (ui.hasWarpAccount) {
                    Text("ready", color = Accent, fontSize = 11.sp)
                } else {
                    Text("no signup", color = Color.Gray, fontSize = 11.sp)
                }
            }
            Text(
                "No server needed. Bypasses ISP blocks for Discord / Telegram / X instantly. For changing country (Netflix, Play Store region) use nodes below when available.",
                color = Color(0xFF9AA4B2),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            if (ui.warpError != null) {
                Text(ui.warpError, color = Danger, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    isWarpConnected -> Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)
                    ) { Text("DISCONNECT WARP") }
                    isWarpConnecting -> Button(
                        onClick = onDisconnect,
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.size(8.dp))
                        Text(if (ui.warpProvisioning) "Registering…" else "Connecting…")
                    }
                    else -> Button(
                        onClick = {
                            viewModel.connectWarp(
                                onReady = onWarpConnect,
                                onError = {}
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Filled.Bolt, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("CONNECT WARP")
                    }
                }
                if (!isWarpConnected && !isWarpConnecting && ui.hasWarpAccount) {
                    TextButton(onClick = { viewModel.clearWarpAccount() }) {
                        Text("Reset", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(ui: UiState, connection: ConnectionState) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(connection)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = when (connection) {
                        ConnectionState.DISCONNECTED -> "Disconnected"
                        ConnectionState.CONNECTING -> "Connecting…"
                        ConnectionState.CONNECTED -> "Connected"
                        ConnectionState.ERROR -> "Error: ${FreeGeoVpnService.lastError ?: "unknown"}"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            val node = ui.selectedNode
            if (node != null) {
                Text(
                    "${node.flag}  ${node.name} (${node.country.uppercase()})",
                    style = MaterialTheme.typography.headlineSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${node.latencyMs ?: "?"} ms", color = Color.Gray)
                    Text(if (node.bandwidth == org.freegeo.vpn.data.Bandwidth.HIGH) "high bw" else "low bw", color = Color.Gray)
                    if (node.warp) Text("WARP", color = Accent)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Accent
        ConnectionState.CONNECTING -> Color.Yellow
        ConnectionState.ERROR -> Danger
        ConnectionState.DISCONNECTED -> Color.Gray
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun IpCheckRow(viewModel: MainViewModel, ui: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = viewModel::checkIp, enabled = !ui.ipChecking) {
            Text(if (ui.ipChecking) "Checking…" else "Check my IP")
        }
        Text(
            ui.ipCheckResult ?: "",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun NodeList(viewModel: MainViewModel, ui: UiState) {
    val nodes = viewModel.visibleNodes()
    if (nodes.isEmpty()) {
        Text("No healthy nodes available.", textAlign = TextAlign.Center, color = Color.Gray)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(nodes, key = { it.id }) { node ->
            NodeRow(
                node = node,
                selected = node.id == ui.selectedNode?.id,
                favorite = node.id in ui.favorites,
                onSelect = { viewModel.selectNode(node) },
                onToggleFav = { viewModel.toggleFavorite(node.id) }
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: Node,
    selected: Boolean,
    favorite: Boolean,
    onSelect: () -> Unit,
    onToggleFav: () -> Unit
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Accent.copy(alpha = 0.15f) else DarkSurface
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(node.flag.ifBlank { "🏳" }, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name.ifBlank { node.id })
                Text(
                    "${node.country.uppercase()} · ${node.latencyMs ?: "?"} ms" +
                        if (node.warp) " · WARP" else "",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onToggleFav) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    tint = if (favorite) Color(0xFFFFD54F) else Color.Gray,
                    contentDescription = "favorite"
                )
            }
        }
    }
}

@Composable
private fun RegistryUrlEditor(viewModel: MainViewModel, ui: UiState) {
    Column {
        OutlinedTextField(
            value = ui.registryUrlDraft.trim(),
            onValueChange = viewModel::setRegistryUrlDraft,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("registry.json URL") },
            singleLine = true,
            label = { Text("Registry URL") }
        )
        Row {
            TextButton(onClick = viewModel::saveRegistryUrl) { Text("Save & refresh") }
            TextButton(onClick = { viewModel.setRegistryUrlDraft("") }) { Text("Close") }
        }
    }
}
