package com.glebsbp.client

import kotlin.random.Random

/** Случайный desktop Chrome UA для WebView-капчи. */
object VkCaptchaWebViewUa {

    private val CHROME_BUILDS = arrayOf(
        "146.0.0.0", "145.0.6422.60", "145.0.6422.53",
        "144.0.6367.78", "144.0.6367.61", "143.0.6312.99",
    )

    fun randomDesktop(): String {
        val build = CHROME_BUILDS[Random.nextInt(CHROME_BUILDS.size)]
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$build Safari/537.36"
    }
}
