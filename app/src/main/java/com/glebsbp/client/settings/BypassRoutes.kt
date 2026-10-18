package com.glebsbp.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Правила обхода VPN: IP / CIDR / wildcard IP / домены.
 * На выходе — AllowedIPs = 0.0.0.0/0 минус исключённые префиксы.
 *
 * Примеры строк:
 *   1.2.3.4
 *   10.0.0.0/8
 *   111.222.*.*
 *   ozon.ru
 *   *.ozon.ru   (резолвит ozon.ru и www.ozon.ru)
 *
 * Домены резолвятся несколькими DNS (настройка + CF/Google), иначе браузер
 * может получить другие A-записи и сайт всё равно пойдёт через VPN.
 */
object BypassRoutes {
    private const val TAG = "BypassRoutes"
    /**
     * Макс. число правил (доменов/IP/CIDR) в списке обхода.
     * ~100 доменов → обычно 150–300 /32; компактный AllowedIPs остаётся в лимите Binder.
     */
    const val MAX_RULES = 100
    /** Макс. число IP/сетей-дыр после DNS (несколько A на домен). */
    private const val MAX_EXCLUDE_PREFIXES = 400
    /** Лимит префиксов AllowedIPs после вычитания. Больше → Binder/TransactionTooLarge. */
    private const val MAX_ALLOWED_PREFIXES = 2000
    private const val MAX_PARALLEL_DNS_RULES = 12

    private val resolveCache = ConcurrentHashMap<String, Pair<Long, Set<Ipv4Cidr>>>()
    private const val RESOLVE_TTL_MS = 5 * 60 * 1000L

    /** Как часто переспрашивать DNS доменов, пока VPN поднят. */
    const val AUTO_REFRESH_INTERVAL_MS = 20 * 60 * 1000L

    @Volatile
    private var lastAppliedAllowedIps: String? = null
    private var autoRefreshJob: Job? = null

    data class BuildResult(
        val allowedIps: String,
        val excludeCount: Int,
        val unresolved: List<String>,
        val truncated: Boolean,
        /** Краткие строки «host → ip, ip» для подсказки в UI. */
        val resolvedPreview: List<String> = emptyList(),
    )

