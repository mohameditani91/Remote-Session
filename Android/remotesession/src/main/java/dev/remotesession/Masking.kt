package dev.remotesession

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.reflect.KClass

/** Covers this view (and everything inside it) with a placeholder in frames sent to the agent. */
var View.isRemoteSessionMasked: Boolean
    get() = getTag(R.id.remotesession_masked) == true
    set(value) = setTag(R.id.remotesession_masked, value)

object MaskRules {
    /** Masks the whole content of activities of these types. */
    fun activities(vararg types: KClass<out Activity>): (View) -> Boolean = { view ->
        view.id == android.R.id.content && view.context.findActivity().let { activity -> types.any { it.java.isInstance(activity) } }
    }
}

internal object Masks {

    /** Grown a little so anti-aliased edges never peek out. */
    const val PADDING_DP = 2f

    /** Masked areas of these windows, in scene pixels. */
    fun rects(windows: List<AppWindow>, rule: ((View) -> Boolean)?, density: Float): List<RectF> {
        val rects = mutableListOf<RectF>()
        val padding = PADDING_DP * density
        for (window in windows) {
            collect(window.root, rule) { rect ->
                rects += RectF(rect).apply {
                    offset(window.left.toFloat(), window.top.toFloat())
                    inset(-padding, -padding)
                }
            }
        }
        return rects
    }

    fun isMasked(view: View, rule: ((View) -> Boolean)?) = view.isRemoteSessionMasked || isPassword(view) || rule?.invoke(view) == true

    fun isPassword(view: View): Boolean {
        if (view !is TextView) return false
        if (view.transformationMethod is PasswordTransformationMethod) return true
        val type = view.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return type in setOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
    }

    /** Masked areas inside one window, in that window's pixels, clipped to what's on screen. */
    private fun collect(view: View, rule: ((View) -> Boolean)?, add: (RectF) -> Unit) {
        if (view.visibility != View.VISIBLE || view.alpha <= 0.01f) return
        if (isMasked(view, rule)) {
            val visible = Rect()
            if (view.getGlobalVisibleRect(visible) && visible.width() >= 1 && visible.height() >= 1) add(RectF(visible))
            return
        }
        if (ComposeSupport.isComposeView(view)) ComposeSupport.maskRects(view).forEach(add)
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), rule, add)
    }
}

/** What the agent sees over a masked area or a locked keyboard: drawn from nothing on the screen. */
internal object Placeholders {

    /** Drawing small and scaling up with filtering blurs without a blur pass. */
    private const val BLUR_SCALE = 6f
    private const val TILE_DP = 56f
    private const val GAP_DP = 12f

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 24
    }
    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)

    fun drawMask(canvas: Canvas, rect: RectF, density: Float) {
        val unit = density * BLUR_SCALE
        val width = max(1, ceil(rect.width() / unit).toInt())
        val height = max(1, ceil(rect.height() / unit).toInt())
        val bitmap = cached("mask-${width}x$height") {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { drawTiles(Canvas(it), width.toFloat(), height.toFloat(), 1 / BLUR_SCALE) }
        }
        canvas.drawBitmap(bitmap, null, rect, filter)
    }

    fun drawKeyboard(canvas: Canvas, rect: RectF, dark: Boolean, blurred: Boolean, density: Float) {
        if (!blurred) {
            canvas.save()
            canvas.translate(rect.left, rect.top)
            drawKeys(canvas, rect.width(), rect.height(), dark)
            canvas.restore()
            return
        }
        val unit = density * BLUR_SCALE / 2
        val width = max(1, ceil(rect.width() / unit).toInt())
        val height = max(1, ceil(rect.height() / unit).toInt())
        val bitmap = cached("keyboard-${width}x$height-$dark") {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { drawKeys(Canvas(it), width.toFloat(), height.toFloat(), dark) }
        }
        canvas.drawBitmap(bitmap, null, rect, filter)
    }

    private fun cached(key: String, make: () -> Bitmap): Bitmap = synchronized(cache) { cache.getOrPut(key, make) }

    private fun drawTiles(canvas: Canvas, width: Float, height: Float, dp: Float) {
        canvas.drawColor(Color.rgb(199, 201, 209))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 227, 232) }
        val tile = TILE_DP * dp
        val gap = GAP_DP * dp
        val columns = max(1, ((width - gap) / (tile + gap)).toInt())
        val rows = max(1, ((height - gap) / (tile + gap)).toInt())
        val w = (width - gap * (columns + 1)) / columns
        val h = (height - gap * (rows + 1)) / rows
        if (w <= 0 || h <= 0) return
        val radius = minOf(w, h) * 0.18f
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = gap + column * (w + gap)
            val y = gap + row * (h + gap)
            canvas.drawRoundRect(x, y, x + w, y + h, radius, radius, paint)
        }
    }

    private fun drawKeys(canvas: Canvas, width: Float, height: Float, dark: Boolean) {
        // A rect, not drawColor: on the frame's canvas drawColor would fill everything, not just the keyboard.
        canvas.drawRect(0f, 0f, width, height, Paint().apply { color = if (dark) Color.rgb(43, 43, 43) else Color.rgb(209, 212, 219) })
        val keys = KeyboardLayout.keys(width, height)
        val rowHeight = keys.firstOrNull()?.let { it.bottom - it.top } ?: return
        val key = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(107, 107, 107) else Color.WHITE }
        val special = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(74, 74, 74) else Color.rgb(171, 178, 189) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else Color.BLACK
            textSize = rowHeight * 0.4f
            textAlign = Paint.Align.CENTER
        }
        val radius = rowHeight * 0.12f
        for (box in keys) {
            canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, radius, radius, if (box.units > 1 && box.units < 6) special else key)
            val label = box.label ?: when (box.key) {
                KeyboardLayout.Key.Delete -> "⌫"
                KeyboardLayout.Key.Enter -> "⏎"
                else -> null
            } ?: continue
            canvas.drawText(label, (box.left + box.right) / 2, (box.top + box.bottom) / 2 - (text.ascent() + text.descent()) / 2, text)
        }
    }
}
