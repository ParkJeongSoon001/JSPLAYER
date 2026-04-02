package com.sv21c.jsplayer

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import org.jupnp.android.AndroidUpnpService
import org.jupnp.android.AndroidRouter
import org.jupnp.android.AndroidUpnpServiceConfiguration
import org.jupnp.UpnpServiceConfiguration
import org.jupnp.model.meta.Device
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import java.net.URL
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.types.UDN
import org.jupnp.model.meta.RemoteDeviceIdentity
import java.util.concurrent.ConcurrentHashMap
import org.jupnp.transport.spi.NetworkAddressFactory
import java.net.NetworkInterface
import org.jupnp.UpnpService
import org.jupnp.protocol.ProtocolFactory
import org.jupnp.UpnpServiceImpl
import org.jupnp.transport.Router

class DLNAManager(
    private val context: Context,
    private val onDeviceAdded: (Device<*, *, *>) -> Unit,
    private val onDeviceRemoved: (Device<*, *, *>) -> Unit
) {
    private var upnpService: AndroidUpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val discoveredUrls = ConcurrentHashMap.newKeySet<String>()

    private var isServiceBound = false
    private var pendingSearch = false

    private val registryListener = object : DefaultRegistryListener() {
        override fun remoteDeviceDiscoveryStarted(registry: Registry, device: org.jupnp.model.meta.RemoteDevice) {
            Log.d("DLNAManager", ">>> Discovery STARTED for: ${device.identity.udn} at ${device.identity.descriptorURL}")
        }

        override fun remoteDeviceDiscoveryFailed(registry: Registry, device: org.jupnp.model.meta.RemoteDevice, ex: Exception?) {
            Log.e("DLNAManager", "!!! Discovery FAILED for: ${device.identity.udn}. Error: ${ex?.message}", ex)
        }

        override fun remoteDeviceAdded(registry: Registry, device: org.jupnp.model.meta.RemoteDevice) {
            Log.d("DLNAManager", ">>> Remote device fully parsed & added: ${device.displayString} (${device.identity.udn})")
            onDeviceAdded(device)
            // Track its URL if it has one
            device.identity.descriptorURL?.toString()?.let { discoveredUrls.add(it) }
        }

        override fun remoteDeviceRemoved(registry: Registry, device: org.jupnp.model.meta.RemoteDevice) {
            Log.d("DLNAManager", "<<< Remote device removed: ${device.displayString}")
            onDeviceRemoved(device)
        }

        override fun localDeviceAdded(registry: Registry, device: org.jupnp.model.meta.LocalDevice) {
            Log.d("DLNAManager", ">>> Local device added: ${device.displayString}")
            onDeviceAdded(device)
        }

        override fun localDeviceRemoved(registry: Registry, device: org.jupnp.model.meta.LocalDevice) {
            Log.d("DLNAManager", "<<< Local device removed: ${device.displayString}")
            onDeviceRemoved(device)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d("DLNAManager", "UPnP Service Connected!")
            val binder = service as AndroidUpnpService
            upnpService = binder
            isServiceBound = true

            binder.registry.addListener(registryListener)

            // Execute search if it was requested before binding completed
            if (pendingSearch) {
                Log.d("DLNAManager", "Executing pending search after service bound")
                pendingSearch = false
                search()
            } else {
                // Let it find everything automatically if no explicit search was pending
                upnpService?.controlPoint?.search()
            }

            try {
                // Force every UPnP device on the network to respond
                upnpService?.controlPoint?.search(org.jupnp.model.message.header.STAllHeader())
                // Specific MediaServer search
                val type = org.jupnp.model.types.UDADeviceType("MediaServer", 1)
                upnpService?.controlPoint?.search(org.jupnp.model.message.header.UDADeviceTypeHeader(type))
            } catch (e: Exception) {
                Log.e("DLNAManager", "Error searching specific MediaServer", e)
            }
            
            // --- 1. DIRECT RAW UDP SSDP TEST (Bypass JUPnP completely) ---
            Thread {
                try {
                    Log.d("DLNAManager", "Starting raw Java SSDP M-SEARCH...")
                    val socket = java.net.MulticastSocket()
                    
                    // Find an appropriate Wi-Fi interface (wlan0, wlan1, etc.)
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    var wifiInterface: java.net.NetworkInterface? = null
                    while (interfaces.hasMoreElements()) {
                        val intf = interfaces.nextElement()
                        // Check for 'wlan' interface that is up and supports multicast
                        if (intf.isUp && intf.supportsMulticast() && intf.name.startsWith("wlan")) {
                            // Prefer the one that has an assigned IPv4 address
                            val hasIpv4 = intf.inetAddresses.asSequence().any { !it.isLoopbackAddress && it.hostAddress.contains(".") }
                            if (hasIpv4) {
                                wifiInterface = intf
                                break
                            }
                            wifiInterface = intf // Fallback if no IPv4 yet
                        }
                    }
                    
                    if (wifiInterface != null) {
                        Log.d("DLNAManager", "Raw socket specifically binding to ${wifiInterface.name}...")
                        socket.networkInterface = wifiInterface
                    } else {
                        Log.d("DLNAManager", "No active wlan interface found, using default interface...")
                    }
                    
                    socket.timeToLive = 4
                    socket.soTimeout = 5000
                    
                    val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 3\r\n" +
                            "ST: ssdp:all\r\n\r\n"
                            
                    val data = ssdpMessage.toByteArray()
                    val address = java.net.InetAddress.getByName("239.255.255.250")
                    val packet = java.net.DatagramPacket(data, data.size, address, 1900)
                    
                    socket.send(packet)
                    Log.d("DLNAManager", "Raw SSDP packet sent via Wi-Fi. Listening for 5 seconds...")
                    
                    val buffer = ByteArray(8192)
                    while (true) {
                        val receivePacket = java.net.DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(receivePacket)
                            val response = String(receivePacket.data, 0, receivePacket.length)
                            Log.d("DLNAManager", "RAW SSDP Response from [${receivePacket.address.hostAddress}]: \n$response")
                            
                            // Extract Location header (case-insensitive)
                            val location = response.lines()
                                .find { it.trim().startsWith("LOCATION:", ignoreCase = true) }
                                ?.substringAfter(":")?.trim()
                            
                            // Extract USN to get the real UDN
                            val usn = response.lines()
                                .find { it.trim().startsWith("USN:", ignoreCase = true) }
                                ?.substringAfter(":")?.trim()
                            
                            val udnStr = usn?.substringBefore("::")?.trim()

                            // Extract ST/Search Target to check if it's a MediaServer
                            val st = response.lines()
                                .find { it.trim().startsWith("ST:", ignoreCase = true) }
                                ?.substringAfter(":")?.trim()
                            
                            if (location != null) {
                                onDeviceURLDiscovered(location, udnStr, st)
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            Log.d("DLNAManager", "Raw SSDP search timeout reached (5s).")
                            break
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    Log.e("DLNAManager", "Raw SSDP Error", e)
                }
            }.start()

            // Get all devices already in the registry
            val upnpServiceNonNull = upnpService ?: return
            
            Log.d("DLNAManager", "Registry listener adding...")
            upnpServiceNonNull.registry?.let { registry ->
                registry.addListener(registryListener)
                for (device in registry.devices) {
                    onDeviceAdded(device)
                }
            }
            
            Log.d("DLNAManager", "Initiating search...")
            upnpServiceNonNull.controlPoint?.search()
            
            try {
                val type = org.jupnp.model.types.UDADeviceType("MediaServer", 1)
                upnpServiceNonNull.controlPoint?.search(org.jupnp.model.message.header.UDADeviceTypeHeader(type))
            } catch (e: Exception) {
                Log.e("DLNAManager", "Error searching specific MediaServer", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d("DLNAManager", "UPnP Service Disconnected.")
            upnpService = null
            isServiceBound = false
        }
    }

    fun start() {
        // Required for Wi-Fi multicast packets to be received
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("ClingMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        try {
            Log.d("DLNAManager", "--- Network Interfaces Available to Java ---")
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val isUp = intf.isUp
                val supportsMulticast = intf.supportsMulticast()
                val ips = intf.inetAddresses.toList().joinToString { it.hostAddress ?: "null" }
                Log.d("DLNAManager", "Interface: ${intf.name}, isUp: $isUp, multicast: $supportsMulticast, IPs: [$ips]")
            }
            Log.d("DLNAManager", "--------------------------------------------")
        } catch (e: Exception) {
            Log.e("DLNAManager", "Error printing network interfaces", e)
        }

        context.bindService(
            Intent(context, MyUpnpService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun stop() {
        try {
            upnpService?.registry?.removeListener(registryListener)
        } catch (e: Exception) {
            Log.e("DLNAManager", "Error removing registry listener", e)
        }
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.e("DLNAManager", "Error unbinding service", e)
        }
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
    }

    fun search() {
        Log.d("DLNAManager", "Manual search requested")

        // Reset our own tracked list
        discoveredUrls.clear()

        // --- 2. JUPNP NORMAL SEARCH ---
        val upnpServiceNonNull = upnpService
        if (upnpServiceNonNull == null || !isServiceBound) {
            Log.w("DLNAManager", "Cannot search yet: upnpService is null or not bound. Flagging pendingSearch.")
            pendingSearch = true
            return
        }
        
        // Properly drop old state to force re-discovery
        upnpServiceNonNull.registry.removeAllRemoteDevices()
        
        upnpServiceNonNull.controlPoint?.search()
        
        try {
            // Force every UPnP device on the network to respond
            upnpServiceNonNull.controlPoint?.search(org.jupnp.model.message.header.STAllHeader())
            // Specific MediaServer search
            val type = org.jupnp.model.types.UDADeviceType("MediaServer", 1)
            upnpServiceNonNull.controlPoint?.search(org.jupnp.model.message.header.UDADeviceTypeHeader(type))
        } catch (e: Exception) {
            Log.e("DLNAManager", "Error executing upnp search", e)
        }
    }

    fun browse(
        device: Device<*, *, *>,
        containerId: String,
        callback: (org.jupnp.support.model.DIDLContent?, String?) -> Unit
    ) {
        val upnpServiceNonNull = upnpService
        if (upnpServiceNonNull == null || !isServiceBound) {
            callback(null, "UPnP Service is not bound.")
            return
        }

        val service = device.findService(org.jupnp.model.types.UDAServiceType("ContentDirectory"))
        if (service == null) {
            callback(null, "ContentDirectory service not found on device.")
            return
        }

        val browseAction = object : org.jupnp.support.contentdirectory.callback.Browse(
            service, containerId, org.jupnp.support.model.BrowseFlag.DIRECT_CHILDREN
        ) {
            override fun received(actionInvocation: org.jupnp.model.action.ActionInvocation<out org.jupnp.model.meta.Service<*, *>>?, didl: org.jupnp.support.model.DIDLContent?) {
                Log.d("DLNAManager", "Browse SUCCESS for $containerId: Found ${didl?.containers?.size ?: 0} containers, ${didl?.items?.size ?: 0} items.")
                callback(didl, null)
            }

            override fun updateStatus(status: Status?) {
                Log.d("DLNAManager", "Browse status update: ${status?.defaultMessage}")
            }

            override fun failure(
                invocation: org.jupnp.model.action.ActionInvocation<out org.jupnp.model.meta.Service<*, *>>?,
                operation: org.jupnp.model.message.UpnpResponse?,
                defaultMsg: String?
            ) {
                Log.e("DLNAManager", "Browse FAILED! $defaultMsg")
                callback(null, defaultMsg ?: "Unknown error browsing folder")
            }
        }

        upnpServiceNonNull.controlPoint?.execute(browseAction)
    }

    private fun onDeviceURLDiscovered(location: String, udnStr: String?, st: String?) {
        if (discoveredUrls.contains(location)) return
        
        Log.d("DLNAManager", "New Device URL discovered via Raw SSDP: $location (UDN: $udnStr, ST: $st)")
        
        // Filter: Allow MediaServer, Basic (Synology), Windows (WdNAS), or if ST is missing
        val stLower = st?.lowercase() ?: ""
        val isPotentiallyMediaServer = stLower.contains("mediaserver") || 
                                       stLower.contains("basic") || 
                                       stLower.contains("wdnas") ||
                                       stLower.isEmpty() ||
                                       stLower.contains("upnp:rootdevice")
        
        if (!isPotentiallyMediaServer) {
            Log.d("DLNAManager", "Skipping manual injection for non-relevant device ST: $st")
            return
        }

        upnpService?.let { service ->
            try {
                val url = java.net.URL(location)
// ... (rest of the logic)
                
                // Use the UDN from SSDP if available, otherwise generate stable UUID
                val udn = if (udnStr != null) {
                    try {
                        org.jupnp.model.types.UDN.valueOf(udnStr)
                    } catch (e: Exception) {
                        val uuid = java.util.UUID.nameUUIDFromBytes(url.toString().toByteArray())
                        org.jupnp.model.types.UDN(uuid)
                    }
                } else {
                    val uuid = java.util.UUID.nameUUIDFromBytes(url.toString().toByteArray())
                    org.jupnp.model.types.UDN(uuid)
                }
                
                // Check if already in registry
                if (service.registry?.getDevice(udn, false) != null) {
                    discoveredUrls.add(location)
                    return
                }

                Log.d("DLNAManager", "Notifying JUPnP to fetch XML for: $location with UDN: $udn")
                // For a remote device, we must use RemoteDeviceIdentity which takes a descriptor URL.
                val identity = org.jupnp.model.meta.RemoteDeviceIdentity(udn, 1800, url, null, null)
                val skeletonDevice = org.jupnp.model.meta.RemoteDevice(identity)
                
                service.registry?.notifyDiscoveryStart(skeletonDevice)
                discoveredUrls.add(location)
                Log.d("DLNAManager", "Notified discovery start. JUPnP will fetch XML async...")
            } catch (e: Exception) {
                Log.e("DLNAManager", "Failed to manually add device for $location: ${e.message}")
            }
        }
    }
}

class MyUpnpService : Service() {
    private var upnpService: UpnpService? = null
    private val initializationLatch = java.util.concurrent.CountDownLatch(1)
    private val binder = MyBinder()

    inner class MyBinder : Binder(), AndroidUpnpService {
        override fun get(): UpnpService? {
            initializationLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return upnpService
        }
        override fun getConfiguration(): UpnpServiceConfiguration? {
            initializationLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return upnpService?.configuration
        }
        override fun getRegistry(): Registry? {
            initializationLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return upnpService?.registry
        }
        override fun getControlPoint(): org.jupnp.controlpoint.ControlPoint? {
            initializationLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return upnpService?.controlPoint
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // Force IPv4 via system properties
            System.setProperty("java.net.preferIPv4Stack", "true")
            // System.setProperty("org.jupnp.network.useInterfaces", "wlan0") // REMOVED: Allow auto-detection for tablet/wlan1 support
            
            
            val config = object : AndroidUpnpServiceConfiguration() {
                override fun createNetworkAddressFactory(): NetworkAddressFactory {
                    Log.d("DLNAManager", ">>> Manual Config.createNetworkAddressFactory CALLED")
                    return super.createNetworkAddressFactory()
                }
            }
            
            upnpService = object : UpnpServiceImpl(config) {
                override fun createRouter(protocolFactory: ProtocolFactory, registry: Registry): Router {
                    Log.d("DLNAManager", ">>> Manual UpnpServiceImpl.createRouter CALLED")
                    // Note: AndroidRouter(Config, ProtocolFactory, Context)
                    return AndroidRouter(getConfiguration(), protocolFactory, this@MyUpnpService)
                }
            }
            
            upnpService?.startup()
            Log.d("DLNAManager", "MyUpnpService: Manual JUPnP stack STARTED.")
        } catch (e: Exception) {
            Log.e("DLNAManager", "MyUpnpService: Manual startup FAILED", e)
        } finally {
            initializationLatch.countDown()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d("DLNAManager", "MyUpnpService (Manual) onDestroy starting...")
        try {
            upnpService?.shutdown()
        } catch (e: Exception) {
            Log.e("DLNAManager", "Error during JUPnP shutdown", e)
        }
        super.onDestroy()
    }
}
