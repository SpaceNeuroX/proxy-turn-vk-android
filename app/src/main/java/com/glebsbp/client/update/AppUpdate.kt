package com.glebsbp.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

const val UPDATE_CHECK_NEVER = -1
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12

private const val UPDATE_LOG_TAG = "GLEBSBP"
private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/GLEBSBP/GLEBSBP/releases?per_page=30"
private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/GLEBSBP/GLEBSBP/releases/latest"
private const val GITHUB_LATEST_RELEASE_WEB_URL = "https://github.com/GLEBSBP/GLEBSBP/releases/latest"
private const val GITHUB_RELEASE_TAG_URL_PREFIX = "https://github.com/GLEBSBP/GLEBSBP/releases/tag/"
private const val GITHUB_RELEASE_BY_TAG_URL_PREFIX = "https://api.github.com/repos/GLEBSBP/GLEBSBP/releases/tags/"
private const val GITHUB_TAGS_URL = "https://api.github.com/repos/GLEBSBP/GLEBSBP/tags?per_page=100"
private const val GITHUB_TAG_TREE_URL_PREFIX = "https://github.com/GLEBSBP/GLEBSBP/tree/"
private const val GITHUB_API_RATE_LIMIT_FALLBACK_MS = 30L * 60L * 1000L
private val VERSION_NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)*")

@Volatile
private var githubApiCooldownUntilMs = 0L

fun updateIntervalHoursToMillis(hours: Int): Long? = when {
    hours <= 0 -> null
    else -> hours * 60L * 60L * 1000L
}

data class AppReleaseInfo(
    val versionTag: String,
    val releaseUrl: String,
    val source: RemoteVersionSource,
    val downloadUrl: String? = null,
    val releaseNotes: String = "",
    val isPrerelease: Boolean = false,
)

enum class RemoteVersionSource {
    Release,
    Tag
}

suspend fun fetchLatestReleaseInfo(
    localVersion: String? = null,
    includePrerelease: Boolean = false,
): AppReleaseInfo? = withContext(Dispatchers.IO) {
    // Сначала API — там есть assets/.apk. Веб-редирект раньше брался первым и
    // возвращал версию без downloadUrl → в UI всегда «В браузере».
    var latestRelease = fetchReleaseFromLatestEndpoint(includePrerelease)
        ?: fetchLatestReleaseFromList(includePrerelease)
        ?: fetchReleaseFromLatestWebRedirect(includePrerelease)

    if (latestRelease != null &&
        latestRelease.source == RemoteVersionSource.Release &&
        latestRelease.downloadUrl.isNullOrBlank()
    ) {
        latestRelease = enrichReleaseWithAssets(latestRelease) ?: latestRelease
    }

    val latestTag = if (includePrerelease) null else fetchLatestTagFromList()

    when {
        latestRelease == null -> latestTag
        latestTag == null -> latestRelease
        isNewerVersion(latestRelease.versionTag, latestTag.versionTag, includePrerelease) -> latestTag
        else -> latestRelease
    }
}

fun isNewerVersion(local: String, remote: String, includePrerelease: Boolean = false): Boolean {
    val localParsed = parseVersionTag(local)
    val remoteParsed = parseVersionTag(remote)
    if (remoteParsed.core.isEmpty()) return false
    if (localParsed.core.isEmpty()) return true

    val maxLen = maxOf(localParsed.core.size, remoteParsed.core.size)
    for (i in 0 until maxLen) {
        val localPart = localParsed.core.getOrElse(i) { 0 }
        val remotePart = remoteParsed.core.getOrElse(i) { 0 }
        if (remotePart > localPart) return true
        if (remotePart < localPart) return false
    }

    val localPre = localParsed.prerelease
    val remotePre = remoteParsed.prerelease
    if (localPre == null && remotePre == null) return false
    if (localPre == null && remotePre != null) return includePrerelease
    if (localPre != null && remotePre == null) return true
    return comparePrerelease(remotePre!!, localPre!!) > 0
}

private data class ParsedVersionTag(
    val core: List<Int>,
    val prerelease: String?,
)

private fun parseVersionTag(version: String): ParsedVersionTag {
    val normalized = normalizeVersionTag(version).removePrefix("v").removePrefix("V")
    val coreMatch = VERSION_NUMBER_REGEX.find(normalized)?.value ?: return ParsedVersionTag(emptyList(), null)
    val core = coreMatch.split('.').mapNotNull { it.toIntOrNull() }
    val suffix = normalized
        .removePrefix(coreMatch)
        .trim()
        .trimStart('-')
        .takeIf { it.isNotEmpty() }
    return ParsedVersionTag(core, suffix)
}

private fun comparePrerelease(remote: String, local: String): Int {
    val remoteNums = Regex("\\d+").findAll(remote).map { it.value.toInt() }.toList()
    val localNums = Regex("\\d+").findAll(local).map { it.value.toInt() }.toList()
    val max = maxOf(remoteNums.size, localNums.size)
    for (i in 0 until max) {
        val r = remoteNums.getOrElse(i) { 0 }
        val l = localNums.getOrElse(i) { 0 }
        if (r != l) return r.compareTo(l)
    }
    return remote.compareTo(local, ignoreCase = true)
}

