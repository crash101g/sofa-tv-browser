package app.sofabrowser.phone

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.adblock.PageTools
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.CursorLayout
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.ui.Ui
import app.sofabrowser.ui.px
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * The phone as a remote, keyboard and trackpad (ported from TVCouchBrowser, MIT, crash101g). The phone scans a QR code
 * and opens a page that Sofa serves on the home network ([PhoneServer]); nothing is installed on the phone.
 *
 * Off until the QR code is first shown, and running only while Sofa's browser screen is started. On the page itself the
 * trackpad drives Sofa's pointer ([CursorLayout]); anywhere else (the home screen, the menu, dialogs, fullscreen video)
 * its moves work as D-pad arrows and a tap as OK, so the phone can go wherever the remote can.
 */
class PhoneRemote(private val activity: Activity, private val host: Host) : PhoneServer.Actions {

    interface Host {
        val cursor: CursorLayout
        val web: WebView?
        /** The page is what is on screen and has the keys: no home screen, menu, dialog or fullscreen video over it. */
        fun pageInFront(): Boolean
        /** Opens [url] (http or https) as if a link from another app asked for it. */
        fun openFromPhone(url: String)
        fun zoom(zoomIn: Boolean)
        /** Removes the element under the pointer and remembers it for the site. */
        fun removeAtPointer()
        fun cleanUp()
    }

    private val app = activity.applicationContext
    val tokens by lazy { TokenStore(app) }
    private val serverHolder = lazy { PhoneServer(app, tokens, this) }
    private val server by serverHolder
    private val main = Handler(Looper.getMainLooper())
    /** Key injection waits for the key to be handled, so it runs off the main thread. */
    private val injector = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-keys") }
    private val instrumentation = Instrumentation()

    @Volatile private var started = false

    /** From the activity's onStart. */
    fun start() {
        started = true
        if (tokens.enabled) server.start()
    }

    /** From the activity's onStop. */
    fun stop() {
        started = false
        if (tokens.enabled) server.stop()
    }

    // Pairing

