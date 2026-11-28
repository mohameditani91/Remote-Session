package dev.remotesession

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * RemoteSession's own UI: the code and consent card, the approval card, the banner while a session runs, and the
 * marker at each agent tap. Each is a window on the resumed activity, left out of frames and out of agent input.
 */
internal class Overlay(private val windows: AppWindows, private val configuration: RemoteSession.Configuration) {

    var onCancel: (() -> Unit)? = null
    var onAllow: ((SessionMode) -> Unit)? = null
    var onDeny: (() -> Unit)? = null
    var onViewOnly: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onApprove: ((Boolean) -> Unit)? = null
    var onUserTouch: (() -> Unit)? = null

    private var host: Activity? = null
    private var card: View? = null
    private var banner: View? = null
    private var state: SessionState = SessionState.Connecting
    private var approval: String? = null
    private var bannerOffset: Pair<Int, Int>? = null
    private var lastTouch: Triple<Float, Float, Int>? = null
    private var lastTouchAt = 0L

    private val showsConsent get() = configuration.consentHandler == null

    fun attach(activity: Activity) {
        if (activity === host) return
        val decor = activity.window.decorView
        // A resumed activity's window is added just after onResume: follow it once it's there.
        if (!decor.isAttachedToWindow) {
            decor.post { if (windows.activity === activity) attach(activity) }
            return
        }
        detach()
        host = activity
        render(state, approval)
        // A tap that opened this screen still gets its marker, above the new screen.
        lastTouch?.takeIf { android.os.SystemClock.uptimeMillis() - lastTouchAt < TOUCH_MARKER_MS }?.let { (x, y, color) -> showTouch(x, y, color) }
    }

    fun detach(activity: Activity) {
        if (activity === host) detach()
    }

    fun detach() {
        card?.let(::remove)
        banner?.let(::remove)
        card = null
        banner = null
        host = null
    }

    fun render(state: SessionState, approval: String?) {
        this.state = state
        this.approval = approval
        val host = host ?: return
        val strings = host.resources
        val content: Card? = when (state) {
            SessionState.Connecting -> Card(strings.getString(R.string.remotesession_connecting), loading = true, actions = listOf(cancel(host)))
            is SessionState.WaitingForAgent -> Card(strings.getString(R.string.remotesession_code_title), code = state.code, message = strings.getString(R.string.remotesession_code_message), actions = listOf(cancel(host)))
            is SessionState.AwaitingConsent -> if (!showsConsent) null else Card(
                strings.getString(R.string.remotesession_consent_title),
                message = strings.getString(R.string.remotesession_consent_message, state.agent.name),
                actions = buildList {
                    if (configuration.allowsControl) add(Action(strings.getString(R.string.remotesession_allow_control), Style.FILLED) { onAllow?.invoke(SessionMode.CONTROL) })
                    add(Action(strings.getString(R.string.remotesession_allow_viewing), if (configuration.allowsControl) Style.TINTED else Style.FILLED) { onAllow?.invoke(SessionMode.VIEW) })
                    add(Action(strings.getString(R.string.remotesession_deny), Style.DESTRUCTIVE) { onDeny?.invoke() })
                },
            )
            is SessionState.Active -> approval?.let {
                Card(
                    strings.getString(R.string.remotesession_approval_title),
                    message = it,
                    actions = listOf(
                        Action(strings.getString(R.string.remotesession_allow), Style.FILLED) { onApprove?.invoke(true) },
                        Action(strings.getString(R.string.remotesession_deny), Style.DESTRUCTIVE) { onApprove?.invoke(false) },
                    ),
                )
            }
            is SessionState.Ended -> null
        }
        showBanner(host, state as? SessionState.Active)
        showCard(host, content)
    }

    /** A dot with two ripples at the agent's tap, so the user can follow along. Still and fading with animations off. */
    fun showTouch(screenX: Float, screenY: Float, color: Int) {
        lastTouch = Triple(screenX, screenY, color)
        lastTouchAt = android.os.SystemClock.uptimeMillis()
        val host = host ?: return
        val marker = TouchMarker(host, screenX, screenY, color)
        val params = params(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        if (!add(host, marker, params)) return
        marker.play { remove(marker) }
    }

    private fun cancel(context: Context) = Action(context.getString(R.string.remotesession_cancel), Style.PLAIN) { onCancel?.invoke() }

    private class Action(val title: String, val style: Style, val run: () -> Unit)

    private enum class Style { FILLED, TINTED, PLAIN, DESTRUCTIVE }

    private class Card(val title: String, val code: String? = null, val message: String? = null, val loading: Boolean = false, val actions: List<Action>)

    private fun params(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // Under the system bars too, so the card's dim covers the whole screen.
        if (android.os.Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        title = "RemoteSession"
    }

    private fun add(host: Activity, view: View, params: WindowManager.LayoutParams): Boolean {
        windows.own += view
        if (runCatching { host.windowManager.addView(view, params) }.isFailure) {
            windows.own -= view
            return false
        }
        if (params.flags and WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS != 0) matchSystemBars(host, view)
        return true
    }

    /**
     * The card draws behind the system bars, which makes it set their icons' color: keep the app's light or dark icons.
     * Light bars set by the theme don't show up in the activity's insets controller, so the theme counts too.
     */
    @Suppress("DEPRECATION")
    private fun matchSystemBars(host: Activity, view: View) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val status = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val navigation = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            fun themeSays(attr: Int) = host.theme.obtainStyledAttributes(intArrayOf(attr)).run { getBoolean(0, false).also { recycle() } }
            val fromTheme = (if (themeSays(android.R.attr.windowLightStatusBar)) status else 0) or
                (if (themeSays(android.R.attr.windowLightNavigationBar)) navigation else 0)
            val appearance = (host.window.insetsController?.systemBarsAppearance ?: 0) or fromTheme
            view.windowInsetsController?.setSystemBarsAppearance(appearance and (status or navigation), status or navigation)
        } else {
            view.systemUiVisibility = host.window.decorView.systemUiVisibility
        }
    }

