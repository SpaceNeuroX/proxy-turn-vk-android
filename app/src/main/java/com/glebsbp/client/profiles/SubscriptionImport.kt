package com.glebsbp.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ParsedRemoteSubscription(
    val profiles: List<ConnectionProfile>,
    val subscriptionName: String? = null,
    val description: String = "",
    val trafficUsedMb: Double = 0.0,
    val trafficLimitMb: Double = 0.0,
    val updatedAt: String = "",
    val version: Int = 0
)

object SubscriptionImport {

    suspend fun fetch(url: String): Result<ParsedRemoteSubscription> = withContext(Dispatchers.IO) {
        try {
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                return@withContext Result.failure(IllegalArgumentException("Адрес подписки должен начинаться с http:// или https://"))
            }
            val conn = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", "GLEBSBP-Subscription/1.0")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext Result.failure(IllegalStateException("HTTP $code"))
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val parsed = parsePayload(body)
                ?: return@withContext Result.failure(IllegalArgumentException("Неверный формат подписки"))
            if (parsed.profiles.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("В подписке нет профилей"))
            }
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parsePayload(rawText: String): ParsedRemoteSubscription? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        var jsonStr = trimmed
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{") && !trimmed.startsWith("glebsbp:")) {
            try {
                val decoded = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
                jsonStr = String(decoded, Charsets.UTF_8).trim()
            } catch (_: Exception) {
            }
        }

        if (jsonStr.startsWith("glebsbp://config") || jsonStr.startsWith("glebsbp:config")) {
            val single = parseGlebsbpUri(jsonStr) ?: return null
            return ParsedRemoteSubscription(profiles = listOf(single), subscriptionName = single.name)
        }

        var description = ""
        var trafficUsed = 0.0
        var trafficLimit = 0.0
        var updatedAt = ""
        var version = 0
        var suggestedName: String? = null

        if (jsonStr.startsWith("{")) {
            try {
                val root = JSONObject(jsonStr)
                suggestedName = root.optString("subscriptionName", root.optString("groupName", "")).ifBlank { null }
                description = root.optString("description", root.optString("info", ""))
                trafficUsed = root.optDouble("trafficUsedMb", root.optDouble("trafficMb", 0.0))
                trafficLimit = root.optDouble("trafficLimitMb", root.optDouble("trafficLimit", 0.0))
                updatedAt = root.optString("updatedAt", root.optString("updated", ""))
                version = root.optInt("version", 0)

                val array = root.optJSONArray("profiles") ?: root.optJSONArray("servers")
                if (array != null) {
                    val profiles = parseProfileArray(array, suggestedName)
                    if (profiles.isNotEmpty()) {
                        return ParsedRemoteSubscription(
                            profiles = profiles,
                            subscriptionName = suggestedName,
                            description = description,
                            trafficUsedMb = trafficUsed,
                            trafficLimitMb = trafficLimit,
                            updatedAt = updatedAt,
                            version = version
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (jsonStr.startsWith("[")) {
            try {
                val array = JSONArray(jsonStr)
                if (array.length() > 0) {
                    val first = array.optJSONObject(0)
                    val gName = first?.optString("groupName", "") ?: ""
                    if (gName.isNotBlank()) suggestedName = gName
                }
                val profiles = parseProfileArray(array, suggestedName)
                if (profiles.isNotEmpty()) {
                    return ParsedRemoteSubscription(
                        profiles = profiles,
                        subscriptionName = suggestedName,
                        description = description,
                        trafficUsedMb = trafficUsed,
                        trafficLimitMb = trafficLimit,
                        updatedAt = updatedAt,
                        version = version
                    )
                }
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun parseProfileArray(array: JSONArray, fallbackGroupName: String?): List<ConnectionProfile> {
        val list = mutableListOf<ConnectionProfile>()
        for (i in 0 until array.length()) {
            val jsonObj = array.optJSONObject(i) ?: continue
            parseProfileObject(jsonObj)?.let { list.add(it) }
        }
        return list
    }

    private fun parseProfileObject(jsonObj: JSONObject): ConnectionProfile? {
        val name = jsonObj.optString("name", "Imported")
        val peer = jsonObj.optString("peer", "")
        if (peer.isBlank()) return null
        val hashes = jsonObj.optString("hashes", jsonObj.optString("vkHashes", ""))
        val workers = jsonObj.optInt("workers", jsonObj.optInt("workersPerHash", 9))
        val port = jsonObj.optInt("port", jsonObj.optInt("listenPort", 9000))
        val pass = jsonObj.optString("password", jsonObj.optString("pass", ""))
        val traffic = jsonObj.optDouble("trafficMb", jsonObj.optDouble("trafficUsedMb", 0.0))
        return ConnectionProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            peer = peer,
            vkHashes = hashes,
            workersPerHash = workers,
            listenPort = port,
            password = pass,
            trafficMb = traffic.coerceAtLeast(0.0),
            groupId = "",
            useGlobalHashes = hashes.isBlank()
        )
    }

    private fun parseGlebsbpUri(trimmed: String): ConnectionProfile? {
        return try {
            val uri = android.net.Uri.parse(trimmed.replace("glebsbp:config", "glebsbp://config"))
            val name = uri.getQueryParameter("name") ?: "QR Профиль"
            val peer = uri.getQueryParameter("peer") ?: return null
            val hashes = uri.getQueryParameter("hashes") ?: ""
            val workers = uri.getQueryParameter("workers")?.toIntOrNull() ?: 9
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 9000
            val pass = uri.getQueryParameter("pass") ?: uri.getQueryParameter("password") ?: ""
            ConnectionProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                peer = peer,
                vkHashes = hashes,
                workersPerHash = workers,
                listenPort = port,
                password = pass,
                useGlobalHashes = hashes.isBlank()
            )
        } catch (_: Exception) {
            null
        }
    }
}