private fun fetchLatestReleaseFromList(includePrerelease: Boolean): AppReleaseInfo? {
    val response = fetchGitHubApi(GITHUB_RELEASES_URL) ?: return null
    val releases = try {
        JSONArray(response)
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: failed to parse releases list", e)
        return null
    }

    var bestRelease: AppReleaseInfo? = null
    for (i in 0 until releases.length()) {
        val json = releases.optJSONObject(i) ?: continue
        if (json.optBoolean("draft")) continue
        if (!includePrerelease && json.optBoolean("prerelease")) continue
        val release = json.toAppReleaseInfo() ?: continue
        if (bestRelease == null || isNewerVersion(bestRelease.versionTag, release.versionTag, includePrerelease)) {
            bestRelease = release
        }
    }
    return bestRelease
}

private fun fetchLatestTagFromList(): AppReleaseInfo? {
    val response = fetchGitHubApi(GITHUB_TAGS_URL) ?: return null
    val tags = try {
        JSONArray(response)
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: failed to parse tags list", e)
        return null
    }

    var bestTag: AppReleaseInfo? = null
    for (i in 0 until tags.length()) {
        val json = tags.optJSONObject(i) ?: continue
        val tagName = normalizeVersionTag(json.optString("name"))
        if (tagName.isBlank()) continue
        val tag = AppReleaseInfo(
            versionTag = tagName,
            releaseUrl = "$GITHUB_TAG_TREE_URL_PREFIX$tagName",
            source = RemoteVersionSource.Tag,
            downloadUrl = null
        )
        if (bestTag == null || isNewerVersion(bestTag.versionTag, tag.versionTag)) {
            bestTag = tag
        }
    }
    return bestTag
}

private fun fetchReleaseFromLatestEndpoint(includePrerelease: Boolean): AppReleaseInfo? {
    val response = fetchGitHubApi(GITHUB_LATEST_RELEASE_URL) ?: return null
    val json = try {
        JSONObject(response)
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: failed to parse latest release", e)
        return null
    }
    if (!includePrerelease && json.optBoolean("prerelease")) return null
    return json.toAppReleaseInfo()
}

private fun fetchReleaseFromLatestWebRedirect(includePrerelease: Boolean): AppReleaseInfo? {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(GITHUB_LATEST_RELEASE_WEB_URL).openConnection() as HttpURLConnection
        applyNoCacheHeaders(conn)
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "text/html,*/*")
        conn.setRequestProperty("User-Agent", "GLEBSBPAndroid/${BuildConfig.VERSION_NAME}")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000

        val responseCode = conn.responseCode
        val location = conn.getHeaderField("Location")
        if (!location.isNullOrBlank()) {
            val releaseUrl = URL(URL(GITHUB_LATEST_RELEASE_WEB_URL), location).toString()
            val versionTag = extractTagFromReleaseUrl(releaseUrl)
            if (!versionTag.isNullOrBlank()) {
                return AppReleaseInfo(versionTag, releaseUrl, RemoteVersionSource.Release, null)
            }
        }

        if (responseCode in 200..299) {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val versionTag = Regex("/releases/tag/([^\"?#<]+)").find(response)?.groupValues?.getOrNull(1)
            if (!versionTag.isNullOrBlank()) {
                return AppReleaseInfo(versionTag, "$GITHUB_RELEASE_TAG_URL_PREFIX$versionTag", RemoteVersionSource.Release, null)
            }
        }

        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: GitHub web fallback returned $responseCode")
        null
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: GitHub web fallback failed", e)
        null
    } finally {
        conn?.disconnect()
    }
}

private fun fetchGitHubApi(url: String): String? {
    val now = System.currentTimeMillis()
    if (now < githubApiCooldownUntilMs) return null
    return fetchHttpText(
        url = url,
        sourceLabel = "GitHub API",
        accept = "application/vnd.github+json",
        isGitHubApi = true
    )
}

private fun fetchHttpText(
    url: String,
    sourceLabel: String,
    accept: String,
    isGitHubApi: Boolean = false
): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        applyNoCacheHeaders(conn)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", accept)
        if (isGitHubApi) {
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        conn.setRequestProperty("User-Agent", "GLEBSBPAndroid/${BuildConfig.VERSION_NAME}")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (responseCode in 200..299) {
            if (isGitHubApi) githubApiCooldownUntilMs = 0L
            response
        } else {
            if (isGitHubApi) noteGitHubApiCooldown(conn, responseCode, response)
            Log.w(
                UPDATE_LOG_TAG,
                "[WARN] Update check: $sourceLabel returned $responseCode ${response.take(300)}"
            )
            null
        }
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: $sourceLabel request failed", e)
        null
    } finally {
        conn?.disconnect()
    }
}

private fun applyNoCacheHeaders(conn: HttpURLConnection) {
    conn.useCaches = false
    conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
    conn.setRequestProperty("Pragma", "no-cache")
    conn.setRequestProperty("Expires", "0")
}

