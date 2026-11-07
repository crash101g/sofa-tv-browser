package app.sofabrowser.adblock

import android.app.Activity
import android.view.KeyEvent
import android.webkit.WebView
import android.widget.Toast
import app.sofabrowser.R
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.CursorLayout
import app.sofabrowser.ui.Ui

/**
 * The Clean up button in the menu and what it runs: Clean up page, Remove element (point at it and press OK, or tap
 * Remove on the phone), Clean up this site automatically, and Bring back removed elements. See [PageTools].
 */
class CleanUp(private val activity: Activity, private val cursor: CursorLayout, private val web: () -> WebView?,
              private val blocking: (String?) -> Boolean) {

    private val removed = RemovedElements(activity)

    /** Set after "Remove element": the next OK removes what is under the pointer instead of pressing it. */
    var removeArmed = false
        private set

    private fun host() = Browser.hostOf(web()?.url)

    fun dialog() {
        val host = host() ?: return
        val auto = removed.autoClean(host)
        val count = removed.count(host)
        val items = mutableListOf<Pair<String, () -> Unit>>(
            activity.getString(R.string.clean_up_now) to { cleanUp(quiet = false) },
            activity.getString(R.string.remove_element) to { armRemove() },
            activity.getString(if (auto) R.string.clean_up_auto_on else R.string.clean_up_auto_off) to {
                removed.setAutoClean(host, !auto)
                toast(activity.getString(if (auto) R.string.clean_up_auto_removed else R.string.clean_up_auto_added))
                if (!auto) cleanUp(quiet = true)
            },
        )
        if (count > 0) items += activity.resources.getQuantityString(R.plurals.bring_back_removed, count, count) to {
            removed.clear(host)
            toast(activity.getString(R.string.removed_brought_back))
            web()?.reload()
        }
        Ui.dialog(activity).setTitle(R.string.clean_up)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    fun cleanUp(quiet: Boolean) {
        val w = web() ?: return
        PageTools.cleanUp(w) { n ->
            if (!quiet) toast(activity.getString(if (n > 0) R.string.cleaned_up else R.string.nothing_to_clean))
        }
    }

    private fun armRemove() {
        removeArmed = true
        toast(activity.getString(if (cursor.cursorEnabled) R.string.remove_element_point else R.string.remove_element_focus))
    }

    /** Takes OK while "Remove element" waits for it; true when the key was used. */
    fun key(event: KeyEvent): Boolean {
        if (!removeArmed) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_UP) { removeArmed = false; removeAtPointer(usePointer = cursor.cursorEnabled) }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) { removeArmed = false; toast(activity.getString(R.string.remove_element_cancelled)) }
                return true
            }
        }
        return false
    }

    /** Removes the element under the pointer (or the focused one) and remembers it for the site when it can. */
    fun removeAtPointer(usePointer: Boolean = true) {
        removeArmed = false
        val w = web() ?: return
        val host = host()
        val fx = if (usePointer) cursor.pointerX / w.width.coerceAtLeast(1) else null
        val fy = if (usePointer) cursor.pointerY / w.height.coerceAtLeast(1) else null
        PageTools.removeAt(w, fx, fy) { sel ->
            toast(activity.getString(when {
                sel.isEmpty() -> R.string.nothing_to_remove
                sel != "-" && host != null && removed.add(host, sel) -> R.string.removed_remembered
                else -> R.string.removed_this_page
            }))
        }
    }

    /** Hides what was removed on this site before, and cleans up sites that asked for it. */
    fun pageFinished(url: String) {
        val w = web() ?: return
        val host = Browser.hostOf(url) ?: return
        removeArmed = false
        if (!blocking(host)) return
        PageTools.hide(w, removed.selectors(host))
        if (removed.autoClean(host)) cleanUp(quiet = true)
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
