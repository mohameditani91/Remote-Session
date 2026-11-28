package dev.remotesession

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inspector.WindowInspector
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The resumed activity's window. Agent coordinates are fractions of it, and frames show it. */
internal class Scene(val activity: Activity) {
    val root: View = activity.window.decorView
    val width get() = root.width
    val height get() = root.height
    val density get() = root.resources.displayMetrics.density
    val isDark get() = root.context.isDark

    fun origin(): IntArray = IntArray(2).also(root::getLocationOnScreen)
}

/** One of the app's windows, placed in scene pixels. */
internal class AppWindow(val root: View, val left: Int, val top: Int) {
    private val flags get() = (root.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
    val width get() = root.width
    val height get() = root.height
    val isTouchable get() = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0

    /** Gets touches outside its bounds too, like a dialog that closes on an outside tap. */
    val isTouchModal get() = flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0

    fun contains(x: Float, y: Float) = x >= left && y >= top && x < left + width && y < top + height
}

/** Follows the resumed activity and lists the app's windows: the activity, dialogs, popups and menus. */
internal class AppWindows(private val application: Application, initial: Activity) : Application.ActivityLifecycleCallbacks {

    var onResumed: ((Activity) -> Unit)? = null
    var onDestroying: ((Activity) -> Unit)? = null

    /** RemoteSession's own windows, never captured or driven. */
    val own: MutableSet<View> = mutableSetOf()

    /** Set when any app window has drawn since the last frame. */
    var dirty = true

    private var resumed = WeakReference(initial)
    private val watched = WeakHashMap<View, ViewTreeObserver.OnDrawListener>()

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    val activity: Activity? get() = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun scene(): Scene? = activity?.let(::Scene)?.takeIf { it.width > 0 && it.height > 0 && it.root.isAttachedToWindow }

    /** The app's visible windows, bottom to top. */
    fun list(scene: Scene): List<AppWindow> {
        val origin = scene.origin()
        val display = scene.root.display?.displayId
        return WindowInspector.getGlobalWindowViews().filter { root ->
            root !in own && root.isAttachedToWindow && root.visibility == View.VISIBLE && root.windowVisibility == View.VISIBLE &&
                root.width > 0 && root.height > 0 && root.display?.displayId == display
        }.map { root ->
            watch(root)
            val location = IntArray(2).also(root::getLocationOnScreen)
            AppWindow(root, location[0] - origin[0], location[1] - origin[1])
        }
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        for ((root, listener) in watched) {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(listener)
        }
        watched.clear()
    }

    private fun watch(root: View) {
        if (root in watched) return
        val listener = ViewTreeObserver.OnDrawListener { dirty = true }
        root.viewTreeObserver.addOnDrawListener(listener)
        watched[root] = listener
        dirty = true
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        dirty = true
        onResumed?.invoke(activity)
    }

    override fun onActivityPreDestroyed(activity: Activity) {
        onDestroying?.invoke(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

/** `Configuration.isNightModeActive` is API 30. */
internal val Context.isDark get() =
    resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
