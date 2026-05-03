package com.mirrifytv.shared.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getWifiIpAddress(context: Context): String? {
        return try {
            // Primary method: WifiManager
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            @Suppress("DEPRECATION")
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return formatIpAddress(ipInt)
            }
            // Fallback: enumerate network interfaces
            getIpFromInterfaces()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi IP", e)
            getIpFromInterfaces()
        }
    }

    private fun formatIpAddress(ipInt: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt shr 24 and 0xff
        )
    }

    private fun getIpFromInterfaces(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { addr ->
                    !addr.isLoopbackAddress && addr is Inet4Address
                }?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating interfaces", e)
            null
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
