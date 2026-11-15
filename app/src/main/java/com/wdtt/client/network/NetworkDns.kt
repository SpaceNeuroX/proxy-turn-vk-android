package com.wdtt.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * DNS, который выдаёт текущая сеть: мобильный оператор или роутер Wi-Fi.
 *
 * Берём у реальной сети (не у VPN): у сети с VPN-транспортом свой DNS
 * туннеля. Само приложение исключено из VPN, поэтому Go-клиент ходит к
 * этим адресам напрямую — даже к внутренним адресам оператора вида 10.x.
 */
object NetworkDns {

    fun current(context: Context): List<String> {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = underlyingNetwork(cm) ?: return emptyList()
        val servers = runCatching { cm.getLinkProperties(network)?.dnsServers }.getOrNull().orEmpty()
        return servers
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            // IPv4 вперёд: IPv6-маршрута до DNS оператора может не быть.
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { addr ->
                when (addr) {
                    is Inet4Address -> addr.hostAddress
                    is Inet6Address -> addr.hostAddress?.substringBefore('%')
                    else -> null
                }
            }
            .distinct()
    }

    /**
     * Сеть, через которую реально идёт трафик приложения. Приложение
     * исключено из VPN, поэтому для него activeNetwork — это и есть
     * мобильная сеть или Wi-Fi; если всё же вернулся VPN — ищем сами.
     */
    private fun underlyingNetwork(cm: ConnectivityManager): Network? {
        cm.activeNetwork?.let { active ->
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return active
        }
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return@mapNotNull null
            n to caps
        }
        // Рабочая (validated) сеть вперёд; среди них — как система: Wi-Fi, затем мобильная.
        return candidates.sortedWith(
            compareByDescending<Pair<Network, NetworkCapabilities>> {
                it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.thenByDescending { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        ).firstOrNull()?.first
    }
}