    private fun remove(view: View) {
        windows.own -= view
        if (view.isAttachedToWindow) runCatching { (view.context as Activity).windowManager.removeViewImmediate(view) }
    }

    // A new window goes on top, so the card is added each time it appears: above dialogs the app opened meanwhile.
    private fun showCard(host: Activity, content: Card?) {
        if (content == null) {
            card?.let(::remove)
            card = null
            return
        }
        val root = card as? CardView ?: CardView(host).also { view ->
            val params = params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (!add(host, view, params)) return
            card = view
        }
        root.show(content)
    }

    @SuppressLint("ViewConstructor")
    private inner class CardView(context: Context) : FrameLayout(context) {

        private val dark = context.isDark
        private val title = text(17f, if (dark) Color.WHITE else Color.BLACK, bold = true)
        private val code = text(44f, configuration.tint, bold = true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        private val message = text(15f, if (dark) Color.rgb(174, 174, 178) else Color.rgb(99, 99, 102))
        private val spinner = ProgressBar(context).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(configuration.tint) }
        private val buttons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private var current: Card? = null

        init {
            setBackgroundColor(Color.argb(89, 0, 0, 0))
            isFocusable = true
            isFocusableInTouchMode = true
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(if (dark) Color.rgb(28, 28, 30) else Color.WHITE) }
                setPadding(dp(20), dp(24), dp(20), dp(16))
                elevation = dp(12f)
            }
            for (child in listOf(title, code, message, spinner, buttons)) {
                panel.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (child === title) 0 else dp(12) })
            }
            (buttons.layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
            val width = min(dp(340), context.resources.displayMetrics.widthPixels - dp(32))
            addView(panel, LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            post { requestFocus() }
        }

        fun show(card: Card) {
            current = card
            title.text = card.title
            code.visibility = if (card.code == null) GONE else VISIBLE
            code.text = card.code?.let { if (it.length == 6) "${it.take(3)} ${it.takeLast(3)}" else it }
            code.contentDescription = card.code?.toList()?.joinToString(" ")
            message.visibility = if (card.message == null) GONE else VISIBLE
            message.text = card.message
            spinner.visibility = if (card.loading) VISIBLE else GONE
            buttons.removeAllViews()
            card.actions.forEachIndexed { index, action ->
                buttons.addView(button(action), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = if (index == 0) 0 else dp(8) })
            }
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserTouch?.invoke()
            return super.dispatchTouchEvent(event)
        }

        // Back cancels while connecting or showing the code; the consent and approval cards need an answer.
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP && (state is SessionState.Connecting || state is SessionState.WaitingForAgent)) onCancel?.invoke()
            return true
        }

        private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        private fun button(action: Action) = Button(context).apply {
            text = action.title
            isAllCaps = false
            stateListAnimator = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            val tint = configuration.tint
            val (fill, ink) = when (action.style) {
                Style.FILLED -> tint to Color.WHITE
                Style.TINTED -> Color.argb(38, Color.red(tint), Color.green(tint), Color.blue(tint)) to tint
                Style.PLAIN -> Color.TRANSPARENT to tint
                Style.DESTRUCTIVE -> Color.TRANSPARENT to Color.rgb(255, 59, 48)
            }
            setTextColor(ink)
            val shape = GradientDrawable().apply { cornerRadius = dp(12f); setColor(fill) }
            val mask = GradientDrawable().apply { cornerRadius = dp(12f); setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), shape, mask)
            setOnClickListener { action.run() }
        }
    }

    private fun showBanner(host: Activity, active: SessionState.Active?) {
        if (active == null) {
            banner?.let(::remove)
            banner = null
            return
        }
        val view = banner as? BannerView ?: run {
            val created = BannerView(host)
            val params = params(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val (x, y) = bannerOffset ?: (0 to topInset(host) + created.dp(12))
                this.x = x
                this.y = y
            }
            if (!add(host, created, params)) return
            banner = created
            created
        }
        view.show(active.mode)
    }

    @Suppress("DEPRECATION")
    private fun topInset(host: Activity) = host.window.decorView.rootWindowInsets?.systemWindowInsetTop ?: 0

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class BannerView(context: Context) : LinearLayout(context) {

        private val status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        private val viewOnly = pill(context.getString(R.string.remotesession_view_only), Color.WHITE) { onViewOnly?.invoke() }
        private var downX = 0f
        private var downY = 0f
        private var start = 0 to 0

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
            setPadding(dp(16), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(Color.argb(240, 26, 26, 26)) }
            val dot = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(255, 59, 48)) }
                ObjectAnimator.ofFloat(this, ALPHA, 1f, 0.3f).apply {
                    duration = 900
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            addView(dot, LayoutParams(dp(8), dp(8)))
            addView(status, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            addView(viewOnly, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply { marginStart = dp(10) })
            addView(pill(context.getString(R.string.remotesession_end), Color.rgb(255, 115, 107)) { onEnd?.invoke() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply { marginStart = dp(6) })
            setOnTouchListener { _, event -> drag(event) }
        }

        fun show(mode: SessionMode) {
            status.setText(if (mode == SessionMode.CONTROL) R.string.remotesession_controlling else R.string.remotesession_viewing)
            viewOnly.visibility = if (mode == SessionMode.CONTROL) VISIBLE else GONE
        }

        private fun pill(title: String, color: Int, action: () -> Unit) = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(Color.argb(46, Color.red(color), Color.green(color), Color.blue(color))) }
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
            setOnClickListener { action() }
        }

        /** The user can drag the banner out of the way. Touches elsewhere arrive as ACTION_OUTSIDE. */
        private fun drag(event: MotionEvent): Boolean {
            val params = layoutParams as? WindowManager.LayoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    onUserTouch?.invoke()
                    return false
                }
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    start = params.x to params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val screen = rootView.resources.displayMetrics
                    val maxX = ((screen.widthPixels - width) / 2 - dp(8)).coerceAtLeast(0)
                    params.x = (start.first + (event.rawX - downX).toInt()).coerceIn(-maxX, maxX)
                    params.y = (start.second + (event.rawY - downY).toInt()).coerceIn(dp(8), (screen.heightPixels - height - dp(8)).coerceAtLeast(dp(8)))
                    if (abs(event.rawX - downX) + abs(event.rawY - downY) > dp(4)) bannerOffset = params.x to params.y
                    runCatching { (context as Activity).windowManager.updateViewLayout(this, params) }
                }
            }
            return true
        }
    }

    @SuppressLint("ViewConstructor")
    private class TouchMarker(context: Context, private val screenX: Float, private val screenY: Float, private val color: Int) : View(context) {

        private val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = this@TouchMarker.color }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, Color.red(this@TouchMarker.color), Color.green(this@TouchMarker.color), Color.blue(this@TouchMarker.color)) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@TouchMarker.color; setShadowLayer(dp(4f), 0f, 0f, Color.argb(115, 0, 0, 0)) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE }
        private val easeOut = DecelerateInterpolator()
        private var elapsed = 0f

        /** Timed by the clock rather than an animator, so the marker still shows and fades with animations off. */
        fun play(done: () -> Unit) {
            val started = android.os.SystemClock.uptimeMillis()
            postOnAnimation(object : Runnable {
                override fun run() {
                    elapsed = (android.os.SystemClock.uptimeMillis() - started).toFloat()
                    invalidate()
                    if (elapsed < TOUCH_MARKER_MS) postOnAnimation(this) else done()
                }
            })
        }

        private val location = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(location)
            val x = screenX - location[0]
            val y = screenY - location[1]
            val fade = if (elapsed < 1100f) 1f else 1f - (elapsed - 1100f) / 350f
            val radius = dp(44f)
            for (delay in if (reduceMotion) listOf(0f) else listOf(0f, 200f)) {
                val t = elapsed - delay
                if (t < 0) continue
                val scale = if (reduceMotion) 0.6f else 0.25f + 0.75f * easeOut.getInterpolation(min(1f, t / 750f))
                val alpha = when {
                    reduceMotion -> 1f
                    t < 50f -> t / 50f
                    t < 200f -> 1f
                    else -> 1f - min(1f, (t - 200f) / 550f)
                } * fade
                fill.alpha = (46 * alpha).toInt()
                ring.alpha = (255 * alpha).toInt()
                canvas.drawCircle(x, y, radius * scale, fill)
                canvas.drawCircle(x, y, radius * scale, ring)
            }
            val pop = if (reduceMotion) 1f else 1f + 0.5f * (1f - easeOut.getInterpolation(min(1f, elapsed / 250f)))
            dot.alpha = (255 * fade).toInt()
            edge.alpha = (255 * fade).toInt()
            canvas.drawCircle(x, y, dp(13f) * pop, dot)
            canvas.drawCircle(x, y, dp(13f) * pop, edge)
        }

    }
}

private const val TOUCH_MARKER_MS = 1450L

private fun View.dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
private fun View.dp(value: Int) = dp(value.toFloat()).toInt()