    private fun lanAddress(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Shows the QR code to scan, turning the phone page on. */
    fun pairDialog() {
        if (!tokens.enabled) tokens.enabled = true
        if (started) server.start()
        val ip = lanAddress()
        if (ip == null || server.port == 0) { toast(activity.getString(R.string.phone_no_network)); return }
        val url = "http://$ip:${server.port}/?t=${tokens.token}"
        val size = 440.px
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48.px, 32.px, 48.px, 16.px)
            addView(ImageView(context).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(size, size))
            addView(TextView(context).apply {
                text = activity.getString(R.string.phone_pair_text, "http://$ip:${server.port}/open?t=${tokens.token}")
                setTextColor(Ui.p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 28.px.toFloat())
                setPadding(48.px, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        Ui.dialog(activity).setTitle(R.string.phone_pair_title).setView(content)
            .setPositiveButton(R.string.phone_done, null)
            .setNeutralButton(R.string.phone_forget) { _, _ -> forget() }
            .show()
    }

    /** A new token, so old QR codes and Shortcuts stop working, and the phone page off until paired again. */
    fun forget() {
        server.stop()
        tokens.rotate()
        tokens.enabled = false
        toast(activity.getString(R.string.phone_forgotten))
    }

    // PhoneServer.Actions: called on server threads.

    override fun openUrl(input: String): Boolean {
        val url = UrlInput.resolve(input, Prefs(app).searchEngine)
        if (!Browser.isWebUrl(url)) return false
        main.post { host.openFromPhone(url) }
        return true
    }

    override fun type(text: String) {
        if (text.isEmpty()) return
        main.post {
            val web = host.web
            if (web == null || !host.pageInFront()) { injectText(text); return@post }
            PageTools.type(web, text) { r ->
                when (r) {
                    "ok" -> Unit
                    "iframe" -> injectText(text) // a field in a frame from another site: type it as keys
                    else -> toast(activity.getString(R.string.phone_no_field))
                }
            }
        }
    }

    override fun key(name: String): Boolean {
        val code = when (name) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK
            "menu" -> KeyEvent.KEYCODE_MENU; "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "enter" -> KeyEvent.KEYCODE_ENTER; "backspace" -> KeyEvent.KEYCODE_DEL
            else -> return false
        }
        main.post {
            val web = host.web
            val onPage = web != null && host.pageInFront()
            when {
                // Text keys go straight to the page's field: through Sofa's key handling, Enter would click at the pointer.
                onPage && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DEL) -> {
                    web!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                // A phone arrow on the page moves the pointer a fixed step, which a quick injected press would not.
                onPage && host.cursor.cursorEnabled && code in ARROWS -> {
                    val step = 40f * Ui.scale
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> host.cursor.padMove(0f, -step)
                        KeyEvent.KEYCODE_DPAD_DOWN -> host.cursor.padMove(0f, step)
                        KeyEvent.KEYCODE_DPAD_LEFT -> host.cursor.padMove(-step, 0f)
                        else -> host.cursor.padMove(step, 0f)
                    }
                }
                else -> injectKey(code)
            }
        }
        return true
    }

    override fun pointerConnected() {
        main.post { toast(activity.getString(R.string.phone_trackpad_connected)) }
    }

    override fun pointerDisconnected() {
        main.post { host.cursor.padRelease() }
    }

    override fun pointer(cmd: PointerProtocol.Command) {
        main.post { onPointer(cmd) }
    }

    private var keyX = 0f
    private var keyY = 0f

    private fun onPointer(cmd: PointerProtocol.Command) {
        if (!host.pageInFront()) { offPage(cmd); return }
        val c = host.cursor
        val u = Ui.scale
        when (cmd.op) {
            'm' -> c.padMove(cmd.a * u * PAD_GAIN, cmd.b * u * PAD_GAIN)
            's' -> c.padScroll(cmd.a * u * PAD_SCROLL_GAIN, cmd.b * u * PAD_SCROLL_GAIN)
            'c' -> c.padTap()
            '2' -> { c.padTap(); main.postDelayed({ c.padTap() }, 90) }
            'd' -> c.padPress()
            'u' -> c.padRelease()
            'l' -> c.padLongPress()
            'z' -> host.zoom(cmd.a > 0)
            'x' -> host.removeAtPointer()
            'k' -> host.cleanUp()
        }
    }

    /** Away from the page the trackpad is a D-pad: moves step through what has focus, a tap presses OK. */
    private fun offPage(cmd: PointerProtocol.Command) {
        when (cmd.op) {
            'm', 's' -> {
                keyX += cmd.a
                keyY += cmd.b
                while (keyY >= KEY_STEP) { keyY -= KEY_STEP; injectKey(KeyEvent.KEYCODE_DPAD_DOWN) }
                while (keyY <= -KEY_STEP) { keyY += KEY_STEP; injectKey(KeyEvent.KEYCODE_DPAD_UP) }
                while (keyX >= KEY_STEP) { keyX -= KEY_STEP; injectKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
                while (keyX <= -KEY_STEP) { keyX += KEY_STEP; injectKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            }
            'c' -> injectKey(KeyEvent.KEYCODE_DPAD_CENTER)
            'u' -> host.cursor.padRelease()
        }
    }

    /**
     * Sends a key as if from the remote. Android lets an app inject input only into its own windows; anything else
     * on top (another app, a system dialog) refuses it, and then the press is dropped.
     */
    private fun injectKey(code: Int) = injector.execute {
        runCatching { instrumentation.sendKeyDownUpSync(code) }
    }

    private fun injectText(text: String) = injector.execute {
        runCatching { instrumentation.sendStringSync(text) }
    }

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()

    fun destroy() {
        if (serverHolder.isInitialized()) server.stop()
        injector.shutdownNow()
    }

    private companion object {
        val ARROWS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        /** TV pixels (on the 1920 px design grid) per phone pixel, before the phone page's own Speed setting. */
        const val PAD_GAIN = 2f
        const val PAD_SCROLL_GAIN = 3f
        /** Phone pixels of trackpad movement per arrow key away from the page. */
        const val KEY_STEP = 60f
    }
}