private fun noteGitHubApiCooldown(conn: HttpURLConnection, responseCode: Int, response: String) {
    if (responseCode != HttpURLConnection.HTTP_FORBIDDEN && responseCode != 429) return
    val now = System.currentTimeMillis()
    val retryAfterUntil = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { now + it * 1000L }
    val rateLimitResetUntil = conn.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { it * 1000L }
    val fallbackUntil = now + if (response.contains("rate limit", ignoreCase = true)) GITHUB_API_RATE_LIMIT_FALLBACK_MS else 5L * 60L * 1000L
    val cooldownUntil = listOfNotNull(retryAfterUntil, rateLimitResetUntil).filter { it > now }.minOrNull() ?: fallbackUntil
    if (cooldownUntil > githubApiCooldownUntilMs) {
        githubApiCooldownUntilMs = cooldownUntil
        Log.w(
            UPDATE_LOG_TAG,
            "[WARN] Update check: GitHub API cooldown ${(cooldownUntil - now) / 1000}s after HTTP $responseCode"
        )
    }
}

private fun enrichReleaseWithAssets(info: AppReleaseInfo): AppReleaseInfo? {
    val tag = normalizeVersionTag(info.versionTag).ifBlank { return null }
    val response = fetchGitHubApi("$GITHUB_RELEASE_BY_TAG_URL_PREFIX$tag")
        ?: fetchGitHubApi("$GITHUB_RELEASE_BY_TAG_URL_PREFIX${tag.removePrefix("v")}")
        ?: return null
    val json = try {
        JSONObject(response)
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: failed to enrich release assets for $tag", e)
        return null
    }
    return json.toAppReleaseInfo()?.copy(
        releaseUrl = info.releaseUrl.ifBlank { json.optString("html_url").trim() }
    )
}

private fun pickApkAssetUrl(assets: JSONArray): String? {
    data class ApkAsset(val name: String, val url: String)
    val apks = mutableListOf<ApkAsset>()
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name")
        val url = asset.optString("browser_download_url").trim()
        if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
        apks += ApkAsset(name, url)
    }
    if (apks.isEmpty()) return null
    return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.url
        ?: apks.firstOrNull { it.name.contains("arm64", ignoreCase = true) }?.url
        ?: apks.first().url
}

private fun JSONObject.toAppReleaseInfo(): AppReleaseInfo? {
    val versionTag = normalizeVersionTag(optString("tag_name"))
    val releaseUrl = optString("html_url").trim()
    if (versionTag.isBlank() || releaseUrl.isBlank()) return null

    val assets = optJSONArray("assets")
    val downloadUrl = if (assets != null) pickApkAssetUrl(assets) else null

    return AppReleaseInfo(
        versionTag,
        releaseUrl,
        RemoteVersionSource.Release,
        downloadUrl,
        releaseNotes = optString("body").trim(),
        isPrerelease = optBoolean("prerelease"),
    )
}

private fun normalizeVersionTag(version: String): String {
    val trimmed = version.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
}

private fun extractTagFromReleaseUrl(releaseUrl: String): String? {
    val marker = "/releases/tag/"
    val index = releaseUrl.indexOf(marker)
    if (index < 0) return null
    return releaseUrl.substring(index + marker.length)
        .substringBefore("?")
        .substringBefore("#")
        .substringBefore("/")
        .takeIf { it.isNotBlank() }
        ?.let(::normalizeVersionTag)
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Finished(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

fun downloadUpdate(context: Context, downloadUrl: String, versionTag: String): Flow<DownloadState> = flow {
    emit(DownloadState.Downloading(0f))
    var conn: HttpURLConnection? = null
    try {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        
        // Clean up old updates
        updatesDir.listFiles()?.forEach { it.delete() }
        
        val apkFile = File(updatesDir, "glebsbp_$versionTag.apk")
        
        conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "GLEBSBPAndroid/${BuildConfig.VERSION_NAME}")
        conn.instanceFollowRedirects = true
        
        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            emit(DownloadState.Error("HTTP $responseCode"))
            return@flow
        }
        
        val totalBytes = conn.contentLength.toLong()
        val inputStream = conn.inputStream
        val outputStream = FileOutputStream(apkFile)
        
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var downloadedBytes = 0L
        var lastEmitTime = 0L
        
        inputStream.use { input ->
            outputStream.use { output ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (!currentCoroutineContext().isActive) {
                        apkFile.delete()
                        emit(DownloadState.Error("Cancelled"))
                        return@flow
                    }
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    
                    val now = System.currentTimeMillis()
                    if (totalBytes > 0 && now - lastEmitTime > 100) {
                        lastEmitTime = now
                        emit(DownloadState.Downloading(downloadedBytes.toFloat() / totalBytes.toFloat()))
                    }
                }
            }
        }
        
        emit(DownloadState.Finished(apkFile))
    } catch (e: Exception) {
        emit(DownloadState.Error(e.message ?: "Unknown error"))
    } finally {
        conn?.disconnect()
    }
}

fun installApk(context: Context, apkFile: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e(UPDATE_LOG_TAG, "Failed to install APK", e)
    }
}
