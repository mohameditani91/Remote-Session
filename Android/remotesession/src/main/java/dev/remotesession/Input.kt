package dev.remotesession

import android.graphics.Matrix
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.sign

/**
 * Replays agent input into the app's own windows. Taps are touch events sent to the window under the point,
 * so Views and Compose handle them as they would a finger. Scrolls are mouse-wheel events, which never tap or fling.
 */
internal class InputDispatcher(private val windows: AppWindows, private val maskRule: ((View) -> Boolean)?) {

    private class Hit(val scene: Scene, val list: List<AppWindow>, val window: AppWindow, val x: Float, val y: Float, val chain: List<View>)

    fun perform(input: RemoteInput): InputResult {
        val scene = windows.scene() ?: return InputResult.nothing("noTarget")
        val list = windows.list(scene)
        return when (input) {
            is RemoteInput.Tap -> tap(scene, list, scene.width * clamp(input.x), scene.height * clamp(input.y))
            is RemoteInput.Scroll -> scroll(scene, list, input)
            is RemoteInput.Text -> insert(list, input.text)
            is RemoteInput.Key -> press(list, input.key)
            RemoteInput.Back -> goBack(list)
            RemoteInput.HideKeyboard -> hideKeyboard(list)
        }
    }

    /** Names what an input reached, for the audit log. Never reads typed text, row text, labels or masked areas. */
    fun describe(input: RemoteInput): String? {
        val scene = windows.scene() ?: return null
        val list = windows.list(scene)
        return when (input) {
            is RemoteInput.Tap -> {
                val x = scene.width * clamp(input.x)
                val y = scene.height * clamp(input.y)
                if (Ime.rect(list)?.contains(x, y) == true) return "keyboard"
                if (Masks.rects(list, maskRule, scene.density).any { it.contains(x, y) }) return "hidden area"
                val hit = hit(scene, list, x, y) ?: return null
                hit.chain.firstNotNullOfOrNull { view ->
                    if (ComposeSupport.isComposeView(view)) ComposeSupport.describe(view, hit.x, hit.y) else describe(view)
                }
            }
            is RemoteInput.Text, is RemoteInput.Key -> {
                val (_, focused) = Ime.focusedView(list) ?: return null
                if (ComposeSupport.isComposeView(focused)) ComposeSupport.describeFocused(focused) else describe(focused)
            }
            else -> null
        }
    }

    fun screenName(): String? = windows.activity?.javaClass?.simpleName

    private fun clamp(value: Double) = value.coerceIn(0.0, 1.0).toFloat()

    /** The window a finger there would reach: the top one under it, or a modal one above it that closes on an outside tap. */
    private fun hit(scene: Scene, list: List<AppWindow>, x: Float, y: Float): Hit? {
        val window = list.asReversed().firstOrNull { it.isTouchable && (it.contains(x, y) || it.isTouchModal) } ?: return null
        val localX = x - window.left
        val localY = y - window.top
        return Hit(scene, list, window, localX, localY, views(window.root, localX, localY))
    }

    /** Views under a point in a window, innermost first. */
    private fun views(root: View, x: Float, y: Float): List<View> {
        val chain = mutableListOf(root)
        var view = root
        val point = floatArrayOf(x, y)
        while (view is ViewGroup) {
            val parent = view
            val child = (parent.childCount - 1 downTo 0).map(parent::getChildAt).firstOrNull { child ->
                if (child.visibility != View.VISIBLE) return@firstOrNull false
                val local = local(parent, child, point)
                local[0] >= 0 && local[1] >= 0 && local[0] < child.width && local[1] < child.height
            } ?: break
            local(parent, child, point).copyInto(point)
            chain += child
            view = child
        }
        return chain.asReversed()
    }

    private fun local(parent: ViewGroup, child: View, point: FloatArray): FloatArray {
        val local = floatArrayOf(point[0] + parent.scrollX - child.left, point[1] + parent.scrollY - child.top)
        if (!child.matrix.isIdentity) Matrix().also { child.matrix.invert(it) }.mapPoints(local)
        return local
    }

