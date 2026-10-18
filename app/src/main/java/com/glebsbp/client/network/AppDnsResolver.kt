package com.glebsbp.client

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A-резолв для обхода VPN: ответы нескольких резолверов объединяются.
 * «DNS для VK» и публичные CF/Google/Quad9 могут отдавать разные A,
 * а браузер ходит своим DNS — нужны все IP, иначе сайт уйдёт в туннель.
 */
object AppDnsResolver {
    private const val TAG = "AppDnsResolver"
    private const val DNS_PORT = 53
    private val dnsMessageType = "application/dns-message".toMediaType()

    private const val PROBE_HOST = "example.com"
    private const val UDP_PROBE_TIMEOUT_MS = 600
    private const val UDP_PROBE_TTL_MS = 30_000L

    private val udpReachabilityCache =
        ConcurrentHashMap<String, Pair<Long, Boolean>>() // serverIp -> (checkedAtMs, reachable)

    /** Доп. UDP DNS: браузер часто резолвит не через «DNS для VK». */
    private val bypassExtraUdpServers = listOf("1.1.1.1", "8.8.8.8")

    private fun isUdpServerReachable(serverIp: String): Boolean {
        val now = System.currentTimeMillis()
        val cached = udpReachabilityCache[serverIp]
        if (cached != null && now - cached.first < UDP_PROBE_TTL_MS) return cached.second

        val reachable = runCatching {
            queryUdpA(serverIp, PROBE_HOST, UDP_PROBE_TIMEOUT_MS).isNotEmpty()
        }.getOrDefault(false)

        udpReachabilityCache[serverIp] = now to reachable
        return reachable
    }

    fun lookupAForBypass(goDnsArg: String, hostname: String, timeoutMs: Int = 2500): Set<Ipv4Cidr> {
        val host = hostname.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return emptySet()

        val out = linkedSetOf<Ipv4Cidr>()
        val isDoh = SettingsStore.isDohGoDnsPreset(
            SettingsStore.goDnsDisplayFromArg(goDnsArg).preset
        ) || goDnsArg.trim().startsWith("doh:", ignoreCase = true)

        if (isDoh) {
            out.addAll(lookupViaDoh(goDnsArg, host, timeoutMs))
        } else {
            out.addAll(lookupViaUdp(goDnsArg, host, timeoutMs))
        }

        for (server in bypassExtraUdpServers) {
            if (!isUdpServerReachable(server)) continue
            val addrs = runCatching { queryUdpA(server, host, timeoutMs) }
                .onFailure { Log.w(TAG, "bypass UDP $server failed for $host: ${it.message}") }
                .getOrDefault(emptySet())
            out.addAll(addrs)
        }

        Log.d(TAG, "bypass $host → ${out.joinToString { it.toString() }.ifEmpty { "(empty)" }}")
        return out
    }

    private fun lookupViaUdp(goDnsArg: String, hostname: String, timeoutMs: Int): Set<Ipv4Cidr> {
        val servers = SettingsStore.goDnsDisplayFromArg(goDnsArg).servers
            .ifEmpty { SettingsStore.goDnsDisplay("yandex").servers }
        val out = linkedSetOf<Ipv4Cidr>()
        for (server in servers) {
            if (!isUdpServerReachable(server)) continue
            val addrs = runCatching { queryUdpA(server, hostname, timeoutMs) }
                .onFailure { Log.w(TAG, "UDP DNS $server failed for $hostname: ${it.message}") }
                .getOrDefault(emptySet())
            out.addAll(addrs)
        }
        return out
    }

    private fun lookupViaDoh(goDnsArg: String, hostname: String, timeoutMs: Int): Set<Ipv4Cidr> {
        val endpoints = dohEndpoints(goDnsArg)
        val out = linkedSetOf<Ipv4Cidr>()
        for (endpoint in endpoints) {
            val addrs = runCatching { queryDohA(endpoint, hostname, timeoutMs) }
                .onFailure { Log.w(TAG, "DoH $endpoint failed for $hostname: ${it.message}") }
                .getOrDefault(emptySet())
            out.addAll(addrs)
        }
        return out
    }

    private fun dohEndpoints(goDnsArg: String): List<String> {
        val display = SettingsStore.goDnsDisplayFromArg(goDnsArg)
        return when (display.preset) {
            "doh-cloudflare" -> listOf(
                "https://1.1.1.1/dns-query",
                "https://cloudflare-dns.com/dns-query",
            )
            "doh-google" -> listOf(
                "https://8.8.8.8/dns-query",
                "https://dns.google/dns-query",
            )
            "doh-yandex" -> listOf(
                "https://77.88.8.8/dns-query",
                "https://common.dot.dns.yandex.net/dns-query",
            )
            "doh-custom" -> display.servers.map { resolveDohEndpoint(it) }
            else -> listOf(
                "https://77.88.8.8/dns-query",
                "https://common.dot.dns.yandex.net/dns-query",
            )
        }
    }

