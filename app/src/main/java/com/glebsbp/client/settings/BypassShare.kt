package com.glebsbp.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Обмен настройками обхода VPN (домены/IP и/или исключения приложений).
 */
object BypassShare {
    const val FORMAT = "glebsbp-bypass"
    const val FORMAT_VERSION = 1

    enum class Scope {
        All,
        Routes,
        Apps,
    }

    data class ImportResult(
        val routes: Int,
        val apps: Int,
        val isWhitelist: Boolean,
        val routesTruncated: Boolean = false,
        val scope: Scope = Scope.All,
    )

    data class PeekResult(
        val hasRoutes: Boolean,
        val hasApps: Boolean,
        val routesCount: Int,
        val appsCount: Int,
        val isWhitelist: Boolean,
        val isPlainText: Boolean,
    )

    fun peek(raw: String): PeekResult {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) {
            return PeekResult(false, false, 0, 0, false, false)
        }
        return runCatching {
            val root = JSONObject(text)
            val format = root.optString("format", "")
            if (format.isNotEmpty() && format != FORMAT) {
                throw IllegalArgumentException("format")
            }
            if (!root.has("bypassRoutes") && !root.has("excludedApps")) {
                throw IllegalArgumentException("plain")
            }
            val routes = BypassRoutes.parseRules(root.optString("bypassRoutes", ""))
            val apps = root.optString("excludedApps", "")
                .split(',')
                .map { it.trim() }
                .count { it.isNotEmpty() }
            PeekResult(
                hasRoutes = routes.isNotEmpty(),
                hasApps = apps > 0 || root.has("isWhitelist"),
                routesCount = routes.size,
                appsCount = apps,
                isWhitelist = root.optBoolean("isWhitelist", false),
                isPlainText = false,
            )
        }.getOrElse {
            val routes = BypassRoutes.parseRules(text)
            PeekResult(
                hasRoutes = routes.isNotEmpty(),
                hasApps = false,
                routesCount = routes.size,
                appsCount = 0,
                isWhitelist = false,
                isPlainText = true,
            )
        }
    }

    suspend fun exportJson(settings: SettingsStore, scope: Scope = Scope.All): String =
        withContext(Dispatchers.IO) {
            val root = JSONObject()
                .put("format", FORMAT)
                .put("formatVersion", FORMAT_VERSION)
                .put("appVersion", BuildConfig.VERSION_NAME)
            when (scope) {
                Scope.All -> {
                    root.put("bypassRoutes", settings.bypassRoutes.first())
                    root.put("excludedApps", settings.excludedApps.first())
                    root.put("isWhitelist", settings.isWhitelist.first())
                }
                Scope.Routes -> {
                    root.put("bypassRoutes", settings.bypassRoutes.first())
                }
                Scope.Apps -> {
                    root.put("excludedApps", settings.excludedApps.first())
                    root.put("isWhitelist", settings.isWhitelist.first())
                }
            }
            root.toString(2)
        }

    suspend fun importJson(
        settings: SettingsStore,
        raw: String,
        scope: Scope = Scope.All,
    ): ImportResult = withContext(Dispatchers.IO) {
        val text = raw.trim().removePrefix("\uFEFF")
        val root = runCatching { JSONObject(text) }.getOrNull()
        val format = root?.optString("format", "").orEmpty()
        if (format.isNotEmpty() && format != FORMAT) {
            throw IllegalArgumentException("Это не файл обхода GLEBSBP (format=$format)")
        }

        val isJsonBypass = root != null &&
            (root.has("bypassRoutes") || root.has("excludedApps"))

        if (!isJsonBypass) {
            if (scope == Scope.Apps) {
                throw IllegalArgumentException("В файле нет списка приложений")
            }
            val parsed = BypassRoutes.parseRules(text)
            val rules = BypassRoutes.limitRules(parsed)
            if (rules.isEmpty()) throw IllegalArgumentException("Пустой или неизвестный файл обхода")
            settings.saveBypassRoutes(rules.joinToString("\n"))
            BypassRoutes.clearResolveCache()
            return@withContext ImportResult(
                routes = rules.size,
                apps = 0,
                isWhitelist = settings.isWhitelist.first(),
                routesTruncated = parsed.size > rules.size,
                scope = Scope.Routes,
            )
        }

        requireNotNull(root)
        val applyRoutes = scope == Scope.All || scope == Scope.Routes
        val applyApps = scope == Scope.All || scope == Scope.Apps

        var routesCount = 0
        var truncated = false
        var appsCount = 0
        var whitelist = settings.isWhitelist.first()

        if (applyRoutes) {
            if (!root.has("bypassRoutes") && scope == Scope.Routes) {
                throw IllegalArgumentException("В файле нет списка сайтов/IP")
            }
            if (root.has("bypassRoutes")) {
                val parsed = BypassRoutes.parseRules(root.optString("bypassRoutes", ""))
                val routes = BypassRoutes.limitRules(parsed)
                settings.saveBypassRoutes(routes.joinToString("\n"))
                BypassRoutes.clearResolveCache()
                routesCount = routes.size
                truncated = parsed.size > routes.size
            }
        }

        if (applyApps) {
            if (!root.has("excludedApps") && !root.has("isWhitelist") && scope == Scope.Apps) {
                throw IllegalArgumentException("В файле нет списка приложений")
            }
            if (root.has("excludedApps") || root.has("isWhitelist")) {
                val apps = root.optString("excludedApps", "")
                whitelist = root.optBoolean("isWhitelist", false)
                settings.saveExceptionsMode(apps, whitelist)
                appsCount = apps.split(',').map { it.trim() }.count { it.isNotEmpty() }
            }
        }

        ImportResult(
            routes = routesCount,
            apps = appsCount,
            isWhitelist = whitelist,
            routesTruncated = truncated,
            scope = scope,
        )
    }
}
