package com.glebsbp.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Проверка DNS для VK: UDP A-запрос `login.vk.ru` на :53 или DoH POST (HTTP/2) на endpoint.
 */
object GoDnsProbe {
    private const val PROBE_HOST = "login.vk.ru"
    private const val DNS_PORT = 53
    private val dnsMessageType = "application/dns-message".toMediaType()

    data class Result(
        val reachable: Boolean,
        val title: String,
        val okHosts: List<String>,
        val failedHosts: List<String>,
        val detail: String = "",
    ) {
        val statusText: String
            get() = buildString {
                append(title)
                append(" [")
                if (okHosts.isNotEmpty()) {
                    append("OK: ")
                    append(okHosts.joinToString(", "))
                }
                if (failedHosts.isNotEmpty()) {
                    if (okHosts.isNotEmpty()) append(" | ")
                    append("FAIL: ")
                    append(failedHosts.joinToString(", "))
                }
                if (okHosts.isEmpty() && failedHosts.isEmpty()) {
                    append("список DNS пуст")
                }
                append("]")
                if (detail.isNotBlank()) {
                    append(" — ")
                    append(detail)
                }
            }
    }

    suspend fun check(goDnsArg: String, timeoutMs: Int = 2000): Result = withContext(Dispatchers.IO) {
        val parsed = SettingsStore.goDnsDisplayFromArg(goDnsArg)
        val targets = if (parsed.servers.isNotEmpty()) {
            parsed.servers
        } else if (SettingsStore.isDohGoDnsPreset(parsed.preset)) {
            SettingsStore.goDnsDisplay("doh-yandex").servers
        } else {
            SettingsStore.goDnsDisplay("yandex").servers
        }
        if (targets.isEmpty()) {
            return@withContext Result(
                reachable = false,
                title = parsed.title,
                okHosts = emptyList(),
                failedHosts = emptyList(),
                detail = "нет адресов",
            )
        }

        val isDoh = SettingsStore.isDohGoDnsPreset(parsed.preset) || goDnsArg.trim().startsWith("doh:", ignoreCase = true)
        val detail = if (isDoh) "DoH $PROBE_HOST" else "UDP DNS $PROBE_HOST"

        coroutineScope {
            val checks = targets.map { target ->
                async {
                    val ok = if (isDoh) {
                        probeDohEndpoint(resolveDohEndpoint(target), timeoutMs)
                    } else {
                        probeDnsServer(target, timeoutMs)
                    }
                    target to ok
                }
            }.awaitAll()

            val okHosts = checks.filter { it.second }.map { it.first }
            val failedHosts = checks.filter { !it.second }.map { it.first }
            Result(
                reachable = okHosts.isNotEmpty(),
                title = parsed.title,
                okHosts = okHosts,
                failedHosts = failedHosts,
                detail = detail,
            )
        }
    }

    suspend fun checkPreset(
        preset: String,
        customRaw: String = "",
        dohCustomRaw: String = "",
        timeoutMs: Int = 2000,
    ): Result {
        val arg = when (val normalized = SettingsStore.normalizeGoDnsPreset(preset)) {
            "custom" -> {
                val servers = SettingsStore.normalizeGoDnsServers(customRaw)
                if (servers.isNotEmpty()) "custom:$servers" else "yandex"
            }
            "doh-custom" -> {
                val urls = SettingsStore.normalizeGoDnsDohUrls(dohCustomRaw)
                if (urls.isNotEmpty()) "doh:$urls" else "doh-yandex"
            }
            else -> normalized
        }
        return check(arg, timeoutMs)
    }

    private fun resolveDohEndpoint(target: String): String {
        val trimmed = target.trim()
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return when (trimmed.lowercase()) {
            "common.dot.dns.yandex.net", "dns.yandex.ru", "77.88.8.8", "77.88.8.1" ->
                "https://$trimmed/dns-query"
            else -> "https://$trimmed/dns-query"
        }
    }

    private fun probeDohEndpoint(endpoint: String, timeoutMs: Int): Boolean {
        return try {
            val txId = Random.nextInt(0, 0x10000)
            val query = buildDnsQuery(txId, PROBE_HOST)
            val timeout = timeoutMs.coerceIn(500, 5000).toLong()
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
                if (!response.isSuccessful) return false
                val data = response.body?.bytes() ?: return false
                validateDnsResponse(data, txId)
            }
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun dohHostHeader(endpoint: String): String? {
        val host = try {
            URL(endpoint).host
        } catch (_: Exception) {
            return null
        }
        if (!host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) {
            return null
        }
        return when (host) {
            "1.1.1.1", "1.0.0.1" -> "cloudflare-dns.com"
            "8.8.8.8", "8.8.4.4" -> "dns.google"
            "77.88.8.8", "77.88.8.1" -> "common.dot.dns.yandex.net"
            else -> null
        }
    }

    private fun probeDnsServer(serverIp: String, timeoutMs: Int): Boolean {
        return try {
            val txId = Random.nextInt(0, 0x10000)
            val query = buildDnsQuery(txId, PROBE_HOST)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.coerceIn(500, 5000)
                val addr = InetAddress.getByName(serverIp)
                socket.send(DatagramPacket(query, query.size, addr, DNS_PORT))

                val buf = ByteArray(512)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                validateDnsResponse(response.data, txId, response.length)
            }
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun validateDnsResponse(data: ByteArray, txId: Int, length: Int = data.size): Boolean {
        if (length < 12) return false
        val respId = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        if (respId != txId) return false

        val flags = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        if ((flags and 0x8000) == 0) return false

        val rcode = flags and 0x000F
        return rcode == 0 || rcode == 3
    }

    private fun buildDnsQuery(txId: Int, hostname: String): ByteArray {
        val labels = hostname.trimEnd('.').split('.')
        val qnameSize = labels.sumOf { 1 + it.length } + 1
        val packet = ByteArray(12 + qnameSize + 4)
        packet[0] = ((txId shr 8) and 0xff).toByte()
        packet[1] = (txId and 0xff).toByte()
        packet[2] = 0x01
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x01

        var offset = 12
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            packet[offset++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, packet, offset, bytes.size)
            offset += bytes.size
        }
        packet[offset++] = 0x00
        packet[offset++] = 0x00
        packet[offset++] = 0x01
        packet[offset++] = 0x00
        packet[offset] = 0x01
        return packet
    }
}
