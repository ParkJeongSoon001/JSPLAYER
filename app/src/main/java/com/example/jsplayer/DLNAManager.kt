package com.example.jsplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import org.jupnp.android.AndroidUpnpService
import org.jupnp.android.AndroidUpnpServiceImpl
import org.jupnp.model.meta.Device
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry

class DLNAManager(
    private val context: Context,
    private val onDeviceAdded: (Device<*, *, *>) -> Unit,
    private val onDeviceRemoved: (Device<*, *, *>) -> Unit
) {
    private var upnpService: AndroidUpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val registryListener = object : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry, device: org.jupnp.model.meta.RemoteDevice) {
            Log.d("DLNAManager", "Remote device added: ${device.displayString}")
            onDeviceAdded(device)
        }

        override fun remoteDeviceRemoved(registry: Registry, device: org.jupnp.model.meta.RemoteDevice) {
            Log.d("DLNAManager", "Remote device removed: ${device.displayString}")
            onDeviceRemoved(device)
        }

        override fun localDeviceAdded(registry: Registry, device: org.jupnp.model.meta.LocalDevice) {
            Log.d("DLNAManager", "Local device added: ${device.displayString}")
            onDeviceAdded(device)
        }

        override fun localDeviceRemoved(registry: Registry, device: org.jupnp.model.meta.LocalDevice) {
            Log.d("DLNAManager", "Local device removed: ${device.displayString}")
            onDeviceRemoved(device)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d("DLNAManager", "UPnP Service Connected.")
            upnpService = service as AndroidUpnpService
            
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
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d("DLNAManager", "UPnP Service Disconnected.")
            upnpService = null
        }
    }

    fun start() {
        // Required for Wi-Fi multicast packets to be received
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("ClingMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        context.bindService(
            Intent(context, AndroidUpnpServiceImpl::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun stop() {
        upnpService?.let {
            it.registry.removeListener(registryListener)
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
        upnpService?.controlPoint?.search()
    }
}
