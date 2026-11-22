package com.example.routing

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.example.models.ProxyProtocol
import com.example.models.RoutingNode
import com.example.models.RoutingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

object RoutingManager {

    private val _routingState = MutableStateFlow(RoutingState())
    val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

    private val _nodes = MutableStateFlow<List<RoutingNode>>(emptyList())
    val nodes: StateFlow<List<RoutingNode>> = _nodes.asStateFlow()

    @Volatile
    private var socksAuthCredentials: Pair<String, String>? = null

    fun setNodes(nodes: List<RoutingNode>) {
        _nodes.value = nodes
        val currentActiveId = _routingState.value.activeNode?.id
        if (currentActiveId != null) {
            val updated = nodes.firstOrNull { it.id == currentActiveId }
            if (updated == null) {
                _routingState.update {
                    it.copy(
                        isConnected = false,
                        isConnecting = false,
                        activeNode = null,
                        statusMessage = "Direct Connection (No Proxy)"
                    )
                }
            } else {
                _routingState.update { it.copy(activeNode = updated) }
            }
        }
    }

    fun setTunnelMode(useVpnTunnel: Boolean) {
        _routingState.update { it.copy(useVpnTunnel = useVpnTunnel) }
    }

    fun prepareVpnIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    fun connectNode(context: Context, node: RoutingNode, useVpnTunnel: Boolean = _routingState.value.useVpnTunnel) {
        configureSocksAuthenticator(node)
        if (!useVpnTunnel) {
            _routingState.update {
                it.copy(
                    isConnected = true,
                    isConnecting = false,
                    useVpnTunnel = false,
                    activeNode = node,
                    statusMessage = "App Proxy Active: ${node.countryName} (${node.protocol.label} ${node.host}:${node.port})"
                )
            }
            return
        }

        _routingState.update {
            it.copy(
                isConnecting = true,
                useVpnTunnel = true,
                activeNode = node,
                statusMessage = "Starting VPN tunnel to ${node.countryName}..."
            )
        }

        val intent = Intent(context, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_CONNECT
            putExtra(TunnelVpnService.EXTRA_NODE_ID, node.id)
            putExtra(TunnelVpnService.EXTRA_NODE_NAME, node.name)
            putExtra(TunnelVpnService.EXTRA_COUNTRY_NAME, node.countryName)
            putExtra(TunnelVpnService.EXTRA_HOST, node.host)
            putExtra(TunnelVpnService.EXTRA_PORT, node.port)
            putExtra(TunnelVpnService.EXTRA_PROTOCOL, node.protocol.name)
            putExtra(TunnelVpnService.EXTRA_USERNAME, node.username)
            putExtra(TunnelVpnService.EXTRA_PASSWORD, node.password)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun disconnect(context: Context) {
        socksAuthCredentials = null
        Authenticator.setDefault(null)
        if (_routingState.value.useVpnTunnel) {
            val intent = Intent(context, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)
        }
        onTunnelDisconnected("Direct Connection (No Proxy)")
    }

    internal fun onTunnelConnected(nodeName: String, countryName: String, host: String, port: Int, protocol: String) {
        _routingState.update {
            it.copy(
                isConnected = true,
                isConnecting = false,
                statusMessage = "VPN Active: $countryName • $nodeName ($protocol $host:$port)"
            )
        }
    }

    internal fun onTunnelError(message: String) {
        _routingState.update {
            it.copy(
                isConnected = false,
                isConnecting = false,
                statusMessage = "Tunnel Error: $message"
            )
        }
    }

    internal fun onTunnelDisconnected(reason: String = "Direct Connection (No Proxy)") {
        _routingState.update {
            it.copy(
                isConnected = false,
                isConnecting = false,
                activeNode = null,
                statusMessage = reason,
                lastLatencyMs = null
            )
        }
    }

    private fun configureSocksAuthenticator(node: RoutingNode) {
        if (node.protocol == ProxyProtocol.SOCKS5 && node.username.isNotBlank()) {
            socksAuthCredentials = node.username to node.password
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    val creds = socksAuthCredentials ?: return null
                    return PasswordAuthentication(creds.first, creds.second.toCharArray())
                }
            })
        } else {
            socksAuthCredentials = null
            Authenticator.setDefault(null)
        }
    }

    /**
     * Builds an OkHttpClient configured with the active HTTP/SOCKS5 proxy routing node
     * and optional Stalker/stream headers. Used by API services, Coil image loading, and Media3.
     */
    fun buildHttpClient(
        customHeaders: Map<String, String> = emptyMap(),
        connectTimeoutSec: Long = 15L,
        readTimeoutSec: Long = 25L,
        overrideNode: RoutingNode? = null
    ): OkHttpClient {
        val state = _routingState.value
        val activeNode = overrideNode ?: if (state.isConnected) state.activeNode else null

        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(20L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)

        if (activeNode != null && activeNode.host.isNotBlank() && activeNode.port in 1..65535) {
            val proxyType = when (activeNode.protocol) {
                ProxyProtocol.HTTP -> Proxy.Type.HTTP
                ProxyProtocol.SOCKS5 -> Proxy.Type.SOCKS
            }
            builder.proxy(Proxy(proxyType, InetSocketAddress.createUnresolved(activeNode.host, activeNode.port)))

            if (activeNode.protocol == ProxyProtocol.HTTP && activeNode.username.isNotBlank()) {
                val credential = Credentials.basic(activeNode.username, activeNode.password)
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
            }
        }

        if (customHeaders.isNotEmpty()) {
            builder.addInterceptor { chain ->
                val reqBuilder = chain.request().newBuilder()
                customHeaders.forEach { (key, value) ->
                    if (key.isNotBlank() && value.isNotBlank()) {
                        reqBuilder.header(key, value)
                    }
                }
                chain.proceed(reqBuilder.build())
            }
        }

        return builder.build()
    }

    /**
     * Probes TCP/HTTP reachability through a candidate RoutingNode and measures latency.
     */
    suspend fun testNodeLatency(node: RoutingNode): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            configureSocksAuthenticator(node)
            val start = System.currentTimeMillis()
            val client = buildHttpClient(
                connectTimeoutSec = 8L,
                readTimeoutSec = 8L,
                overrideNode = node
            )
            val request = Request.Builder()
                .url("https://clients3.google.com/generate_204")
                .head()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 204) {
                    error("Proxy returned HTTP ${response.code}")
                }
            }
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            if (_routingState.value.activeNode?.id == node.id) {
                _routingState.update { it.copy(lastLatencyMs = elapsed) }
            }
            elapsed
        }
    }
}
