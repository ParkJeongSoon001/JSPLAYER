package com.sv21c.jsplayer

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class DiscoveredServer(
    val ip: String,
    val hostname: String,    // NetBIOS name or IP
    val type: String,        // "SMB" or "WEBDAV"
    val port: Int = 0,
    val path: String = ""    // WebDAV 기본 경로
)

/**
 * 로컬 서브넷을 스캔하여 SMB / WebDAV 서버를 검색합니다.
 * - SMB: TCP 445, 139 포트
 * - WebDAV: TCP 80, 8080, 5005, 5006, 8888, 2049 포트 + "/dav", "/webdav", "/" 경로 탐색
 */
object NetworkScanner {
    private const val TAG = "NetworkScanner"
    private const val CONNECT_TIMEOUT_MS = 600  // 빠른 스캔을 위해 600ms

    /** 현재 기기의 Wi-Fi IP를 가져옴 */
    fun getLocalIpAddress(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null
            else String.format(
                "%d.%d.%d.%d",
                ip and 0xFF,
                (ip shr 8) and 0xFF,
                (ip shr 16) and 0xFF,
                (ip shr 24) and 0xFF
            )
        } catch (e: Exception) {
            Log.w(TAG, "getLocalIpAddress failed: ${e.message}")
            null
        }
    }

    /** 서브넷 접두사 추출: "192.168.0.5" → "192.168.0." */
    private fun subnetPrefix(ip: String): String = ip.substringBeforeLast('.') + "."

    /** 단일 호스트의 특정 포트가 열려있는지 확인 */
    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * SMB 서버 검색 (TCP 445 또는 139)
     * @param onProgress 진행 상황 콜백 (0..100)
     * @param onFound 서버 발견 시 콜백
     */
    fun scanForSmb(
        context: Context,
        onProgress: (Int) -> Unit,
        onFound: (DiscoveredServer) -> Unit,
        onComplete: () -> Unit
    ) {
        val localIp = getLocalIpAddress(context)
        if (localIp == null) {
            Log.w(TAG, "Cannot determine local IP for SMB scan")
            onComplete()
            return
        }
        val prefix = subnetPrefix(localIp)
        Log.d(TAG, "Scanning SMB on subnet ${prefix}0/24")

        val total = 254
        var checked = 0
        val threads = mutableListOf<Thread>()

        for (i in 1..254) {
            val ip = "$prefix$i"
            val t = Thread {
                if (isPortOpen(ip, 445) || isPortOpen(ip, 139)) {
                    val hostname = try {
                        InetAddress.getByName(ip).hostName.also {
                            if (it == ip) ip else it
                        }
                    } catch (e: Exception) { ip }
                    val server = DiscoveredServer(ip, hostname, "SMB", 445, "smb://$ip/")
                    Log.d(TAG, "SMB found: $ip ($hostname)")
                    onFound(server)
                }
                synchronized(this) {
                    checked++
                    onProgress((checked * 100) / total)
                }
            }
            threads.add(t)
            t.start()
        }
        threads.forEach { it.join() }
        onComplete()
    }

    /**
     * WebDAV 서버 검색 (TCP 80, 8080, 5005, 5006, 8888)
     */
    fun scanForWebDav(
        context: Context,
        onProgress: (Int) -> Unit,
        onFound: (DiscoveredServer) -> Unit,
        onComplete: () -> Unit
    ) {
        val localIp = getLocalIpAddress(context)
        if (localIp == null) {
            Log.w(TAG, "Cannot determine local IP for WebDAV scan")
            onComplete()
            return
        }
        val prefix = subnetPrefix(localIp)
        val webDavPorts = listOf(5005, 5006, 8888, 8080, 80, 443)
        Log.d(TAG, "Scanning WebDAV on subnet ${prefix}0/24")

        val total = 254
        var checked = 0
        val threads = mutableListOf<Thread>()
        val foundIps = mutableSetOf<String>() // 중복 방지

        for (i in 1..254) {
            val ip = "$prefix$i"
            val t = Thread {
                for (port in webDavPorts) {
                    if (isPortOpen(ip, port)) {
                        synchronized(foundIps) {
                            if (!foundIps.contains(ip)) {
                                foundIps.add(ip)
                                val hostname = try {
                                    InetAddress.getByName(ip).hostName
                                } catch (e: Exception) { ip }
                                val proto = if (port == 443 || port == 5006) "https" else "http"
                                val server = DiscoveredServer(
                                    ip, hostname, "WEBDAV", port,
                                    "$proto://$ip${if (port != 80 && port != 443) ":$port" else ""}/"
                                )
                                Log.d(TAG, "WebDAV candidate found: $ip:$port ($hostname)")

                                onFound(server)
                            }
                        }
                        break // 첫 번째 열린 포트만 사용
                    }
                }
                synchronized(this) {
                    checked++
                    onProgress((checked * 100) / total)
                }
            }
            threads.add(t)
            t.start()
        }
        threads.forEach { it.join() }
        onComplete()
    }
}
