package com.example.routing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.models.ProxyProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer

class TunnelVpnService : VpnService() {

    companion object {
        private const val TAG = "TunnelVpnService"
        const val ACTION_CONNECT = "com.example.routing.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.routing.ACTION_DISCONNECT"

        const val EXTRA_NODE_ID = "extra_node_id"
        const val EXTRA_NODE_NAME = "extra_node_name"
        const val EXTRA_COUNTRY_NAME = "extra_country_name"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"

        private const val NOTIFICATION_CHANNEL_ID = "tiviwave_vpn_routing"
        private const val NOTIFICATION_ID = 4102
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel("Disconnected by user")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CONNECT -> {
                val nodeName = intent.getStringExtra(EXTRA_NODE_NAME).orEmpty().ifBlank { "Proxy Node" }
                val countryName = intent.getStringExtra(EXTRA_COUNTRY_NAME).orEmpty().ifBlank { "Global" }
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val protocolRaw = intent.getStringExtra(EXTRA_PROTOCOL) ?: ProxyProtocol.HTTP.name

                if (host.isBlank() || port !in 1..65535) {
                    RoutingManager.onTunnelError("Invalid proxy host or port")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithNotification(
                    title = getString(R.string.vpn_notification_title),
                    content = "$countryName • $nodeName ($protocolRaw $host:$port)"
                )
                establishTunnel(nodeName, countryName, host, port, protocolRaw)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun establishTunnel(
        nodeName: String,
        countryName: String,
        host: String,
        port: Int,
        protocolRaw: String
    ) {
        closeTunnelInterface()
        try {
            val builder = Builder()
                .setSession("TiviWave-$countryName")
                .setMtu(1500)
                .addAddress("10.214.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setBlocking(false)

            // Attach HTTP proxy metadata to the VPN interface on API 29+ when HTTP proxy is used.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && protocolRaw == ProxyProtocol.HTTP.name) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port))
            }

            // Exclude our own app from the raw TUN loop so OkHttpClient / Media3 OkHttpDataSource
            // can connect directly to the upstream proxy socket without recursive loopback.
            runCatching { builder.addDisallowedApplication(packageName) }

            val pfd = builder.establish()
            if (pfd == null) {
                RoutingManager.onTunnelError("VPN permission was revoked or unavailable")
                stopSelf()
                return
            }

            vpnInterface = pfd
            RoutingManager.onTunnelConnected(nodeName, countryName, host, port, protocolRaw)

            tunnelJob = serviceScope.launch {
                monitorTunnelInterface(pfd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN tunnel", e)
            RoutingManager.onTunnelError(e.message ?: "Unable to establish VPN interface")
            stopSelf()
        }
    }

    private fun monitorTunnelInterface(pfd: ParcelFileDescriptor) {
        val buffer = ByteBuffer.allocate(32767)
        try {
            FileInputStream(pfd.fileDescriptor).use { input ->
                while (serviceScope.isActive && vpnInterface != null) {
                    val bytesRead = input.read(buffer.array())
                    if (bytesRead < 0) {
                        break
                    }
                    if (bytesRead == 0) {
                        Thread.sleep(120)
                    } else {
                        buffer.clear()
                    }
                }
            }
        } catch (e: Exception) {
            if (vpnInterface != null) {
                Log.w(TAG, "Tunnel monitor interrupted: ${e.message}")
            }
        }
    }

    private fun startForegroundWithNotification(title: String, content: String) {
        ensureNotificationChannel()
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.action_disconnect), disconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.vpn_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.vpn_notification_text)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun closeTunnelInterface() {
        tunnelJob?.cancel()
        tunnelJob = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun stopTunnel(reason: String) {
        closeTunnelInterface()
        stopForeground(STOP_FOREGROUND_REMOVE)
        RoutingManager.onTunnelDisconnected(reason)
    }

    override fun onRevoke() {
        stopTunnel("VPN permission revoked by system")
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnelInterface()
        serviceScope.cancel()
        super.onDestroy()
    }
}
