package com.glebsbp.client

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.os.Message
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

data class VkTurnCreds(
    val username: String,
    val password: String,
    val urls: List<String>
)

enum class VkAuthScreenMode {
    LOGIN,
    JOIN_CALL,
    TOKEN,
}

private object VkAuthJoinScripts {
    fun joinUrlCandidates(hash: String): List<String> = listOf(
        "https://m.vk.ru/call/join/$hash",
        "https://vk.ru/call/join/$hash",
        "https://m.vk.com/call/join/$hash",
        "https://vk.com/call/join/$hash",
    )

    const val INTERCEPTOR = """
        (function() {
            if (window.__glebsbp_vk_auth_installed) return;
            window.__glebsbp_vk_auth_installed = true;

            function tryEmitTurnServer(data) {
                if (!data) return;
                var ts = data.turn_server;
                if (!ts && data.response && data.response.turn_server) {
                    ts = data.response.turn_server;
                }
                if (ts && ts.username && ts.credential && ts.urls) {
                    window.GlebsbpVkAuth.onTurnServer(JSON.stringify(ts));
                }
            }

            const origFetch = window.fetch;
            window.fetch = async function() {
                const response = await origFetch.apply(this, arguments);
                try {
                    const clone = response.clone();
                    const text = await clone.text();
                    if (text && text.indexOf('turn_server') !== -1) {
                        tryEmitTurnServer(JSON.parse(text));
                    }
                } catch(e) {}
                return response;
            };

            const origXHROpen = XMLHttpRequest.prototype.open;
            const origXHRSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._glebsbp_url = url;
                return origXHROpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                const xhr = this;
                xhr.addEventListener('load', function() {
                    try {
                        if (!xhr.responseText || xhr.responseText.indexOf('turn_server') === -1) return;
                        tryEmitTurnServer(JSON.parse(xhr.responseText));
                    } catch(e) {}
                });
                return origXHRSend.apply(this, arguments);
            };
        })();
    """

    const val AUTO_JOIN_SETUP = """
        (function() {
            // Всегда переустанавливаем хелперы: после bfcache/SPA ready мог остаться true,
            // а __glebsbp_autoJoinTry — устаревшим → тихий режим «кнопка не найдена» со 2-го раза.
            window.__glebsbp_auto_join_ready = true;
            window.__glebsbp_auto_join_clicks = window.__glebsbp_auto_join_clicks || 0;
            window.__glebsbp_auto_join_done = false;

            var rejectPhrases = [
                'открыть в приложении',
                'open in app',
                'open in the',
                'vk звонки',
                'скачать приложение'
            ];

            function norm(s) {
                return (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
            }

            function elementText(el) {
                return norm(el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || '');
            }

            function scoreElement(el) {
                var text = elementText(el);
                if (!text || text.length > 120) return -1;
                for (var r = 0; r < rejectPhrases.length; r++) {
                    if (text.indexOf(rejectPhrases[r]) !== -1) return -1;
                }
                if (text.indexOf('продолжить в браузере') !== -1 && text.indexOf('открыть') !== -1 && text.length > 30) {
                    return -1;
                }
                if (text === 'продолжить в браузере' || text === 'continue in browser') return 100;
                if (text.indexOf('продолжить в браузере') !== -1) return 90 - Math.min(text.length, 80);
                if (text.indexOf('continue in browser') !== -1) return 85 - Math.min(text.length, 80);
                // Актуальная кнопка на m.vk: «Присоединиться»
                if (text === 'присоединиться' || text === 'join') return 95;
                if (text.indexOf('присоединиться к звонку через браузер') !== -1) return 70;
                if (text.indexOf('присоединиться через браузер') !== -1) return 65;
                if (text.indexOf('присоединиться к звонку') !== -1) return 60;
                if (text.indexOf('присоединиться') !== -1 && text.length <= 40) return 55;
                if (text.indexOf('войти в звонок') !== -1) return 50;
                if (text === 'продолжить' || text === 'continue') return 40;
                if (text.indexOf('продолжить') !== -1 && text.length <= 25) return 35;
                return -1;
            }

            function hasBetterChild(el, parentScore) {
                var kids = el.querySelectorAll('button, a, [role="button"], input[type="button"], input[type="submit"]');
                for (var i = 0; i < kids.length; i++) {
                    if (kids[i] === el) continue;
                    var cs = scoreElement(kids[i]);
                    if (cs >= parentScore) return true;
                }
                return false;
            }

            function pickBest(minScore) {
                var selectors = 'button, a, [role="button"], input[type="button"], input[type="submit"], .vkuiButton, [class*="Button"]';
                var nodes = document.querySelectorAll(selectors);
                var best = null;
                var bestScore = -1;
                var bestLen = 9999;
                for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    var sc = scoreElement(el);
                    if (sc < minScore) continue;
                    if (hasBetterChild(el, sc)) continue;
                    var tlen = elementText(el).length;
                    if (sc > bestScore || (sc === bestScore && tlen < bestLen)) {
                        best = el;
                        bestScore = sc;
                        bestLen = tlen;
                    }
                }
                return best ? { el: best, score: bestScore, text: elementText(best) } : null;
            }

            function fireClick(el) {
                try { el.scrollIntoView({block:'center', inline:'nearest'}); } catch (e0) {}
                try { el.click(); } catch (e1) {}
                try {
                    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                } catch (e2) {}
            }

            window.__glebsbp_autoJoinTry = function() {
                // Не блокируем навсегда: клик мог попасть в пустоту / SPA ещё не готова.
                if (window.__glebsbp_auto_join_done) return '';
                var pick = pickBest(50) || pickBest(35);
                if (!pick) return '';
                fireClick(pick.el);
                window.__glebsbp_auto_join_clicks = (window.__glebsbp_auto_join_clicks || 0) + 1;
                window.__glebsbp_auto_join_done = true;
                setTimeout(function() { window.__glebsbp_auto_join_done = false; }, 2200);
                return 'clicked(score=' + pick.score + '):' + pick.text.substring(0, 60);
            };

            try {
                if (window.__glebsbp_auto_join_observer) {
                    window.__glebsbp_auto_join_observer.disconnect();
                }
            } catch (e) {}
            window.__glebsbp_auto_join_observer = new MutationObserver(function() {
                try { window.__glebsbp_autoJoinTry(); } catch (e) {}
            });
            var root = document.documentElement || document.body;
            if (root) {
                window.__glebsbp_auto_join_observer.observe(root, { childList: true, subtree: true });
            }
            setTimeout(function() {
                try { window.__glebsbp_auto_join_observer && window.__glebsbp_auto_join_observer.disconnect(); } catch(e) {}
            }, 45000);
            return 'ready';
        })();
    """