    fun parseRules(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.substringBefore('#').trim() }
            .map { normalizeRuleInput(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /** Обрезает список до [MAX_RULES] (сохранение / импорт). */
    fun limitRules(rules: List<String>): List<String> = rules.take(MAX_RULES)

    /** https://whoer.net/ru → whoer.net */
    fun normalizeRuleInput(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            s = runCatching {
                android.net.Uri.parse(s).host?.trim().orEmpty()
            }.getOrDefault("")
            if (s.isEmpty()) return raw.trim()
        } else if ('/' in s) {
            s = s.substringBefore('/').trim()
        }
        return s.trim().trimEnd('.')
    }

    fun hasDomainRules(raw: String): Boolean {
        return parseRules(raw).any { parseRule(it) == null && domainLookupHosts(it) != null }
    }

    fun noteAppliedAllowedIps(allowedIps: String) {
        lastAppliedAllowedIps = allowedIps
    }

    fun clearResolveCache() {
        resolveCache.clear()
    }

    /**
     * Пока VPN активен — раз в [AUTO_REFRESH_INTERVAL_MS] заново резолвит домены
     * (если в настройках включён автоперерезолв).
     * Если набор IP изменился, вызывает [onIpsChanged] (обычно reload WireGuard).
     */
    fun startAutoRefresh(
        scope: CoroutineScope,
        appContext: Context,
        onIpsChanged: suspend () -> Unit
    ) {
        stopAutoRefresh()
        val app = appContext.applicationContext
        autoRefreshJob = scope.launch(Dispatchers.IO) {
            val store = SettingsStore(app)
            if (!store.bypassAutoRefresh.first()) {
                Log.d(TAG, "auto-refresh skipped (disabled in settings)")
                return@launch
            }
            Log.d(TAG, "auto-refresh started every ${AUTO_REFRESH_INTERVAL_MS / 1000}s")
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                try {
                    if (!store.bypassAutoRefresh.first()) {
                        Log.d(TAG, "auto-refresh stopped (disabled in settings)")
                        break
                    }
                    val raw = store.bypassRoutes.first()
                    if (!hasDomainRules(raw)) continue
                    val result = buildAllowedIps(raw, app)
                    val previous = lastAppliedAllowedIps
                    if (previous != null && result.allowedIps == previous) {
                        Log.d(TAG, "auto-refresh: IPs unchanged (${result.excludeCount} excludes)")
                        continue
                    }
                    Log.i(
                        TAG,
                        "auto-refresh: IPs changed (excludes=${result.excludeCount}), reloading WG"
                    )
                    onIpsChanged()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "auto-refresh failed: ${e.message}")
                }
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun buildAllowedIps(rawRules: String, context: Context): BuildResult {
        val rules = limitRules(parseRules(rawRules))
        if (rules.isEmpty()) {
            return BuildResult("0.0.0.0/0", 0, emptyList(), false)
        }

        val app = context.applicationContext
        val goDnsArg = runBlocking(Dispatchers.IO) {
            SettingsStore(app).resolveGoDnsArg()
        }

        // Параллельный резолв всех доменных правил
        data class RuleResult(
            val rule: String,
            val cidrs: List<Ipv4Cidr>?,  // null = не домен / не резолвится
            val isDomain: Boolean,
        )

        val ruleResults: List<RuleResult> = runBlocking(Dispatchers.IO) {
            coroutineScope {
                val semaphore = Semaphore(MAX_PARALLEL_DNS_RULES)
                rules.map { rule ->
                    async {
                        val parsed = parseRule(rule)
                        if (parsed != null) {
                            return@async RuleResult(rule, parsed, false)
                        }
                        val hosts = domainLookupHosts(rule)
                            ?: return@async RuleResult(rule, null, false)
                        val resolved = semaphore.withPermit {
                            resolveHosts(goDnsArg, hosts)
                        }
                        RuleResult(rule, resolved.toList().ifEmpty { null }, true)
                    }
                }.awaitAll()
            }
        }

        val excludes = linkedSetOf<Ipv4Cidr>()
        val unresolved = mutableListOf<String>()
        val resolvedPreview = mutableListOf<String>()
        var truncated = false

        for (r in ruleResults) {
            if (excludes.size >= MAX_EXCLUDE_PREFIXES) {
                truncated = true
                break
            }
            if (r.cidrs == null) {
                unresolved.add(r.rule)
                continue
            }
            excludes.addAll(r.cidrs)
            if (r.isDomain && resolvedPreview.size < 4) {
                resolvedPreview.add(
                    "${r.rule} → ${r.cidrs.take(4).joinToString { it.toString() }}"
                )
            }
        }

        val capped = excludes.take(MAX_EXCLUDE_PREFIXES)
        if (excludes.size > capped.size) truncated = true

        // Компактный комплемент: 0.0.0.0/0 минус все дыры сразу.
        // Старое поштучное subtract раздувало AllowedIPs до лимита
        // и отбрасывало хвост списка (ya.ru и т.п.).
        var allowed = complementCidrs(capped)
        var appliedExcludes = capped.size
        if (allowed.size > MAX_ALLOWED_PREFIXES) {
            truncated = true
            // Подбираем максимальное число excludes, которое влезает в лимит
            var lo = 0
            var hi = capped.size
            var best = emptyList<Ipv4Cidr>()
            var bestN = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val candidate = complementCidrs(capped.take(mid))
                if (candidate.size <= MAX_ALLOWED_PREFIXES) {
                    best = candidate
                    bestN = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            allowed = best
            appliedExcludes = bestN
            Log.w(
                TAG,
                "AllowedIPs limit: applied $appliedExcludes/${capped.size} excludes " +
                    "(allowed=${allowed.size})"
            )
        }

        if (allowed.isEmpty()) {
            Log.w(TAG, "Bypass rules excluded entire IPv4 space; falling back to 0.0.0.0/0")
            return BuildResult("0.0.0.0/0", appliedExcludes, unresolved, true)
        }

        Log.d(
            TAG,
            "bypass excludes=${capped.size} applied=$appliedExcludes allowed=${allowed.size} unresolved=${unresolved.size} truncated=$truncated"
        )
        return BuildResult(
            allowedIps = allowed.joinToString(", ") { it.toString() },
            excludeCount = appliedExcludes,
            unresolved = unresolved,
            truncated = truncated,
            resolvedPreview = resolvedPreview,
        )
    }

    private fun parseRule(rule: String): List<Ipv4Cidr>? {
        parseCidr(rule)?.let { return listOf(it) }
        parseIpWildcard(rule)?.let { return listOf(it) }
        return null
    }

    /** Домен или *.domain → список хостов для DNS. */
    private fun domainLookupHosts(rule: String): List<String>? {
        var host = normalizeRuleInput(rule).lowercase(Locale.US)
        if (host.startsWith("*.")) {
            host = host.removePrefix("*.")
            if (!isDomainName(host)) return null
            // Явный wildcard: apex + www
            return listOf(host, "www.$host")
        }
        if (!isDomainName(host)) return null
        // Только то, что написал пользователь — иначе www.* раздувает AllowedIPs
        return listOf(host)
    }

    private fun isDomainName(host: String): Boolean {
        if (host.length !in 1..253) return false
        if (host.startsWith(".") || host.endsWith(".") || ".." in host) return false
        if (!host.contains('.')) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        if (labels.any { !it.matches(Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")) }) return false
        val tld = labels.last()
        return tld.any { it.isLetter() }
    }

    private fun resolveHosts(goDnsArg: String, hosts: List<String>): Set<Ipv4Cidr> {
        val now = System.currentTimeMillis()
        val out = linkedSetOf<Ipv4Cidr>()
        for (host in hosts) {
            val cacheKey = "$goDnsArg|$host"
            val cached = resolveCache[cacheKey]
            if (cached != null && now - cached.first < RESOLVE_TTL_MS && cached.second.isNotEmpty()) {
                out.addAll(cached.second)
                continue
            }
            val addrs = runCatching {
                AppDnsResolver.lookupAForBypass(goDnsArg, host)
            }.getOrElse {
                Log.w(TAG, "bypass DNS resolve failed for $host: ${it.message}")
                emptySet()
            }
            val effective = if (addrs.isNotEmpty()) {
                addrs
            } else {
                // при сбое DNS (часто при уже поднятом VPN) оставляем прошлые IP
                val stale = cached?.second.orEmpty()
                if (stale.isEmpty()) {
                    Log.w(TAG, "bypass DNS empty for $host, no stale cache")
                } else {
                    Log.w(TAG, "bypass DNS empty for $host, keep ${stale.size} stale IP(s)")
                }
                stale
            }
            if (effective.isNotEmpty()) {
                resolveCache[cacheKey] = now to effective
                out.addAll(effective)
            }
        }
        return out
    }

    private fun parseCidr(raw: String): Ipv4Cidr? {
        val s = raw.trim()
        val slash = s.indexOf('/')
        if (slash < 0) {
            val ip = parseIpv4(s) ?: return null
            return Ipv4Cidr(ip, 32)
        }
        val ip = parseIpv4(s.substring(0, slash)) ?: return null
        val prefix = s.substring(slash + 1).toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        return Ipv4Cidr(ip, prefix).networkCidr()
    }

    private fun parseIpWildcard(raw: String): Ipv4Cidr? {
        val parts = raw.trim().split('.')
        if (parts.size != 4) return null
        if (parts.none { it == "*" }) return null
        var network = 0L
        var prefix = 0
        var seenStar = false
        for (i in 0..3) {
            val p = parts[i]
            if (p == "*") {
                seenStar = true
                continue
            }
            if (seenStar) return null
            val o = p.toIntOrNull() ?: return null
            if (o !in 0..255) return null
            network = network or (o.toLong() shl (24 - 8 * i))
            prefix = (i + 1) * 8
        }
        return Ipv4Cidr(network, prefix).networkCidr()
    }

    private fun parseIpv4(s: String): Long? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var ip = 0L
        for (i in 0..3) {
            val o = parts[i].toIntOrNull() ?: return null
            if (o !in 0..255) return null
            ip = ip or (o.toLong() shl (24 - 8 * i))
        }
        return ip
    }

    /**
     * 0.0.0.0/0 минус [holes] → компактный список CIDR для AllowedIPs.
     * Гораздо меньше префиксов, чем поштучный subtract+split.
     */
    private fun complementCidrs(holes: Collection<Ipv4Cidr>): List<Ipv4Cidr> {
        if (holes.isEmpty()) return listOf(Ipv4Cidr.ALL)

        // Сводим дыры к непересекающимся диапазонам [start, end]
        val ranges = holes.map { h ->
            val n = h.networkCidr()
            val start = n.network
            val hostBits = 32 - n.prefixLen
            val size = if (hostBits >= 32) 0x1_0000_0000L else (1L shl hostBits)
            start to (start + size - 1)
        }.sortedBy { it.first }

        val merged = ArrayList<Pair<Long, Long>>(ranges.size)
        for ((start, end) in ranges) {
            if (merged.isEmpty() || start > merged.last().second + 1) {
                merged.add(start to end)
            } else {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(prev.first to maxOf(prev.second, end))
            }
        }

        val out = ArrayList<Ipv4Cidr>(merged.size * 2 + 2)
        var cursor = 0L
        for ((start, end) in merged) {
            if (cursor < start) {
                out.addAll(rangeToCidrs(cursor, start - 1))
            }
            cursor = end + 1
            if (cursor > 0xFFFF_FFFFL) break
        }
        if (cursor <= 0xFFFF_FFFFL) {
            out.addAll(rangeToCidrs(cursor, 0xFFFF_FFFFL))
        }
        return out
    }

    /** Максимальные CIDR, покрывающие включительный диапазон IP. */
    private fun rangeToCidrs(start: Long, end: Long): List<Ipv4Cidr> {
        if (start > end) return emptyList()
        // Полное пространство
        if (start == 0L && end == 0xFFFF_FFFFL) return listOf(Ipv4Cidr.ALL)

        val out = ArrayList<Ipv4Cidr>(8)
        var cur = start
        while (cur <= end) {
            val remaining = end - cur + 1
            // Крупнейший блок-степень-двойки, выровненный по cur и ≤ remaining.
            // Нельзя делать 1L shl 32 — у Long сдвиг маскируется до 6 бит.
            val alignBits = when {
                cur == 0L -> 31
                else -> cur.countTrailingZeroBits().coerceAtMost(31)
            }
            var lenBits = 0
            while (lenBits < alignBits) {
                val nextSize = 1L shl (lenBits + 1)
                if (nextSize > remaining) break
                lenBits++
            }
            val block = 1L shl lenBits
            out.add(Ipv4Cidr(cur, 32 - lenBits))
            cur += block
        }
        return out
    }

    private fun mergeCidrs(list: List<Ipv4Cidr>): List<Ipv4Cidr> {
        if (list.isEmpty()) return emptyList()
        val sorted = list.map { it.networkCidr() }.distinct().sortedWith(
            compareBy<Ipv4Cidr> { it.prefixLen }.thenBy { it.network }
        )
        // Collapse: remove ranges fully contained in a broader one.
        val kept = mutableListOf<Ipv4Cidr>()
        for (c in sorted.sortedBy { it.prefixLen }) {
            if (kept.any { it.contains(c) }) continue
            kept.removeAll { c.contains(it) }
            kept.add(c)
        }
        // Try pairwise merge of siblings.
        var changed = true
        var cur = kept.sortedWith(compareBy({ it.network }, { it.prefixLen })).toMutableList()
        while (changed) {
            changed = false
            val next = mutableListOf<Ipv4Cidr>()
            var i = 0
            while (i < cur.size) {
                if (i + 1 < cur.size) {
                    val a = cur[i]
                    val b = cur[i + 1]
                    val merged = a.tryMerge(b)
                    if (merged != null) {
                        next.add(merged)
                        i += 2
                        changed = true
                        continue
                    }
                }
                next.add(cur[i])
                i++
            }
            cur = next
        }
        return cur.sortedWith(compareBy({ it.network }, { it.prefixLen }))
    }
}

/** IPv4 CIDR as network address (host bits zeroed) + prefix length. */
data class Ipv4Cidr(val network: Long, val prefixLen: Int) {
    init {
        require(prefixLen in 0..32)
        require(network in 0..0xFFFF_FFFFL)
    }

    fun networkCidr(): Ipv4Cidr = Ipv4Cidr(network and mask(), prefixLen)

    fun mask(): Long = if (prefixLen == 0) 0L else (-1L shl (32 - prefixLen)) and 0xFFFF_FFFFL

    fun contains(other: Ipv4Cidr): Boolean {
        if (prefixLen > other.prefixLen) return false
        return (other.network and mask()) == network
    }

    fun overlaps(other: Ipv4Cidr): Boolean = contains(other) || other.contains(this)

    fun subtract(hole: Ipv4Cidr): List<Ipv4Cidr> {
        val self = networkCidr()
        val h = hole.networkCidr()
        if (!self.overlaps(h)) return listOf(self)
        if (h.contains(self)) return emptyList()
        // Split self until hole is carved out.
        val result = ArrayList<Ipv4Cidr>(8)
        val queue = ArrayDeque<Ipv4Cidr>()
        queue.add(self)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!cur.overlaps(h)) {
                result.add(cur)
                continue
            }
            if (h.contains(cur)) continue
            if (cur.prefixLen >= 32) continue
            val (left, right) = cur.split()
            queue.add(left)
            queue.add(right)
        }
        return result
    }

    fun split(): Pair<Ipv4Cidr, Ipv4Cidr> {
        require(prefixLen < 32)
        val bit = 1L shl (31 - prefixLen)
        val left = Ipv4Cidr(network, prefixLen + 1)
        val right = Ipv4Cidr(network or bit, prefixLen + 1)
        return left to right
    }

    fun tryMerge(other: Ipv4Cidr): Ipv4Cidr? {
        if (prefixLen != other.prefixLen || prefixLen == 0) return null
        val parentLen = prefixLen - 1
        val parentMask = if (parentLen == 0) 0L else (-1L shl (32 - parentLen)) and 0xFFFF_FFFFL
        if ((network and parentMask) != (other.network and parentMask)) return null
        val bit = 1L shl (31 - parentLen)
        if ((network xor other.network) != bit) return null
        return Ipv4Cidr(network and parentMask, parentLen)
    }

    override fun toString(): String {
        val a = ((network ushr 24) and 0xff).toInt()
        val b = ((network ushr 16) and 0xff).toInt()
        val c = ((network ushr 8) and 0xff).toInt()
        val d = (network and 0xff).toInt()
        return "$a.$b.$c.$d/$prefixLen"
    }

    companion object {
        val ALL = Ipv4Cidr(0, 0)

        fun fromAddress(bytes: ByteArray): Ipv4Cidr {
            require(bytes.size == 4)
            var ip = 0L
            for (i in 0..3) {
                ip = ip or ((bytes[i].toLong() and 0xff) shl (24 - 8 * i))
            }
            return Ipv4Cidr(ip, 32)
        }
    }
}
