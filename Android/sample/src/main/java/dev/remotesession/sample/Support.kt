package dev.remotesession.sample

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.remotesession.MaskRules
import dev.remotesession.RemoteSession
import dev.remotesession.SessionMode
import dev.remotesession.SessionState

object Support {

    /** The emulator reaches the Mac running the relay at 10.0.2.2. On a phone, use the Mac's IP. */
    const val DEFAULT_RELAY = "ws://10.0.2.2:8080/device"

    var state by mutableStateOf<SessionState?>(null)
        private set

    fun relay(context: Context): String = prefs(context).getString("relay", DEFAULT_RELAY) ?: DEFAULT_RELAY
    fun setRelay(context: Context, value: String) = prefs(context).edit().putString("relay", value).apply()

    /** On: H.264 video when the agent's browser can decode it. Off: JPEG stills. */
    fun video(context: Context) = prefs(context).getBoolean("video", true)
    fun setVideo(context: Context, value: Boolean) = prefs(context).edit().putBoolean("video", value).apply()

    fun start(activity: Activity, autoConsent: SessionMode? = null) {
        val configuration = RemoteSession.Configuration(relay(activity)).apply {
            tint = 0xFF5856D6.toInt()
            video = video(activity)
            maskRule = MaskRules.activities(RecoveryKeyActivity::class)
            if (autoConsent != null) consentHandler = { _, decide -> decide(autoConsent) }
        }
        RemoteSession.onStateChange = { state = it }
        RemoteSession.onInput = { Log.i("RemoteSession", "${it.input} on ${it.target ?: "nothing"} in ${it.screen}: ${it.result.outcome} ${it.result.reason ?: ""}") }
        RemoteSession.start(activity, configuration)
        state = RemoteSession.state
    }

    /**
     * For scripted runs:
     * `adb shell am start -n dev.remotesession.sample/.MainActivity --ez autoStart true --es autoConsent control`
     */
    fun startIfRequested(activity: Activity) {
        // Debug builds only: the activity is exported, so any app could otherwise start a pre-approved session.
        if (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!activity.intent.getBooleanExtra("autoStart", false)) return
        val mode = SessionMode.entries.firstOrNull { it.wire == activity.intent.getStringExtra("autoConsent") }
        activity.window.decorView.post { start(activity, mode) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("support", Context.MODE_PRIVATE)
}