    const val AUTO_JOIN_TRY =
        "(function(){ return (window.__glebsbp_autoJoinTry && window.__glebsbp_autoJoinTry()) || ''; })();"

    const val AUTO_JOIN_RESET = """
        (function(){
            try { if (window.__glebsbp_auto_join_observer) window.__glebsbp_auto_join_observer.disconnect(); } catch(e) {}
            window.__glebsbp_auto_join_observer = null;
            window.__glebsbp_auto_join_clicks = 0;
            window.__glebsbp_auto_join_ready = false;
            window.__glebsbp_auto_join_done = false;
            window.__glebsbp_autoJoinTry = null;
        })();
    """

    const val PAGE_404_CHECK =
        "(function(){var t=document.body?document.body.innerText:'';return t.indexOf('Такой страницы нет')!==-1||t.indexOf('Страница не найдена')!==-1?'404':'';})();"
}

object VkAuthWebViewManager {
    private const val TAG = "VkAuthWV"
    private const val AUTH_TIMEOUT_MS = 5 * 60_000L
    /** Тихий вход: ждём TURN; при неудаче открываем видимый WebView. */
    private const val SILENT_AUTH_TIMEOUT_MS = 22_000L
    private const val NOTIFICATION_ID = 9002
    private const val CHANNEL_ID = "vk_auth_channel"

    const val EXTRA_MODE = "authMode"
    const val EXTRA_JOIN_HASH = "joinHash"

    val authMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLoginResult = AtomicReference<CompletableDeferred<Result<Unit>>?>(null)
    private val pendingTurnResult = AtomicReference<CompletableDeferred<Result<VkTurnCreds>>?>(null)
    private val pendingTokenResult = AtomicReference<CompletableDeferred<Result<String>>?>(null)
    var activeActivity: VkAuthActivity? = null
    var pendingIntentToStart: Intent? = null

