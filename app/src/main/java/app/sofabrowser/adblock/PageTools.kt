package app.sofabrowser.adblock

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * Blocking for what the filter lists miss, ported from TVCouchBrowser (MIT, crash101g):
 *
 * - **Clean up page** removes full-screen overlays, cookie and newsletter walls and sticky ad bars, and makes a
 *   scroll-locked or blurred page usable again. It keeps the site's own header and navigation (anything holding most of
 *   the page's links).
 * - **Remove element** takes away the element at a point (or the focused one) and returns a selector to remember it by
 *   for the site ([RemovedElements]), or "-" when no selector picks it out safely.
 * - **Typing from the phone** into the field that has focus.
 * - **YouTube**: the ad entries are dropped from the player's data before the player reads them, which is what uBlock
 *   Origin's json-prune scriptlets do. YouTube may change this or notice it.
 *
 * Everything but the YouTube script runs only when Sofa calls it, in the main frame, through evaluateJavascript: pages
 * get nothing they could call. The functions live under a random, non-enumerable name for the life of the process.
 */
object PageTools {

    private val KEY = "_" + (1..12).map { "abcdefghijklmnopqrstuvwxyz"[java.security.SecureRandom().nextInt(26)] }.joinToString("")

    private val TOOLS = """
(() => {
  const ANNOY = /(^|[-_\s])(ad|ads|advert|adsbox|banner|sponsor|promo|cookie|consent|gdpr|cmp|newsletter|subscribe|popup|pop-up|modal|overlay|interstitial|paywall|lightbox|backdrop)([-_\s]|$)/i;
  const nameOf = (el) => (el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '');
  const unlockScroll = () => {
    let changed = false;
    for (const el of [document.documentElement, document.body]) {
      if (!el) continue;
      const st = getComputedStyle(el);
      if (st.overflow === 'hidden' || st.overflowY === 'hidden') {
        el.style.setProperty('overflow', 'auto', 'important');
        el.style.setProperty('overflow-y', 'auto', 'important');
        changed = true;
      }
      if (el === document.body && st.position === 'fixed') {
        // Sites lock scrolling by fixing the body at -scrollY; put it back where it was.
        const y = -parseInt(el.style.top || '0', 10) || 0;
        el.style.setProperty('position', 'static', 'important');
        el.style.setProperty('top', 'auto', 'important');
        if (y) window.scrollTo(0, y);
        changed = true;
      }
    }
    // Paywalls and cookie walls blur the article behind them.
    for (const el of document.querySelectorAll('body, body > *, main, article')) {
      const f = getComputedStyle(el).filter;
      if (f && f.includes('blur')) { el.style.setProperty('filter', 'none', 'important'); changed = true; }
    }
    return changed;
  };
  const cleanup = () => {
    let n = 0;
    const vw = innerWidth, vh = innerHeight;
    const links = document.querySelectorAll('a[href]').length;
    const fs = document.fullscreenElement;
    for (const el of document.querySelectorAll('body *')) {
      if (!el.isConnected) continue;
      const st = getComputedStyle(el);
      if (st.position !== 'fixed' && st.position !== 'sticky') continue;
      if (fs && (el.contains(fs) || fs.contains(el))) continue;
      const r = el.getBoundingClientRect();
      const w = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
      const h = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
      if (!w || !h) continue;
      const cover = (w * h) / (vw * vh);
      // Never take the page's own layout: something holding most of its links is the site itself.
      const own = el.querySelectorAll('a[href]').length;
      if (links > 20 && own > links * 0.5) continue;
      // A playing video's container is what the user came for.
      if (el.querySelector('video')) continue;
      const big = cover >= 0.3 && st.position === 'fixed';
      const annoying = ANNOY.test(nameOf(el)) && own <= 8;
      const adFrame = cover < 0.3 && own <= 3 && el.querySelector('iframe') && (r.top <= 0 || r.bottom >= vh - 1);
      if (big || annoying || adFrame) { el.remove(); n++; }
    }
    if (unlockScroll()) n++;
    return n;
  };
  const stableName = (x) => /^[A-Za-z_][\w-]{1,40}$/.test(x) && !/\d{4,}/.test(x) && !/^(css|sc|jsx|emotion)-/.test(x);
  const selectorFor = (el) => {
    const tag = el.tagName.toLowerCase();
    let sel = '';
    if (el.id && stableName(el.id)) sel = '#' + CSS.escape(el.id);
    else if (tag === 'iframe' && el.src) {
      try {
        const u = new URL(el.src, location.href);
        if (u.hostname && u.hostname !== location.hostname) sel = 'iframe[src*="' + u.hostname.replace(/"/g, '') + '"]';
      } catch (e) {}
    }
    if (!sel) {
      const cls = [...el.classList].filter(stableName).slice(0, 3);
      if (!cls.length) return '';
      sel = tag + cls.map((c) => '.' + CSS.escape(c)).join('');
      const p = el.parentElement;
      if (p && p.id && stableName(p.id)) sel = '#' + CSS.escape(p.id) + ' > ' + sel;
    }
    // Only remember selectors that pick out this element and few others.
    try {
      const count = document.querySelectorAll(sel).length;
      return count >= 1 && count <= 3 ? sel : '';
    } catch (e) { return ''; }
  };
  const area = (e) => { const r = e.getBoundingClientRect(); return r.width * r.height; };
  const zapEl = (el) => {
    if (!el || el === document.body || el === document.documentElement) return '';
    // Climb out of wrappers that are barely bigger than what was pointed at.
    while (el.parentElement && el.parentElement !== document.body && el.parentElement !== document.documentElement &&
      area(el.parentElement) <= area(el) * 1.25) el = el.parentElement;
    // Text or a picture inside an unnamed box: take the nearest named box around it (an ad slot, say), if it is small.
    if (!selectorFor(el)) {
      let p = el.parentElement;
      for (let i = 0; i < 3 && p && p !== document.body && p !== document.documentElement; i++, p = p.parentElement) {
        if (area(p) > innerWidth * innerHeight * 0.5) break;
        if (selectorFor(p)) { el = p; break; }
      }
    }
    const sel = selectorFor(el);
    el.remove();
    unlockScroll();
    return sel || '-';
  };
  const zapAt = (fx, fy) => zapEl(document.elementFromPoint(fx * innerWidth, fy * innerHeight));
  const zapFocused = () => {
    const el = document.activeElement;
    return el && el !== document.body ? zapEl(el) : '';
  };
  let sheet = null;
  const hide = (list) => {
    try {
      if (!sheet) sheet = new CSSStyleSheet();
      if (!document.adoptedStyleSheets.includes(sheet)) document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
      for (const s of list) { try { sheet.insertRule(s + '{display:none!important}', sheet.cssRules.length); } catch (e) {} }
    } catch (e) {}
    return list.length;
  };
  const editable = (el) => el && (el.isContentEditable || el.tagName === 'TEXTAREA' ||
    (el.tagName === 'INPUT' && /^(text|search|url|email|password|tel|number|)$/i.test(el.type)));
  const type = (text) => {
    const el = document.activeElement;
    if (!editable(el)) return el && el.tagName === 'IFRAME' ? 'iframe' : 'nofield';
    if (!document.execCommand('insertText', false, text)) {
      if (el.isContentEditable) el.textContent += text;
      else {
        const s = el.selectionStart ?? el.value.length, e = el.selectionEnd ?? el.value.length;
        el.setRangeText(text, s, e, 'end');
        el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
      }
    }
    return 'ok';
  };
  return Object.freeze({ cleanup, zapAt, zapFocused, hide, type });
})()
""".trimIndent()

