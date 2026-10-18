package com.glebsbp.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Пишет vk_profile.json в filesDir для Go RJS-капчи.
 * На каждый запуск туннеля — новый browser_fp, UA и слегка варьированный device_json.
 */
object VkCaptchaProfile {

    private const val PROFILE_FILE = "vk_profile.json"

    fun writeForGo(context: Context) {
        val appContext = context.applicationContext

        val ua = UserAgentGenerator.generate()
        val chromeMajor = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.getOrNull(1) ?: "131"
        val secChUa =
            "\"Chromium\";v=\"$chromeMajor\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"$chromeMajor\""
        val isMobileUa = ua.contains("Mobile", ignoreCase = true)

        val metrics = appContext.resources.displayMetrics
        val width = jitter(metrics.widthPixels.coerceAtLeast(320), 16)
        val height = jitter(metrics.heightPixels.coerceAtLeast(480), 20)
        val innerHeight = jitter((height * 0.88).toInt().coerceAtLeast(400), 12)
        val dprOptions = listOf(1.0, 1.25, 1.5, 2.0, 2.5, 3.0)
        val dpr = dprOptions[Random.nextInt(dprOptions.size)]

        val langSets = listOf(
            listOf("ru-RU", "ru", "en-US"),
            listOf("ru-RU", "ru"),
            listOf("en-US", "en", "ru-RU"),
        )
        val langs = langSets[Random.nextInt(langSets.size)]

        val deviceJson = JSONObject().apply {
            put("screenWidth", width)
            put("screenHeight", height)
            put("screenAvailWidth", width)
            put("screenAvailHeight", height)
            put("innerWidth", width)
            put("innerHeight", innerHeight)
            put("devicePixelRatio", dpr)
            put("language", langs.first())
            put("languages", JSONArray(langs))
            put("webdriver", false)
            put("hardwareConcurrency", (4 + Random.nextInt(5)).coerceIn(4, 8))
            put(
                "notificationsPermission",
                listOf("default", "denied", "granted")[Random.nextInt(3)]
            )
        }

        val profile = JSONObject().apply {
            put("user_agent", ua)
            put("sec_ch_ua", secChUa)
            put("sec_ch_ua_mobile", if (isMobileUa) "?1" else "?0")
            put("sec_ch_ua_platform", if (isMobileUa) "\"Android\"" else "\"Windows\"")
            put("device_json", deviceJson.toString())
            put("browser_fp", newBrowserFp())
        }

        File(appContext.filesDir, PROFILE_FILE).writeText(profile.toString())
    }

    private fun jitter(base: Int, delta: Int): Int {
        return (base + Random.nextInt(-delta, delta + 1)).coerceAtLeast(320)
    }

    private fun newBrowserFp(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
