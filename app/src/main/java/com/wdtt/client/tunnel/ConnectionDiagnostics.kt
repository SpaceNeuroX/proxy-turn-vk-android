package com.wdtt.client

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проверка соединения через VK (go_client/netdiag.go): Go присылает строки
 * `DIAG|{json}` — начало прогона, шаги, итог. Здесь они собираются в
 * состояние для экрана диагностики.
 */
enum class DiagStepState { RUNNING, OK, FAIL, WARN, SKIP }

enum class DiagSeverity { OK, WARN, ERROR }

data class DiagStep(
    val id: String,
    val title: String,
    val state: DiagStepState,
    val text: String,
)

data class DiagVerdict(
    val code: String,
    val severity: DiagSeverity,
    val title: String,
    val text: String,
    val actions: List<String>,
    val facts: Map<String, String>,
)

data class DiagnosticsState(
    val runId: Long = 0L,
    val running: Boolean = false,
    /** Проверку запросили из приложения, Go её ещё не начал. */
    val requested: Boolean = false,
    val trigger: String = "",
    val mode: String = "",
    val steps: List<DiagStep> = emptyList(),
    val verdict: DiagVerdict? = null,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
) {
    val isEmpty: Boolean get() = !running && !requested && verdict == null && steps.isEmpty()
    /** Процесс остановился посреди проверки: шаги есть, итога нет. */
    val isInterrupted: Boolean get() = !running && !requested && verdict == null && steps.isNotEmpty()
    val isFatalPasswordVerdict: Boolean get() = verdict?.code in FATAL_CODES

    companion object {
        /** Сервер прямо сказал, что с паролем; повторять подключение бессмысленно. */
        val FATAL_CODES = setOf("password_unknown", "password_expired", "password_deactivated", "device_limit")
    }
}

object ConnectionDiagnostics {
    const val LINE_PREFIX = "DIAG|"

    /** Применяет одно событие из Go. null — строка не разобралась (тогда её просто игнорируем). */
    fun apply(state: DiagnosticsState, line: String, nowMs: Long = System.currentTimeMillis()): DiagnosticsState? {
        val json = runCatching { JSONObject(line.substringAfter(LINE_PREFIX)) }.getOrNull() ?: return null
        val run = json.optLong("run", 0L)
        return when (json.optString("ev")) {
            "start" -> DiagnosticsState(
                runId = run,
                running = true,
                trigger = json.optString("trigger"),
                mode = json.optString("mode"),
                startedAtMs = nowMs,
            )
            "step" -> {
                if (run != state.runId) return null
                val step = DiagStep(
                    id = json.optString("step"),
                    title = json.optString("title"),
                    state = when (json.optString("state")) {
                        "ok" -> DiagStepState.OK
                        "fail" -> DiagStepState.FAIL
                        "warn" -> DiagStepState.WARN
                        "skip" -> DiagStepState.SKIP
                        else -> DiagStepState.RUNNING
                    },
                    text = json.optString("text"),
                )
                val steps = state.steps.toMutableList()
                val index = steps.indexOfFirst { it.id == step.id }
                if (index >= 0) steps[index] = step else steps.add(step)
                state.copy(steps = steps)
            }
            "verdict" -> {
                if (run != state.runId) return null
                val actions = buildList {
                    val arr = json.optJSONArray("actions")
                    if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
                }.filter { it.isNotBlank() }
                val facts = buildMap {
                    val obj = json.optJSONObject("facts")
                    if (obj != null) for (key in obj.keys()) put(key, obj.optString(key))
                }
                state.copy(
                    verdict = DiagVerdict(
                        code = json.optString("code"),
                        severity = when (json.optString("sev")) {
                            "ok" -> DiagSeverity.OK
                            "warn" -> DiagSeverity.WARN
                            else -> DiagSeverity.ERROR
                        },
                        title = json.optString("title"),
                        text = json.optString("text"),
                        actions = actions,
                        facts = facts,
                    ),
                )
            }
            "end" -> {
                if (run != state.runId) return null
                // Шаги, которые так и остались «в процессе», — прерваны.
                val steps = state.steps.map {
                    if (it.state == DiagStepState.RUNNING) it.copy(state = DiagStepState.SKIP, text = "не завершено") else it
                }
                state.copy(running = false, requested = false, steps = steps, finishedAtMs = nowMs)
            }
            else -> null
        }
    }

    /** Подпись кнопки для действия из вердикта. */
    fun actionLabel(action: String): String = when {
        action == "switch_raw" -> "Перейти на Raw"
        action == "switch_wg" -> "Перейти на WG"
        action == "switch_tcp" -> "TURN через TCP"
        action == "switch_udp" -> "TURN через UDP"
        action == "reconnect" -> "Переподключить"
        action.startsWith("use_turn:") -> "Закрепить TURN ${action.substringAfter(':')}"
        else -> action
    }

    private val modeTitles = mapOf("dtls" to "WG (DTLS)", "direct" to "Direct", "raw" to "Raw")

    /** Человекочитаемые факты для экрана (без секретов: пароль и ключ сюда не попадают). */
    fun factRows(verdict: DiagVerdict?, mode: String): List<Pair<String, String>> {
        val facts = verdict?.facts.orEmpty()
        return buildList {
            facts["server"]?.let { add("Сервер" to it) }
            (facts["mode"] ?: mode).takeIf { it.isNotBlank() }?.let { add("Режим" to (modeTitles[it] ?: it)) }
            facts["turn"]?.let { turn ->
                val transport = facts["transport"]?.uppercase()
                add("TURN VK" to if (transport != null) "$turn · $transport" else turn)
            }
            facts["rtt_ms"]?.let { add("Задержка до сервера" to "$it мс") }
            facts["good_turn"]?.let { add("Рабочий TURN" to it) }
            facts["alive_port"]?.let { raw ->
                val (name, port) = raw.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                add("Отвечает" to "${modeTitles[name] ?: name}, порт $port")
            }
            facts["dk"]?.let { add("Ключ сервера в ссылке" to if (it == "true") "есть" else "нет") }
            facts["error"]?.takeIf { it.isNotBlank() }?.let { add("Ошибка" to it) }
        }
    }

    /** Отчёт для «Скопировать» — его можно смело отправлять в чат. */
    fun reportText(state: DiagnosticsState): String = buildString {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(if (state.finishedAtMs > 0) state.finishedAtMs else state.startedAtMs))
        appendLine("qWDTT — проверка соединения ($time)")
        state.verdict?.let {
            appendLine("Итог: ${it.title} [${it.code}]")
            appendLine(it.text)
        }
        appendLine()
        state.steps.forEach { step ->
            val mark = when (step.state) {
                DiagStepState.OK -> "✓"
                DiagStepState.FAIL -> "✗"
                DiagStepState.WARN -> "!"
                DiagStepState.SKIP -> "–"
                DiagStepState.RUNNING -> "…"
            }
            appendLine("$mark ${step.title}: ${step.text}")
        }
        val rows = factRows(state.verdict, state.mode)
        if (rows.isNotEmpty()) {
            appendLine()
            rows.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }.trimEnd()
}