    private fun resolveDohEndpoint(target: String): String {
        val trimmed = target.trim()
        if (trimmed.startsWith("https://", ignoreCase = true)) return trimmed
        return when (trimmed.lowercase()) {
            "common.dot.dns.yandex.net", "dns.yandex.ru", "77.88.8.8", "77.88.8.1" ->
                "https://$trimmed/dns-query"
            else -> "https://$trimmed/dns-query"
        }
    }

    private fun queryUdpA(serverIp: String, hostname: String, timeoutMs: Int): Set<Ipv4Cidr> {
        val txId = Random.nextInt(0, 0x10000)
        val query = buildDnsQuery(txId, hostname)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs.coerceIn(500, 8000)
            val addr = InetAddress.getByName(serverIp)
            socket.send(DatagramPacket(query, query.size, addr, DNS_PORT))
            val buf = ByteArray(2048)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            return parseDnsARecords(response.data, response.length, txId)
        }
    }

    private fun queryDohA(endpoint: String, hostname: String, timeoutMs: Int): Set<Ipv4Cidr> {
        val txId = Random.nextInt(0, 0x10000)
        val query = buildDnsQuery(txId, hostname)
        val timeout = timeoutMs.coerceIn(500, 8000).toLong()
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout + 500, TimeUnit.MILLISECONDS)
            .build()

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .addHeader("Accept", "application/dns-message")
            .post(query.toRequestBody(dnsMessageType))
        dohHostHeader(endpoint)?.let { requestBuilder.header("Host", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return emptySet()
            val data = response.body?.bytes() ?: return emptySet()
            return parseDnsARecords(data, data.size, txId)
        }
    }

    private fun dohHostHeader(endpoint: String): String? {
        val host = try {
            URL(endpoint).host
        } catch (_: Exception) {
            return null
        }
        if (!host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) return null
        return when (host) {
            "1.1.1.1", "1.0.0.1" -> "cloudflare-dns.com"
            "8.8.8.8", "8.8.4.4" -> "dns.google"
            "77.88.8.8", "77.88.8.1" -> "common.dot.dns.yandex.net"
            else -> null
        }
    }

    private fun buildDnsQuery(txId: Int, hostname: String): ByteArray {
        val labels = hostname.trimEnd('.').split('.')
        val qnameSize = labels.sumOf { 1 + it.length } + 1
        val packet = ByteArray(12 + qnameSize + 4)
        packet[0] = ((txId shr 8) and 0xff).toByte()
        packet[1] = (txId and 0xff).toByte()
        packet[2] = 0x01 // RD
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x01 // QDCOUNT=1

        var offset = 12
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            packet[offset++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, packet, offset, bytes.size)
            offset += bytes.size
        }
        packet[offset++] = 0x00
        packet[offset++] = 0x00 // QTYPE A
        packet[offset++] = 0x01
        packet[offset++] = 0x00 // QCLASS IN
        packet[offset] = 0x01
        return packet
    }

    /** Разбор DNS-ответа: только A (type=1). */
    private fun parseDnsARecords(data: ByteArray, length: Int, txId: Int): Set<Ipv4Cidr> {
        if (length < 12) return emptySet()
        val respId = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        if (respId != txId) return emptySet()

        val flags = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        if ((flags and 0x8000) == 0) return emptySet()
        val rcode = flags and 0x000F
        if (rcode != 0) return emptySet()

        val qdCount = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        val anCount = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)

        var offset = 12
        repeat(qdCount) {
            offset = skipName(data, length, offset) ?: return emptySet()
            offset += 4 // QTYPE + QCLASS
            if (offset > length) return emptySet()
        }

        val out = linkedSetOf<Ipv4Cidr>()
        repeat(anCount) {
            offset = skipName(data, length, offset) ?: return out
            if (offset + 10 > length) return out
            val type = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            val rdLength = ((data[offset + 8].toInt() and 0xff) shl 8) or (data[offset + 9].toInt() and 0xff)
            offset += 10
            if (offset + rdLength > length) return out
            if (type == 1 && rdLength == 4) {
                val bytes = byteArrayOf(
                    data[offset],
                    data[offset + 1],
                    data[offset + 2],
                    data[offset + 3],
                )
                out.add(Ipv4Cidr.fromAddress(bytes))
            }
            offset += rdLength
        }
        return out
    }

    private fun skipName(data: ByteArray, length: Int, start: Int): Int? {
        var offset = start
        var jumped = false
        var guard = 0
        while (guard++ < 128) {
            if (offset >= length) return null
            val label = data[offset].toInt() and 0xff
            when {
                label == 0 -> return if (jumped) start + 2 else offset + 1
                (label and 0xC0) == 0xC0 -> {
                    // compression pointer
                    if (offset + 1 >= length) return null
                    return if (jumped) start + 2 else offset + 2
                }
                else -> {
                    offset += 1 + label
                    if (offset > length) return null
                }
            }
        }
        return null
    }
}
