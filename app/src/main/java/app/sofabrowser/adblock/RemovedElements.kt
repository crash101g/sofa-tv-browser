package app.sofabrowser.adblock

import android.content.Context

/**
 * What the user took off pages: elements removed with "Remove element", kept per site as CSS selectors and hidden
 * again on every visit, and the sites that are cleaned up on every load ("Clean up this site automatically").
 * Ported from TVCouchBrowser (MIT, crash101g).
 */
class RemovedElements(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("removed_elements", Context.MODE_PRIVATE)

    private fun all(): Set<String> = prefs.getStringSet(KEY_SELECTORS, emptySet())!!

    private fun prefix(host: String) = Sites.of(host.lowercase().removePrefix("www.")) + SEP

    /** Saves [selector] for [host]'s site. False when it doesn't look like a plain selector, or too many are kept. */
    fun add(host: String, selector: String): Boolean {
        if (!usable(selector)) return false
        val set = HashSet(all())
        if (set.size >= MAX) return false
        set.add(prefix(host) + selector)
        prefs.edit().putStringSet(KEY_SELECTORS, set).apply()
        return true
    }

    fun selectors(host: String): List<String> {
        val p = prefix(host)
        return all().filter { it.startsWith(p) }.map { it.substring(p.length) }
    }

    fun count(host: String) = selectors(host).size

    fun clear(host: String) {
        val p = prefix(host)
        prefs.edit().putStringSet(KEY_SELECTORS, all().filterNot { it.startsWith(p) }.toSet()).apply()
    }

    fun autoClean(host: String) = Sites.of(host.lowercase().removePrefix("www.")) in prefs.getStringSet(KEY_AUTO, emptySet())!!

    fun setAutoClean(host: String, on: Boolean) {
        val set = HashSet(prefs.getStringSet(KEY_AUTO, emptySet())!!)
        val site = Sites.of(host.lowercase().removePrefix("www."))
        if (on) set.add(site) else set.remove(site)
        prefs.edit().putStringSet(KEY_AUTO, set).apply()
    }

    companion object {
        private const val KEY_SELECTORS = "selectors"
        private const val KEY_AUTO = "auto_clean"
        private const val SEP = "\u0001"
        private const val MAX = 500

        /** A selector that can only ever be one CSS selector: nothing that could close the rule or start another. */
        fun usable(selector: String) = selector.isNotBlank() && selector.length <= 200 && selector.none { it in "{}<>;@\\\n\r" || it == SEP[0] }
    }
}