    /** Вход в аккаунт VK без присоединения к звонку (кнопка на главной). */
    suspend fun loginOnly(context: Context): Result<Unit> {
        return authMutex.withLock {
            val deferred = CompletableDeferred<Result<Unit>>()
            pendingLoginResult.getAndSet(deferred)?.cancel()

            val intent = Intent(context, VkAuthActivity::class.java).apply {
                putExtra(EXTRA_MODE, VkAuthScreenMode.LOGIN.name)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            pendingIntentToStart = intent
            showAuthNotification(context, VkAuthScreenMode.LOGIN, null)

            if (MainActivity.isForeground) {
                context.startActivity(intent)
            }

            try {
                withTimeout(AUTH_TIMEOUT_MS) {
                    deferred.await()
                }
            } finally {
                pendingLoginResult.set(null)
                pendingIntentToStart = null
                clearAuthNotification(context)
                try {
                    activeActivity?.finish()
                } catch (_: Exception) {
                }
                activeActivity = null
            }
        }
    }

    /**
     * Присоединение к звонку и получение TURN.
     * Сначала тихий режим (только логи), видимый WebView — если тихий не сработал
     * или нет сессии VK.
     */
    suspend fun authenticate(context: Context, hash: String): Result<VkTurnCreds> {
        val cleanHash = stripVkUrlStatic(hash.trim())
        if (cleanHash.length < 8) {
            return Result.failure(IllegalArgumentException("Некорректный VK-хеш"))
        }

        if (hasVkSessionCookie()) {
            logAuth("Тихий вход в звонок…")
            val silent = authMutex.withLock {
                silentJoinWithHeadlessWebView(context.applicationContext, cleanHash)
            }
            if (silent.isSuccess) {
                return silent
            }
            val err = silent.exceptionOrNull()
            if (err is CancellationException) throw err
            val reason = err?.message?.take(80) ?: "timeout"
            logAuth("Тихий вход не удался ($reason) — WebView", verbose = true)
            logAuth("Открываем WebView…")
        } else {
            logAuth("Нет сессии VK — нужен вход")
        }

        return runJoinCall(context, cleanHash)
    }

    private suspend fun runJoinCall(
        context: Context,
        cleanHash: String,
    ): Result<VkTurnCreds> {
        return authMutex.withLock {
            val deferred = CompletableDeferred<Result<VkTurnCreds>>()
            pendingTurnResult.getAndSet(deferred)?.cancel()

            val intent = Intent(context, VkAuthActivity::class.java).apply {
                putExtra(EXTRA_MODE, VkAuthScreenMode.JOIN_CALL.name)
                putExtra(EXTRA_JOIN_HASH, cleanHash)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            pendingIntentToStart = intent
            showAuthNotification(context, VkAuthScreenMode.JOIN_CALL, cleanHash)

            if (MainActivity.isForeground) {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    pendingTurnResult.set(null)
                    pendingIntentToStart = null
                    return@withLock Result.failure(e)
                }
            }

            try {
                withTimeout(AUTH_TIMEOUT_MS) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                Result.failure(Exception("Таймаут входа в звонок VK"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                pendingTurnResult.set(null)
                pendingIntentToStart = null
                clearAuthNotification(context)
                try {
                    activeActivity?.finish()
                } catch (_: Exception) {
                }
                activeActivity = null
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun silentJoinWithHeadlessWebView(
        context: Context,
        hash: String,
    ): Result<VkTurnCreds> {
        if (!hasVkSessionCookie()) {
            return Result.failure(IllegalStateException("Нет сессии VK"))
        }

        val deferred = CompletableDeferred<Result<VkTurnCreds>>()
        var webViewRef: WebView? = null
        var joinUrlIndex = 0
        var join404Retries = 0
        var autoJoinJob: Job? = null
        var autoJoinRunId = 0
        val autoJoinScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun currentJoinUrl(): String = VkAuthJoinScripts.joinUrlCandidates(hash)
            .getOrElse(joinUrlIndex) { VkAuthJoinScripts.joinUrlCandidates(hash).last() }

        fun destroyWebView() {
            autoJoinJob?.cancel()
            autoJoinJob = null
            val wv = webViewRef
            webViewRef = null
            if (wv == null) return
            val destroy = Runnable {
                try {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                } catch (_: Exception) {
                }
                try {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    try { wv.removeJavascriptInterface("GlebsbpVkAuth") } catch (_: Exception) {}
                    wv.webViewClient = WebViewClient()
                    wv.webChromeClient = null
                    wv.onPause()
                    wv.destroy()
                } catch (_: Exception) {
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) destroy.run()
            else mainHandler.post(destroy)
        }

        fun finishWith(result: Result<VkTurnCreds>) {
            if (!deferred.isCompleted) deferred.complete(result)
            destroyWebView()
        }

        fun parseTurnJson(json: String): VkTurnCreds? {
            return try {
                val obj = JSONObject(json)
                val user = obj.optString("username")
                val pass = obj.optString("credential")
                val urlsRaw = obj.optJSONArray("urls") ?: JSONArray()
                val urls = buildList {
                    for (i in 0 until urlsRaw.length()) {
                        val url = urlsRaw.optString(i)
                        if (url.isNotBlank()) add(url)
                    }
                }
                if (user.isBlank() || pass.isBlank() || urls.isEmpty()) null
                else VkTurnCreds(user, pass, urls)
            } catch (_: Exception) {
                null
            }
        }

        fun resetAutoJoinForNewPage(view: WebView?) {
            autoJoinJob?.cancel()
            autoJoinRunId++
            view?.evaluateJavascript(VkAuthJoinScripts.AUTO_JOIN_RESET, null)
            logAuth("Страница загружается, сброс автоклика", verbose = true)
        }

        fun scheduleAutoJoinClicks(view: WebView?) {
            val wv = view ?: return
            val runId = ++autoJoinRunId
            autoJoinJob?.cancel()
            val clicked = AtomicBoolean(false)
            logAuth("Автоклик: старт (hash=${hash.take(8)}…)", verbose = true)
            // Как в видимом WebView — SPA VK часто дорисовывает кнопку с задержкой со 2-го раза.
            val delaysMs = longArrayOf(0, 500, 1000, 2000, 3000, 4500, 6000, 8000, 10000, 13000, 16000, 19000)
            autoJoinJob = autoJoinScope.launch {
                // Дождаться установки хелперов, иначе первый TRY на тёплом Chromium пустой.
                suspendCancellableCoroutine { cont ->
                    wv.evaluateJavascript(VkAuthJoinScripts.AUTO_JOIN_SETUP) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                var prev = 0L
                for (target in delaysMs) {
                    delay(target - prev)
                    prev = target
                    if (runId != autoJoinRunId) return@launch
                    val clean = suspendCancellableCoroutine { cont ->
                        wv.evaluateJavascript(VkAuthJoinScripts.AUTO_JOIN_TRY) { result ->
                            val value = result?.trim()?.removeSurrounding("\"").orEmpty()
                            if (cont.isActive) cont.resume(value)
                        }
                    }
                    if (clean.isNotBlank() && clean != "null") {
                        clicked.set(true)
                        logAuth("Автоклик @${target}ms: $clean", verbose = true)
                    }
                }
                delay(500)
                if (runId == autoJoinRunId && !clicked.get() && !deferred.isCompleted) {
                    // Не рвём тихий режим сразу — TURN мог прийти без клика / кнопка ещё грузится.
                    logAuth("Автоклик: кнопка пока не найдена (тихий режим), ждём TURN…", isError = false)
                }
            }
        }

        fun checkJoinPageOrRetry(view: WebView?, pageUrl: String?) {
            view?.evaluateJavascript(VkAuthJoinScripts.PAGE_404_CHECK) { result ->
                if (result?.contains("404") != true) return@evaluateJavascript
                val candidates = VkAuthJoinScripts.joinUrlCandidates(hash)
                if (joinUrlIndex < candidates.lastIndex) {
                    joinUrlIndex++
                    join404Retries++
                    logAuth("404 на ${pageUrl ?: "?"}, retry ${joinUrlIndex + 1}/${candidates.size}", verbose = true)
                    mainHandler.post { view.loadUrl(currentJoinUrl(), authLoadHeaders()) }
                    return@evaluateJavascript
                }
                if (join404Retries == 0) return@evaluateJavascript
                logAuth("Хеш звонка недействителен: ${hash.take(8)}…", isError = true)
                finishWith(
                    Result.failure(
                        Exception("Ссылка на звонок VK недействительна или устарела. Обновите хеш звонка.")
                    )
                )
            }
        }

        val latch = CountDownLatch(1)
        val createAction = Runnable {
            try {
                val wv = WebView(context)
                webViewRef = wv
                wv.apply {
                    applyAuthWebSettings(this, context, 0)
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onDebugLog(message: String) {
                            logAuth("JS: $message", verbose = true)
                        }

                        @JavascriptInterface
                        fun onTurnServer(json: String) {
                            val creds = parseTurnJson(json)
                            if (creds == null) {
                                logAuth("Неполный turn_server: $json", isError = true)
                                return
                            }
                            logAuth("TURN получены")
                            finishWith(Result.success(creds))
                        }

                        @JavascriptInterface
                        fun onLoginPageError(message: String) {
                            finishWith(Result.failure(Exception(message)))
                        }

                        @JavascriptInterface
                        fun onError(message: String) {
                            finishWith(Result.failure(Exception(message)))
                        }
                    }, "GlebsbpVkAuth")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            resetAutoJoinForNewPage(view)
                            view?.evaluateJavascript(VkAuthJoinScripts.INTERCEPTOR, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(VkAuthJoinScripts.INTERCEPTOR, null)
                            checkJoinPageOrRetry(view, url)
                            scheduleAutoJoinClicks(view)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            return url.startsWith("intent://") || url.startsWith("vk://")
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val host = error?.url?.let { Uri.parse(it).host }.orEmpty()
                            if (host.endsWith("vk.com") || host.endsWith("vk.ru") || host.endsWith("okcdn.ru")) {
                                handler?.proceed()
                            } else {
                                handler?.cancel()
                            }
                        }
                    }
                    webChromeClient = WebChromeClient()
                    // Без attach к иерархии со 2-го раза Chromium часто не дорисовывает SPA-кнопки.
                    val host = MainActivity.currentActivity
                        ?.findViewById<ViewGroup>(android.R.id.content)
                    if (host != null) {
                        val lp = FrameLayout.LayoutParams(360, 640)
                        alpha = 0.01f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        host.addView(this, lp)
                    } else {
                        measure(
                            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                        )
                        layout(0, 0, 360, 640)
                    }
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    clearCache(true)
                    onResume()
                    val startUrl = currentJoinUrl()
                    logAuth(
                        "Тихий WebView: url=$startUrl, attached=${host != null}",
                        verbose = true,
                    )
                    loadUrl(startUrl, authLoadHeaders())
                }
            } catch (e: Exception) {
                if (!deferred.isCompleted) {
                    deferred.complete(Result.failure(e))
                }
            } finally {
                latch.countDown()
            }
        }

        try {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().flush()
            if (Looper.myLooper() == Looper.getMainLooper()) createAction.run()
            else mainHandler.post(createAction)

            if (!latch.await(5, TimeUnit.SECONDS)) {
                destroyWebView()
                return Result.failure(IllegalStateException("Не удалось создать WebView"))
            }

            return withTimeout(SILENT_AUTH_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            destroyWebView()
            return Result.failure(Exception("Тихий вход: timeout"))
        } catch (e: CancellationException) {
            destroyWebView()
            throw e
        } catch (e: Exception) {
            destroyWebView()
            return Result.failure(e)
        } finally {
            destroyWebView()
        }
    }

    suspend fun authenticateAll(context: Context, hashes: List<String>): Map<String, VkTurnCreds> {
        val out = linkedMapOf<String, VkTurnCreds>()
        for (hash in hashes.distinct()) {
            val clean = stripVkUrlStatic(hash.trim())
            if (clean.length < 8) continue
            val result = authenticate(context, clean)
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("VK auth failed for $clean")
            }
            out[clean] = result.getOrThrow()
        }
        return out
    }

    fun writeCredsFile(context: Context, credsByHash: Map<String, VkTurnCreds>): File {
        val root = JSONObject()
        val hashes = JSONObject()
        credsByHash.forEach { (hash, creds) ->
            val entry = JSONObject()
            entry.put("u", creds.username)
            entry.put("p", creds.password)
            entry.put("urls", JSONArray(creds.urls))
            hashes.put(hash, entry)
        }
        root.put("hashes", hashes)
        val file = File(context.filesDir, "vk_turn_creds.json")
        file.writeText(root.toString())
        return file
    }

    fun notifyLoginFailure(error: Exception) {
        val deferred = pendingLoginResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(Result.failure(error))
        }
    }

    fun notifyLoginSuccess() {
        val deferred = pendingLoginResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(Result.success(Unit))
        }
    }

    fun notifyTurnResult(result: Result<VkTurnCreds>) {
        val deferred = pendingTurnResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }

    fun notifyCancelled() {
        val cancelled = Exception("Cancelled")
        pendingLoginResult.getAndSet(null)?.complete(Result.failure(cancelled))
        pendingTurnResult.getAndSet(null)?.complete(Result.failure(cancelled))
        pendingTokenResult.getAndSet(null)?.complete(Result.failure(cancelled))
    }

    internal fun notifyTokenSuccess(token: String) {
        val deferred = pendingTokenResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(Result.success(token))
        }
    }

    internal fun notifyTokenFailure(error: Throwable) {
        val deferred = pendingTokenResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(Result.failure(error))
        }
    }

    fun checkAndShowPendingAuth(context: Context) {
        val intent = pendingIntentToStart
        if (intent != null && activeActivity == null) {
            context.startActivity(intent)
        }
    }

    fun hasVkSessionCookie(): Boolean = vkRemixSid().isNotBlank()

    /** Получить access_token для VK API (автогенерация хешей звонков). */
    suspend fun obtainAccessToken(context: Context): Result<String> {
        if (!hasVkSessionCookie()) {
            return Result.failure(IllegalStateException("Сначала войдите в аккаунт VK"))
        }
        return authMutex.withLock {
            val deferred = CompletableDeferred<Result<String>>()
            pendingTokenResult.getAndSet(deferred)?.cancel()

            val intent = Intent(context, VkAuthActivity::class.java).apply {
                putExtra(EXTRA_MODE, VkAuthScreenMode.TOKEN.name)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            pendingIntentToStart = intent
            showAuthNotification(context, VkAuthScreenMode.TOKEN, null)

            if (MainActivity.isForeground) {
                context.startActivity(intent)
            }

            try {
                withTimeout(AUTH_TIMEOUT_MS) {
                    deferred.await()
                }
            } finally {
                pendingTokenResult.set(null)
                pendingIntentToStart = null
                clearAuthNotification(context)
                try {
                    activeActivity?.finish()
                } catch (_: Exception) {
                }
                activeActivity = null
            }
        }
    }

    internal fun buildVkCookieHeader(): String {
        val cm = CookieManager.getInstance()
        val domains = listOf(
            "https://vk.com",
            "https://vk.ru",
            "https://m.vk.com",
            "https://m.vk.ru",
            "https://login.vk.com",
            "https://oauth.vk.com",
            "https://id.vk.com",
            "https://id.vk.ru",
        )
        return domains.flatMap { domain ->
            cm.getCookie(domain)?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }.distinct().joinToString("; ")
    }

    internal fun vkRemixSid(): String {
        val cm = CookieManager.getInstance()
        val raw = listOf(
            cm.getCookie("https://vk.com"),
            cm.getCookie("https://vk.ru"),
            cm.getCookie("https://m.vk.com"),
            cm.getCookie("https://m.vk.ru"),
            cm.getCookie("https://id.vk.com"),
            cm.getCookie("https://id.vk.ru"),
        ).filterNotNull().joinToString(";")
        return raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("remixsid=") }
            ?.removePrefix("remixsid=")
            ?.trim()
            .orEmpty()
    }

    fun clearVkAuthCookies() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    fun authLoadHeaders(): Map<String, String> = mapOf(
        "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    /** URL входа: сначала мобильный vk.ru, затем десктопный (обход ошибок VK ID в WebView). */
    fun loginStartUrl(attempt: Int): String = when (attempt) {
        0 -> "https://m.vk.ru/login"
        1 -> "https://m.vk.ru/"
        else -> "https://vk.ru/login"
    }

    fun oauthTokenStartUrl(): String {
        val redirect = java.net.URLEncoder.encode("https://oauth.vk.com/blank.html", Charsets.UTF_8.name())
        return "https://oauth.vk.com/authorize" +
            "?client_id=6287487" +
            "&display=mobile" +
            "&redirect_uri=$redirect" +
            "&response_type=token" +
            "&scope=messages" +
            "&state=glebsbp" +
            "&v=5.199"
    }

    fun authUserAgent(context: Context, attempt: Int): String {
        if (attempt >= 2) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        }
        return WebSettings.getDefaultUserAgent(context)
    }

    fun applyAuthWebSettings(webView: WebView, context: Context, loginAttempt: Int) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            blockNetworkLoads = false
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = authUserAgent(context, loginAttempt)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    internal const val LOGIN_ERROR_WATCHER_JS = """
        (function() {
            if (window.__glebsbp_login_err_watch) return;
            window.__glebsbp_login_err_watch = true;
            function check() {
                try {
                    var t = (document.body && document.body.innerText) || '';
                    var low = t.toLowerCase();
                    if (low.indexOf('unknown method') !== -1) {
                        window.GlebsbpVkAuth.onLoginPageError('Unknown method passed');
                    }
                } catch(e) {}
            }
            setInterval(check, 900);
            if (document.documentElement) {
                new MutationObserver(check).observe(document.documentElement,
                    {childList:true, subtree:true, characterData:true});
            }
            check();
        })();
    """

    private fun showAuthNotification(context: Context, mode: VkAuthScreenMode, hash: String?) {
        if (MainActivity.isForeground) return
        if (!NotificationHelper.areNotificationsEnabled(context)) return
        NotificationHelper.ensureAuxChannel(
            context,
            CHANNEL_ID,
            "Авторизация VK",
            "Вход в аккаунт VK и подтверждение звонка",
        )
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(context, VkAuthActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            if (hash != null) putExtra(EXTRA_JOIN_HASH, hash)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 2, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = Intent(context, VkAuthCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 3, cancelIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val body = when (mode) {
            VkAuthScreenMode.LOGIN -> "Войдите в аккаунт VK"
            VkAuthScreenMode.JOIN_CALL -> "Подтверждаем вход в звонок…"
            VkAuthScreenMode.TOKEN -> "Получаем доступ VK API…"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Требуется вход в VK")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "Отменить", cancelPendingIntent)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun clearAuthNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun encodeTurnCredsPayload(hash: String, creds: VkTurnCreds): String {
        val json = JSONObject()
        json.put("u", creds.username)
        json.put("p", creds.password)
        json.put("urls", JSONArray(creds.urls))
        val b64 = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "TURN_CREDS|$hash|$b64\n"
    }

    internal fun logAuth(message: String, isError: Boolean = false, verbose: Boolean = false) {
        Log.d(TAG, message)
        TunnelManager.addVkAuthLog(message, isError, verbose)
    }
}

class VkAuthActivity : ComponentActivity() {
    private lateinit var screenMode: VkAuthScreenMode
    private var loginHandled = false
    private var joinHash = ""
    private var joinUrlIndex = 0
    private var awaitingLoginBeforeJoin = false
    private var join404Retries = 0
    private var loginFlowAttempt = 0
    private var loginRetryInProgress = false
    private var loginErrorHandledForAttempt = -1
    private var webViewRef: WebView? = null
    private val autoJoinScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoJoinJob: Job? = null
    private var autoJoinRunId = 0

    private fun joinUrlCandidates(): List<String> = VkAuthJoinScripts.joinUrlCandidates(joinHash)

    private fun currentJoinUrl(): String = joinUrlCandidates().getOrElse(joinUrlIndex) {
        joinUrlCandidates().last()
    }

    private val interceptorJSCode = VkAuthJoinScripts.INTERCEPTOR
    private val autoJoinSetupJSCode = VkAuthJoinScripts.AUTO_JOIN_SETUP
    private val autoJoinTryJSCode = VkAuthJoinScripts.AUTO_JOIN_TRY

    private val autoJoinDiagJSCode = """
        (function() {
            function norm(s) {
                return (s || '').replace(/\s+/g, ' ').trim();
            }
            var buttons = [];
            var selectors = 'button, a, [role="button"], input[type="button"], input[type="submit"], .vkuiButton, [class*="Button"]';
            var nodes = document.querySelectorAll(selectors);
            for (var i = 0; i < nodes.length && buttons.length < 20; i++) {
                var el = nodes[i];
                var text = norm(el.innerText || el.textContent || el.value || el.getAttribute('aria-label'));
                if (!text) continue;
                buttons.push(text.substring(0, 100));
            }
            var body = '';
            try { body = norm((document.body && document.body.innerText) || '').substring(0, 400); } catch(e) {}
            return JSON.stringify({
                url: String(location.href || ''),
                ready: String(document.readyState || ''),
                title: String(document.title || ''),
                clicks: window.__glebsbp_auto_join_clicks || 0,
                buttons: buttons,
                body: body
            });
        })();
    """.trimIndent()

    private fun resetAutoJoinForNewPage(view: WebView?) {
        autoJoinJob?.cancel()
        autoJoinRunId++
        view?.evaluateJavascript(VkAuthJoinScripts.AUTO_JOIN_RESET, null)
        VkAuthWebViewManager.logAuth("Страница загружается, сброс автоклика", verbose = true)
    }

    private fun parseJsJson(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        var s = raw.trim()
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return try {
            JSONObject(s)
        } catch (_: Exception) {
            null
        }
    }

    private fun dumpPageDiagnostics(view: WebView?, reason: String) {
        view?.evaluateJavascript(autoJoinDiagJSCode) { raw ->
            val obj = parseJsJson(raw)
            if (obj == null) {
                VkAuthWebViewManager.logAuth("Диагностика ($reason): не удалось прочитать страницу", verbose = true)
                return@evaluateJavascript
            }
            try {
                val url = obj.optString("url")
                val ready = obj.optString("ready")
                val title = obj.optString("title")
                val clicks = obj.optInt("clicks")
                val buttons = obj.optJSONArray("buttons")
                val body = obj.optString("body")
                val btnList = buildList {
                    if (buttons != null) {
                        for (i in 0 until buttons.length()) {
                            val t = buttons.optString(i)
                            if (t.isNotBlank()) add(t)
                        }
                    }
                }
                VkAuthWebViewManager.logAuth(
                    "Диагностика ($reason): ready=$ready, url=${url.take(80)}, title=${title.take(60)}, clicks=$clicks",
                    verbose = true,
                )
                if (btnList.isEmpty()) {
                    VkAuthWebViewManager.logAuth("Кнопки на странице: не найдены", verbose = true)
                } else {
                    VkAuthWebViewManager.logAuth(
                        "Кнопки (${btnList.size}): ${btnList.take(8).joinToString(" | ")}",
                        verbose = true,
                    )
                }
                if (body.isNotBlank()) {
                    VkAuthWebViewManager.logAuth("Текст страницы: ${body.take(200)}", verbose = true)
                }
            } catch (e: Exception) {
                VkAuthWebViewManager.logAuth("Диагностика ($reason): ошибка ${e.message}", verbose = true)
            }
        }
    }

    private fun scheduleAutoJoinClicks(view: WebView?) {
        if (screenMode != VkAuthScreenMode.JOIN_CALL || awaitingLoginBeforeJoin) return
        val wv = view ?: return
        val runId = ++autoJoinRunId
        autoJoinJob?.cancel()
        val clicked = AtomicBoolean(false)
        VkAuthWebViewManager.logAuth("Автоклик: старт (run=$runId, hash=${joinHash.take(8)}…)", verbose = true)
        val delaysMs = longArrayOf(0, 500, 1000, 2000, 3000, 4000, 5000, 6500, 8000, 10000, 12000, 15000, 18000)
        val diagAtMs = setOf(3000L, 5000L, 8000L, 12000L, 18000L)
        autoJoinJob = autoJoinScope.launch {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(autoJoinSetupJSCode) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            var prev = 0L
            for (target in delaysMs) {
                delay(target - prev)
                prev = target
                if (runId != autoJoinRunId) {
                    VkAuthWebViewManager.logAuth("Автоклик: отменён (новая страница)", verbose = true)
                    return@launch
                }
                val clean = suspendCancellableCoroutine { cont ->
                    wv.evaluateJavascript(autoJoinTryJSCode) { result ->
                        val value = result?.trim()?.removeSurrounding("\"").orEmpty()
                        if (cont.isActive) cont.resume(value)
                    }
                }
                when {
                    clean.isNotBlank() && clean != "null" -> {
                        clicked.set(true)
                        VkAuthWebViewManager.logAuth("Автоклик @${target}ms: $clean", verbose = true)
                    }
                    diagAtMs.contains(target) && !clicked.get() -> dumpPageDiagnostics(wv, "${target}ms")
                }
            }
            delay(500)
            if (runId == autoJoinRunId && !clicked.get()) {
                VkAuthWebViewManager.logAuth(
                    "Автоклик: кнопка не найдена — нажмите «Продолжить» вручную",
                    isError = true
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VkAuthWebViewManager.activeActivity = this

        screenMode = when (intent.getStringExtra(VkAuthWebViewManager.EXTRA_MODE)) {
            VkAuthScreenMode.LOGIN.name -> VkAuthScreenMode.LOGIN
            VkAuthScreenMode.TOKEN.name -> VkAuthScreenMode.TOKEN
            else -> VkAuthScreenMode.JOIN_CALL
        }
        val joinHashExtra = intent.getStringExtra(VkAuthWebViewManager.EXTRA_JOIN_HASH).orEmpty()
        joinHash = joinHashExtra
        if (screenMode == VkAuthScreenMode.JOIN_CALL && joinHash.length < 8) {
            VkAuthWebViewManager.notifyTurnResult(Result.failure(IllegalArgumentException("Некорректный VK-хеш")))
            finish()
            return
        }

        awaitingLoginBeforeJoin =
            screenMode == VkAuthScreenMode.JOIN_CALL && !VkAuthWebViewManager.hasVkSessionCookie()

        val startUrl = when (screenMode) {
            VkAuthScreenMode.LOGIN -> VkAuthWebViewManager.loginStartUrl(0)
            VkAuthScreenMode.TOKEN -> VkAuthWebViewManager.oauthTokenStartUrl()
            VkAuthScreenMode.JOIN_CALL -> if (awaitingLoginBeforeJoin) {
                VkAuthWebViewManager.loginStartUrl(0)
            } else {
                currentJoinUrl()
            }
        }
        val statusText = when (screenMode) {
            VkAuthScreenMode.LOGIN -> "Войдите в аккаунт VK"
            VkAuthScreenMode.TOKEN -> "Получаем доступ VK API…"
            VkAuthScreenMode.JOIN_CALL -> when {
                awaitingLoginBeforeJoin -> "Сначала войдите в аккаунт VK"
                else -> "Подтверждаем вход в звонок…"
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        VkAuthWebViewManager.logAuth(
            "WebView: mode=$screenMode, url=$startUrl, " +
                "vkCookie=${VkAuthWebViewManager.hasVkSessionCookie()}, awaitingLogin=$awaitingLoginBeforeJoin",
            verbose = true,
        )

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var isLoading by rememberSaveable { mutableStateOf(true) }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = statusText,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(
                                    onClick = {
                                        VkAuthWebViewManager.notifyCancelled()
                                        finish()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Закрыть",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        webViewRef = this
                                        setBackgroundColor(android.graphics.Color.WHITE)
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        VkAuthWebViewManager.applyAuthWebSettings(
                                            this,
                                            ctx,
                                            loginFlowAttempt
                                        )

                                        addJavascriptInterface(object {
                                            @JavascriptInterface
                                            fun onDebugLog(message: String) {
                                                VkAuthWebViewManager.logAuth("JS: $message", verbose = true)
                                            }

                                            @JavascriptInterface
                                            fun onTurnServer(json: String) {
                                                if (screenMode != VkAuthScreenMode.JOIN_CALL) return
                                                runOnUiThread {
                                                    parseAndFinishTurn(json)
                                                }
                                            }

                                            @JavascriptInterface
                                            fun onLoginPageError(message: String) {
                                                runOnUiThread {
                                                    maybeRetryLogin(message)
                                                }
                                            }

                                            @JavascriptInterface
                                            fun onError(message: String) {
                                                when (screenMode) {
                                                    VkAuthScreenMode.LOGIN ->
                                                        VkAuthWebViewManager.notifyLoginFailure(Exception(message))
                                                    VkAuthScreenMode.JOIN_CALL ->
                                                        VkAuthWebViewManager.notifyTurnResult(
                                                            Result.failure(Exception(message))
                                                        )
                                                    VkAuthScreenMode.TOKEN ->
                                                        VkAuthWebViewManager.notifyTokenFailure(Exception(message))
                                                }
                                                finish()
                                            }
                                        }, "GlebsbpVkAuth")

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                loginRetryInProgress = false
                                                if (screenMode == VkAuthScreenMode.JOIN_CALL) {
                                                    resetAutoJoinForNewPage(view)
                                                    view?.evaluateJavascript(interceptorJSCode, null)
                                                }
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                if (screenMode == VkAuthScreenMode.TOKEN) {
                                                    checkTokenAndClose(url)
                                                    return
                                                }
                                                if (screenMode == VkAuthScreenMode.JOIN_CALL) {
                                                    view?.evaluateJavascript(interceptorJSCode, null)
                                                    checkJoinPageOrRetry(view, url)
                                                    scheduleAutoJoinClicks(view)
                                                }
                                                if (screenMode == VkAuthScreenMode.LOGIN) {
                                                    checkLoginAndClose(url)
                                                    injectLoginErrorWatcher(view)
                                                }
                                                if (awaitingLoginBeforeJoin) {
                                                    checkLoginThenOpenJoin(view, url)
                                                    injectLoginErrorWatcher(view)
                                                }
                                            }

                                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                val url = request?.url?.toString().orEmpty()
                                                if (url.startsWith("intent://") || url.startsWith("vk://")) {
                                                    return true
                                                }
                                                if (screenMode == VkAuthScreenMode.TOKEN) {
                                                    VkCallHashGenerator.extractAccessToken(url)?.let { token ->
                                                        VkAuthWebViewManager.notifyTokenSuccess(token)
                                                        finish()
                                                        return true
                                                    }
                                                }
                                                return false
                                            }

                                            override fun onReceivedSslError(
                                                view: WebView?,
                                                handler: SslErrorHandler?,
                                                error: SslError?
                                            ) {
                                                val host = error?.url?.let { Uri.parse(it).host }.orEmpty()
                                                if (host.endsWith("vk.com") || host.endsWith("vk.ru") || host.endsWith("okcdn.ru")) {
                                                    handler?.proceed()
                                                } else {
                                                    handler?.cancel()
                                                }
                                            }
                                        }
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onCreateWindow(
                                                view: WebView,
                                                isDialog: Boolean,
                                                isUserGesture: Boolean,
                                                resultMsg: Message
                                            ): Boolean {
                                                val transport = resultMsg.obj as WebView.WebViewTransport
                                                transport.webView = view
                                                resultMsg.sendToTarget()
                                                return true
                                            }

                                            override fun onJsAlert(
                                                view: WebView?,
                                                url: String?,
                                                message: String?,
                                                result: android.webkit.JsResult?
                                            ): Boolean {
                                                val msg = message.orEmpty()
                                                if (msg.contains("Unknown method", ignoreCase = true)) {
                                                    maybeRetryLogin(msg)
                                                    result?.cancel()
                                                    return true
                                                }
                                                return false
                                            }

                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                if (newProgress >= 90) isLoading = false
                                            }
                                        }
                                        loadUrl(startUrl, VkAuthWebViewManager.authLoadHeaders())
                                    }
                                }
                            )
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(48.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkTokenAndClose(pageUrl: String?) {
        if (screenMode != VkAuthScreenMode.TOKEN || tokenHandled) return
        val token = VkCallHashGenerator.extractAccessToken(pageUrl.orEmpty())
        if (token != null) {
            tokenHandled = true
            VkAuthWebViewManager.logAuth("VK API token получен", verbose = true)
            VkAuthWebViewManager.notifyTokenSuccess(token)
            finish()
        }
    }

    private var tokenHandled = false

    private fun isOnVkLoginFlow(pageUrl: String?): Boolean {
        val url = pageUrl.orEmpty().lowercase()
        return (url.contains("id.vk.com") || url.contains("id.vk.ru")) &&
            (url.contains("authorize") || url.contains("login") || url.contains("auth"))
    }

    private fun injectLoginErrorWatcher(view: WebView?) {
        view?.evaluateJavascript(VkAuthWebViewManager.LOGIN_ERROR_WATCHER_JS, null)
    }

    private fun maybeRetryLogin(reason: String) {
        if (loginHandled || loginRetryInProgress) return
        if (loginErrorHandledForAttempt == loginFlowAttempt) return
        val needsLogin = screenMode == VkAuthScreenMode.LOGIN || awaitingLoginBeforeJoin
        if (!needsLogin) return

        loginErrorHandledForAttempt = loginFlowAttempt

        if (loginFlowAttempt >= 2) {
            VkAuthWebViewManager.logAuth("Вход VK: все варианты исчерпаны ($reason)", isError = true)
            when (screenMode) {
                VkAuthScreenMode.LOGIN ->
                    VkAuthWebViewManager.notifyLoginFailure(Exception(reason))
                VkAuthScreenMode.JOIN_CALL ->
                    VkAuthWebViewManager.notifyTurnResult(Result.failure(Exception(reason)))
                VkAuthScreenMode.TOKEN ->
                    VkAuthWebViewManager.notifyTokenFailure(Exception(reason))
            }
            finish()
            return
        }

        loginRetryInProgress = true
        loginFlowAttempt++
        val nextUrl = VkAuthWebViewManager.loginStartUrl(loginFlowAttempt)
        VkAuthWebViewManager.logAuth(
            "Вход VK: ошибка «$reason», пробуем вариант ${loginFlowAttempt + 1}/3 → $nextUrl",
            verbose = true,
        )
        VkAuthWebViewManager.clearVkAuthCookies()
        val wv = webViewRef
        if (wv != null) {
            VkAuthWebViewManager.applyAuthWebSettings(wv, applicationContext, loginFlowAttempt)
            wv.evaluateJavascript(
                "window.__glebsbp_login_err_watch=false;",
                null
            )
            wv.loadUrl(nextUrl, VkAuthWebViewManager.authLoadHeaders())
        }
        loginRetryInProgress = false
    }

    private fun checkLoginThenOpenJoin(view: WebView?, pageUrl: String?) {
        if (!awaitingLoginBeforeJoin || screenMode != VkAuthScreenMode.JOIN_CALL) return
        val sid = VkAuthWebViewManager.vkRemixSid()
        if (sid.length < 8) return

        val url = pageUrl.orEmpty().lowercase()
        if (isOnVkLoginFlow(url)) return

        awaitingLoginBeforeJoin = false
        joinUrlIndex = 0
        join404Retries = 0
        VkAuthWebViewManager.logAuth("Вход VK OK, открываем звонок", verbose = true)
        view?.loadUrl(currentJoinUrl())
    }

    private fun checkJoinPageOrRetry(view: WebView?, pageUrl: String?) {
        if (screenMode != VkAuthScreenMode.JOIN_CALL || awaitingLoginBeforeJoin) return
        view?.evaluateJavascript(VkAuthJoinScripts.PAGE_404_CHECK) { result ->
            if (result?.contains("404") != true) return@evaluateJavascript
            val candidates = joinUrlCandidates()
            if (joinUrlIndex < candidates.lastIndex) {
                joinUrlIndex++
                join404Retries++
                VkAuthWebViewManager.logAuth(
                    "404 на ${pageUrl ?: "?"}, retry ${joinUrlIndex + 1}/${candidates.size}",
                    verbose = true,
                )
                runOnUiThread { view.loadUrl(currentJoinUrl()) }
                return@evaluateJavascript
            }
            if (join404Retries == 0) return@evaluateJavascript
            Log.e("VkAuthWV", "VK call link not found for hash ${joinHash.take(8)}...")
            VkAuthWebViewManager.logAuth("Хеш звонка недействителен: ${joinHash.take(8)}…", isError = true)
            VkAuthWebViewManager.notifyTurnResult(
                Result.failure(
                    Exception("Ссылка на звонок VK недействительна или устарела. Обновите хеш звонка.")
                )
            )
            finish()
        }
    }

    private fun checkLoginAndClose(pageUrl: String?) {
        if (loginHandled || screenMode != VkAuthScreenMode.LOGIN) return
        val sid = VkAuthWebViewManager.vkRemixSid()
        if (sid.length < 8) return

        val url = pageUrl.orEmpty().lowercase()
        if (isOnVkLoginFlow(url)) return

        loginHandled = true
        VkAuthWebViewManager.logAuth("Вход VK выполнен", verbose = true)
        VkAuthWebViewManager.notifyLoginSuccess()
        finish()
    }

    private fun parseAndFinishTurn(json: String) {
        try {
            val obj = JSONObject(json)
            val user = obj.optString("username")
            val pass = obj.optString("credential")
            val urlsRaw = obj.optJSONArray("urls") ?: JSONArray()
            val urls = mutableListOf<String>()
            for (i in 0 until urlsRaw.length()) {
                val url = urlsRaw.optString(i)
                if (url.isNotBlank()) urls.add(url)
            }
            if (user.isBlank() || pass.isBlank() || urls.isEmpty()) {
                VkAuthWebViewManager.logAuth("Неполный turn_server: $json", isError = true)
                return
            }
            VkAuthWebViewManager.logAuth("TURN получены")
            VkAuthWebViewManager.notifyTurnResult(Result.success(VkTurnCreds(user, pass, urls)))
            finish()
        } catch (e: Exception) {
            Log.e("VkAuthWV", "parse error: ${e.message}")
        }
    }

    override fun onDestroy() {
        autoJoinJob?.cancel()
        super.onDestroy()
        if (VkAuthWebViewManager.activeActivity === this) {
            VkAuthWebViewManager.activeActivity = null
        }
    }
}

class VkAuthCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VkAuthWebViewManager.notifyCancelled()
        VkAuthWebViewManager.activeActivity?.finish()
        val notifMgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifMgr.cancel(9002)
    }
}