    private fun tap(scene: Scene, list: List<AppWindow>, x: Float, y: Float): InputResult {
        Ime.rect(list)?.takeIf { it.contains(x, y) }?.let { keyboard ->
            if (Ime.isSensitive(list, Masks.rects(list, maskRule, scene.density), maskRule)) return InputResult.nothing("keyboard")
            return when (val key = KeyboardLayout.key(x - keyboard.left, y - keyboard.top, keyboard.width(), keyboard.height())) {
                is KeyboardLayout.Key.Character -> insert(list, key.text)
                KeyboardLayout.Key.Space -> insert(list, " ")
                KeyboardLayout.Key.Delete -> press(list, RemoteKey.BACKSPACE)
                KeyboardLayout.Key.Enter -> press(list, RemoteKey.RETURN)
                null -> InputResult.nothing("keyboard")
            }
        }
        val hit = hit(scene, list, x, y) ?: return InputResult.nothing("noTarget")
        for (view in hit.chain) {
            if (ComposeSupport.isComposeView(view)) {
                if (ComposeSupport.isDisabled(view, hit.x, hit.y)) return InputResult.nothing("disabled")
                break
            }
            if (view.isClickable || view.isLongClickable || view.onCheckIsTextEditor()) {
                if (!view.isEnabled) return InputResult.nothing("disabled")
                break
            }
        }
        return if (touch(hit.window.root, hit.x, hit.y)) InputResult.performed else InputResult.nothing("noTarget")
    }

