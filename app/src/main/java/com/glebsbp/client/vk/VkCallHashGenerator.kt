package com.glebsbp.client

import android.content.Context
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object VkCallHashGenerator {
    private const val VK_CLIENT_ID = "6287487"
    private const val API_VERSION = "5.199"
    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

    suspend fun generateHashes(context: Context, count: Int = 1): Result<List<String>> = withContext(Dispatchers.IO) {
        if (!VkAuthWebViewManager.hasVkSessionCookie()) {
            return@withContext Result.failure(IllegalStateException("Сначала войдите в аккаунт VK"))
        }

        val token = obtainAccessToken(context)
            ?: return@withContext Result.failure(
                IllegalStateException("Не удалось получить токен VK. Перелогиньтесь в аккаунт VK.")
            )

        val hashes = mutableListOf<String>()
        val total = count.coerceIn(1, SettingsStore.MAX_VK_HASHES)
        repeat(total) { index ->
            if (index > 0) delay(2_000)
            val joinLink = startCall(token)
                ?: return@withContext Result.failure(
                    IllegalStateException("Не удалось создать звонок VK (${index + 1}/$total)")
                )
            val hash = stripVkUrlStatic(joinLink)
            if (hash.length < 16) {
                return@withContext Result.failure(
                    IllegalStateException("VK вернул некорректную ссылку на звонок")
                )
            }
            hashes.add(hash)
        }
        Result.success(hashes)
    }

    private suspend fun obtainAccessToken(context: Context): String? {
        obtainAccessTokenViaHttp(context)?.let { return it }
        return VkAuthWebViewManager.obtainAccessToken(context).getOrNull()
    }

    private data class HttpHop(val token: String? = null, val nextUrl: String? = null)

    private fun obtainAccessTokenViaHttp(context: Context): String? {
        val cookieHeader = VkAuthWebViewManager.buildVkCookieHeader()
        if (cookieHeader.isBlank()) return null

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val ua = WebSettings.getDefaultUserAgent(context)
        var url = buildAuthorizeUrl()

        // Без non-local return из use/let/repeat — иначе ART VerifyError на части устройств.
        for (step in 0 until 12) {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val hop = client.newCall(request).execute().use { response ->
                parseAuthorizeResponse(response)
            }

            val token = hop.token
            if (token != null) return token
            val next = hop.nextUrl
            if (next.isNullOrBlank()) return null
            url = next
        }
        return null
    }

    private fun parseAuthorizeResponse(response: okhttp3.Response): HttpHop {
        val location = response.header("Location").orEmpty()
        val fromLocation = extractAccessToken(location)
        if (fromLocation != null) return HttpHop(token = fromLocation)

        if (location.isNotBlank()) return HttpHop(nextUrl = location)
        if (!response.isSuccessful) return HttpHop()

        val body = response.body?.string().orEmpty()
        val href = Regex("""location\.href\s*=\s*["']([^"']+)["']""")
            .find(body)?.groupValues?.getOrNull(1)
            .orEmpty()
        val fromHref = extractAccessToken(href)
        if (fromHref != null) return HttpHop(token = fromHref)

        val grantUrl = Regex("""(https://login\.vk\.com/\?act=grant_access[^"'\\s<]+)""")
            .find(body)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
        return HttpHop(nextUrl = grantUrl)
    }

    private fun buildAuthorizeUrl(): String {
        val redirect = URLEncoder.encode(REDIRECT_URI, Charsets.UTF_8.name())
        return "https://oauth.vk.com/authorize" +
            "?client_id=$VK_CLIENT_ID" +
            "&display=mobile" +
            "&redirect_uri=$redirect" +
            "&response_type=token" +
            "&scope=messages" +
            "&state=glebsbp" +
            "&v=$API_VERSION"
    }

    internal fun extractAccessToken(url: String): String? {
        if (!url.contains("access_token=")) return null
        val part = when {
            url.contains('#') -> url.substringAfter('#')
            url.contains('?') -> url.substringAfter('?')
            else -> url
        }
        return part.split('&')
            .firstOrNull { it.startsWith("access_token=") }
            ?.substringAfter("access_token=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun startCall(accessToken: String): String? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.vk.ru")
            .addPathSegments("method/calls.start")
            .addQueryParameter("access_token", accessToken)
            .addQueryParameter("v", API_VERSION)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val body = client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            response.body?.string().orEmpty()
        }
        if (body.isBlank()) return null
        val json = JSONObject(body)
        if (json.has("error")) {
            val err = json.getJSONObject("error")
            throw IllegalStateException(err.optString("error_msg", "VK API error"))
        }
        return json.optJSONObject("response")?.optString("join_link")?.takeIf { it.isNotBlank() }
    }
}