    /** Runs `tools.<call>` in [web]'s main frame, defining the tools there first if this document doesn't have them yet. */
    private fun call(web: WebView, call: String, done: ((String) -> Unit)? = null) {
        val k = JSONObject.quote(KEY)
        val js = "(() => { let t = window[$k]; if (!t) { t = $TOOLS; " +
            "try { Object.defineProperty(window, $k, { value: t }); } catch (e) {} } return t.$call; })()"
        web.evaluateJavascript(js) { r -> done?.invoke(r ?: "null") }
    }

    /** Cleans up the page; [done] gets how many things were removed or fixed. */
    fun cleanUp(web: WebView, done: (Int) -> Unit = {}) = call(web, "cleanup()") { r -> done(r.trim('"').toIntOrNull() ?: 0) }

    /**
     * Removes the element at ([fx], [fy]), as fractions of the page's view, or the focused one when null. [done] gets ""
     * for nothing there, "-" for removed but not to be remembered, or the selector to remember.
     */
    fun removeAt(web: WebView, fx: Float?, fy: Float?, done: (String) -> Unit) {
        val c = if (fx == null || fy == null) "zapFocused()" else "zapAt($fx, $fy)"
        call(web, c) { r -> done(runCatching { JSONArray("[$r]").optString(0) }.getOrDefault("")) }
    }

    /** Hides [selectors] on the page (elements removed there before). */
    fun hide(web: WebView, selectors: List<String>) {
        if (selectors.isEmpty()) return
        call(web, "hide(${JSONArray(selectors.filter(RemovedElements::usable))})")
    }

    /** Types [text] into the page's focused field; [done] gets "ok", "iframe" (a frame has focus) or "nofield". */
    fun type(web: WebView, text: String, done: (String) -> Unit) = call(web, "type(${JSONObject.quote(text)})") { r -> done(r.trim('"')) }

    // YouTube

    private const val YOUTUBE = """
(() => {
  const AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];
  const prune = (o) => {
    if (o && typeof o === 'object') {
      for (const k of AD_KEYS) if (k in o) delete o[k];
      if (o.playerResponse) prune(o.playerResponse);
    }
    return o;
  };
  for (const name of ['ytInitialPlayerResponse', 'playerResponse']) {
    let v;
    try { Object.defineProperty(window, name, { configurable: true, get: () => v, set: (x) => { v = prune(x); } }); } catch (e) {}
  }
  const parse = JSON.parse;
  JSON.parse = function () { return prune(parse.apply(this, arguments)); };
  const json = Response.prototype.json;
  Response.prototype.json = function () { return json.apply(this, arguments).then(prune); };
})();
"""

    private val YOUTUBE_ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com",
        "https://music.youtube.com", "https://www.youtube-nocookie.com")

    private val youtubeScripts = java.util.WeakHashMap<WebView, ScriptHandler>()

    /** Adds or removes the YouTube script in [web]; it takes effect from the next page load. */
    @SuppressLint("RequiresFeature") // checked inline
    fun youtube(web: WebView, on: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val current = youtubeScripts[web]
        if (on && current == null) {
            runCatching { youtubeScripts[web] = WebViewCompat.addDocumentStartJavaScript(web, YOUTUBE, YOUTUBE_ORIGINS) }
        } else if (!on && current != null) {
            current.remove()
            youtubeScripts.remove(web)
        }
    }
}
