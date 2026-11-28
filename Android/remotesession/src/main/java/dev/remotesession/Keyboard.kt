package dev.remotesession

import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * A drawn keyboard the agent sees, and can tap, where the system keyboard is open: the IME runs in another
 * process, so it never shows up in the app's own windows. Blurred and locked while the focused field is masked.
 */
internal object KeyboardLayout {

    sealed interface Key {
        data class Character(val text: String) : Key
        data object Delete : Key
        data object Enter : Key
        data object Space : Key
    }

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val key: Key?, val units: Float, val label: String?) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    private val rows: List<List<Float>> = listOf(
        List(13) { 1f } + 1.5f,
        listOf(1.5f) + List(13) { 1f },
        listOf(1.75f) + List(11) { 1f } + 1.75f,
        listOf(2.25f) + List(10) { 1f } + 2.25f,
        listOf(1.5f, 1.5f, 1.5f, 6.5f, 1.5f, 2f),
    )
    private val letters = listOf("`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./", "").map { row -> row.map(Char::toString) }

    /** Keys laid out in a keyboard of this size, origin top-left. */
    fun keys(width: Float, height: Float): List<Box> {
        val pad = width * 0.006f
        val left = pad
        val top = height * 0.12f + pad
        val areaWidth = width - 2 * pad
        val areaHeight = height - top - pad
        val gap = areaWidth * 0.006f
        val rowHeight = (areaHeight - gap * (rows.size - 1)) / rows.size
        if (rowHeight <= 0) return emptyList()
        return rows.flatMapIndexed { index, row ->
            val unit = (areaWidth - gap * (row.size - 1)) / row.sum()
            val y = top + index * (rowHeight + gap)
            val labels = letters[index].iterator()
            var x = left
            row.mapIndexed { position, units ->
                val label = if (units == 1f && labels.hasNext()) labels.next() else null
                val key = when {
                    label != null -> Key.Character(label)
                    index == 0 && position == row.lastIndex -> Key.Delete
                    index == 2 && position == row.lastIndex -> Key.Enter
                    index == 4 && position == 3 -> Key.Space
                    else -> null
                }
                Box(x, y, x + unit * units, y + rowHeight, key, units, label).also { x = it.right + gap }
            }
        }
    }

    fun key(x: Float, y: Float, width: Float, height: Float): Key? = keys(width, height).firstOrNull { it.contains(x, y) }?.key
}

internal object Ime {

    /** Where the system keyboard covers the app, in scene pixels: measured from the focused window, else the activity's. */
    fun rect(windows: List<AppWindow>): RectF? = listOfNotNull(focusedWindow(windows), windows.firstOrNull()).firstNotNullOfOrNull { window ->
        val insets = window.root.rootWindowInsets ?: return@firstNotNullOfOrNull null
        @Suppress("DEPRECATION")
        val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.ime()).bottom
        else maxOf(0, insets.systemWindowInsetBottom - insets.stableInsetBottom)
        if (bottom < 1) null
        else RectF(window.left.toFloat(), (window.top + window.height - bottom).toFloat(), (window.left + window.width).toFloat(), (window.top + window.height).toFloat())
    }

    /** The window that has the keyboard's attention: the top one with focus, or the scene. */
    fun focusedWindow(windows: List<AppWindow>): AppWindow? = windows.lastOrNull { it.root.hasWindowFocus() } ?: windows.firstOrNull()

    fun focusedView(windows: List<AppWindow>): Pair<AppWindow, View>? {
        val window = focusedWindow(windows) ?: return null
        return window.root.findFocus()?.let { window to it }
    }

    /** True while the focused field is a password, masked, or under a masked area: the drawn keyboard is then blurred and locked. */
    fun isSensitive(windows: List<AppWindow>, masks: List<RectF>, rule: ((View) -> Boolean)?): Boolean {
        val (window, focused) = focusedView(windows) ?: return false
        val local = masks.map { RectF(it).apply { offset(-window.left.toFloat(), -window.top.toFloat()) } }
        if (ComposeSupport.isComposeView(focused)) return ComposeSupport.focusedIsSensitive(focused, local)
        var view: View? = focused
        while (view != null) {
            if (Masks.isMasked(view, rule)) return true
            view = view.parent as? View
        }
        val visible = android.graphics.Rect()
        if (!focused.getGlobalVisibleRect(visible)) return false
        return local.any { RectF.intersects(it, RectF(visible)) }
    }
}
