package app.sofabrowser.adblock

/**
 * Captcha and bot-check providers, which are never blocked: a blocked captcha can't be solved, and the site behind it
 * stays locked. Some filter lists block parts of them as trackers. Ported from TVCouchBrowser (MIT, crash101g).
 */
object Challenges {

    private val HOSTS = listOf(
        "challenges.cloudflare.com", "hcaptcha.com", "recaptcha.net", "arkoselabs.com", "funcaptcha.com",
    )

    fun exempt(host: String?, path: String?): Boolean {
        val h = host?.lowercase() ?: return false
        fun under(d: String) = h == d || h.endsWith(".$d")
        if (HOSTS.any(::under)) return true
        return (under("google.com") || under("gstatic.com")) && path.orEmpty().startsWith("/recaptcha")
    }
}