    /** A finger down and up. True when something took the touch. */
    private fun touch(root: View, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = motion(now, now, MotionEvent.ACTION_DOWN, x, y)
        val up = motion(now, now + TAP_DURATION_MS, MotionEvent.ACTION_UP, x, y)
        return try {
            root.dispatchTouchEvent(down).also { root.dispatchTouchEvent(up) }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun motion(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        return MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun scroll(scene: Scene, list: List<AppWindow>, input: RemoteInput.Scroll): InputResult {
        val hit = hit(scene, list, scene.width * clamp(input.x), scene.height * clamp(input.y)) ?: return InputResult.nothing("noScrollView")
        val dx = (input.dx * scene.width).toFloat()
        val dy = (input.dy * scene.height).toFloat()
        val vertical = abs(dy) >= abs(dx)
        val direction = (if (vertical) dy else dx).sign.toInt()
        var sawScroller = false
        for (view in hit.chain) {
            if (ComposeSupport.isComposeView(view)) {
                ComposeSupport.scroll(view, hit.x, hit.y, dx, dy)?.let { return it }
                continue
            }
            val can = { way: Int -> if (vertical) view.canScrollVertically(way) else view.canScrollHorizontally(way) }
            if (direction != 0 && can(direction)) {
                wheel(view, dx, dy)
                return InputResult.performed
            }
            sawScroller = sawScroller || can(-direction)
        }
        return InputResult.nothing(if (sawScroller) "atEdge" else "noScrollView")
    }

    /** A mouse wheel turned over the view, by exactly this many pixels. */
    private fun wheel(view: View, dx: Float, dy: Float) {
        val configuration = ViewConfiguration.get(view.context)
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            x = view.width / 2f
            y = view.height / 2f
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / configuration.scaledVerticalScrollFactor)
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx / configuration.scaledHorizontalScrollFactor)
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        view.onGenericMotionEvent(event)
        event.recycle()
    }

    private fun insert(list: List<AppWindow>, text: String): InputResult {
        val (window, focused) = Ime.focusedView(list) ?: return InputResult.nothing("noFocusedField")
        if (ComposeSupport.isComposeView(focused)) {
            return when (ComposeSupport.insert(focused, text)) {
                true -> InputResult.performed
                false -> InputResult.nothing("cannotFocus")
                null -> keys(window.root, text)
            }
        }
        val editable = (focused as? TextView)?.takeIf { it.isEnabled && it.onCheckIsTextEditor() }?.editableText ?: return InputResult.nothing("noFocusedField")
        val start = minOf(focused.selectionStart, focused.selectionEnd).coerceAtLeast(0)
        val end = maxOf(focused.selectionStart, focused.selectionEnd).coerceAtLeast(0)
        editable.replace(start, end, text)
        return InputResult.performed
    }

    /** Types through key events, for fields that only take hardware keys. */
    private fun keys(root: View, text: String): InputResult {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return InputResult.nothing("unsupported")
        var handled = false
        for (event in events) handled = root.dispatchKeyEvent(event) || handled
        return if (handled) InputResult.performed else InputResult.nothing("cannotFocus")
    }

    private fun key(root: View, code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = root.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        val up = root.dispatchKeyEvent(KeyEvent(now, now + TAP_DURATION_MS, KeyEvent.ACTION_UP, code, 0))
        return down || up
    }

    private fun press(list: List<AppWindow>, remoteKey: RemoteKey): InputResult {
        val (window, focused) = Ime.focusedView(list) ?: return InputResult.nothing("noFocusedField")
        if (ComposeSupport.isComposeView(focused)) {
            if (!ComposeSupport.hasFocusedField(focused)) return InputResult.nothing("noFocusedField")
            if (remoteKey == RemoteKey.RETURN && ComposeSupport.imeAction(focused) == true) return InputResult.performed
            return if (key(window.root, if (remoteKey == RemoteKey.RETURN) KeyEvent.KEYCODE_ENTER else KeyEvent.KEYCODE_DEL)) InputResult.performed else InputResult.nothing("cannotFocus")
        }
        val field = (focused as? TextView)?.takeIf { it.isEnabled && it.onCheckIsTextEditor() } ?: return InputResult.nothing("noFocusedField")
        val editable = field.editableText ?: return InputResult.nothing("cannotFocus")
        when (remoteKey) {
            RemoteKey.RETURN -> {
                if (field.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return insert(list, "\n")
                val action = field.imeOptions and EditorInfo.IME_MASK_ACTION
                field.onEditorAction(if (action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE) EditorInfo.IME_ACTION_DONE else action)
            }
            RemoteKey.BACKSPACE -> {
                val start = minOf(field.selectionStart, field.selectionEnd)
                val end = maxOf(field.selectionStart, field.selectionEnd)
                when {
                    start < 0 -> return InputResult.nothing("cannotFocus")
                    start != end -> editable.delete(start, end)
                    start > 0 -> editable.delete(Character.offsetByCodePoints(editable, start, -1), start)
                }
            }
        }
        return InputResult.performed
    }

    private fun goBack(list: List<AppWindow>): InputResult {
        val top = Ime.focusedWindow(list) ?: return InputResult.nothing("nothingToGoBack")
        val activity = windows.activity ?: return InputResult.nothing("nothingToGoBack")
        if (top.root !== activity.window.decorView) {
            // Dialogs, popups and menus close on Back; on Android 13+ a dialog that uses predictive back ignores key events.
            return if (key(top.root, KeyEvent.KEYCODE_BACK)) InputResult.performed else InputResult.nothing("alertOpen")
        }
        val handled = hasComponentActivity && activity is ComponentActivity && activity.onBackPressedDispatcher.hasEnabledCallbacks()
        // At the root, Back would leave the app.
        if (!handled && activity.isTaskRoot) return InputResult.nothing("nothingToGoBack")
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        return InputResult.performed
    }

    private fun hideKeyboard(list: List<AppWindow>): InputResult {
        val (window, focused) = Ime.focusedView(list) ?: return InputResult.nothing("noKeyboard")
        if (Ime.rect(list) == null) return InputResult.nothing("noKeyboard")
        val hidden = focused.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.root.windowToken, 0)
        focused.clearFocus()
        return if (hidden) InputResult.performed else InputResult.nothing("keyboardStays")
    }

    private fun describe(view: View): String? {
        val description = view.contentDescription?.toString()
        val parent = view.parent
        return when {
            view is EditText -> named(view.hint?.toString() ?: description)?.let { "\"$it\" field" } ?: "text field"
            view is CompoundButton -> {
                val kind = when (view) {
                    is Switch -> "switch"
                    is CheckBox -> "checkbox"
                    is RadioButton -> "radio button"
                    else -> "toggle"
                }
                named(description ?: view.text?.toString())?.let { "\"$it\" $kind" } ?: kind
            }
            view is Button -> named(description ?: view.text?.toString())?.let { "\"$it\" button" } ?: "button"
            hasRecyclerView && parent is RecyclerView -> parent.getChildAdapterPosition(view).takeIf { it >= 0 }?.let { "row ${it + 1}" } ?: "row"
            parent is AdapterView<*> -> parent.getPositionForView(view).takeIf { it >= 0 }?.let { "row ${it + 1}" } ?: "row"
            !view.isClickable -> null
            view is TextView -> named(description ?: view.text?.toString())?.let { "\"$it\" button" } ?: "button"
            else -> named(description)?.let { "\"$it\"" }
        }
    }

    private fun named(text: String?): String? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.length > 40) trimmed.take(40) + "…" else trimmed
    }

    private companion object {
        const val TAP_DURATION_MS = 40L
        val hasComponentActivity = runCatching { Class.forName("androidx.activity.ComponentActivity") }.isSuccess
        val hasRecyclerView = runCatching { Class.forName("androidx.recyclerview.widget.RecyclerView") }.isSuccess
    }
}
