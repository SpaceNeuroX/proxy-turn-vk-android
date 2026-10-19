package com.glebsbp.client

import android.webkit.CookieManager

/** Очистка cookies VK-капчи без затрагивания сессии входа в аккаунт. */
object VkWebViewCookies {

    private val CAPTCHA_HOSTS = listOf(
        "https://id.vk.ru",
        "https://id.vk.com",
        "https://vk.ru",
        "https://vk.com",
    )

    fun clearCaptchaCookies() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        for (host in CAPTCHA_HOSTS) {
            val cookies = cm.getCookie(host) ?: continue
            for (part in cookies.split(";")) {
                val name = part.trim().substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cm.setCookie(host, "$name=; Max-Age=0; Path=/")
                }
            }
        }
        cm.flush()
    }
}
