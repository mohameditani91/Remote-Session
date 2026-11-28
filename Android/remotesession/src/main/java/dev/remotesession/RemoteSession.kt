package dev.remotesession

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View

/** Lets a support agent see, and with the user's consent control, this app from a browser. Main thread only. */
object RemoteSession {

    class Configuration(
        /** The relay's device endpoint, for example `ws://10.0.2.2:8080/device`. Put auth in [headers] or the query. */
        val relay: String,
    ) {
        var headers: Map<String, String> = emptyMap()
        /** Replaces the WebSocket transport, for example to run over your own backend. */
        var transport: RemoteSessionTransport? = null
        var allowsControl = true
        /** Streams H.264 video of the app's own windows when the agent's browser can decode it, and JPEG stills otherwise. */
        var video = false
        /** JPEG stills only. Video runs at up to 30 fps. */
        var framesPerSecond = 2.0
        /** Frame pixels per dp. */
        var scale = 1f
        /** 0–100. */
        var jpegQuality = 70
        /** Seconds. The still frame rate halves while drawing a frame takes longer than this on the main thread. */
        var mainThreadBudget = 0.05
        /** Returns true for views to mask. Password fields and `isRemoteSessionMasked` views are always masked. */
        var maskRule: ((View) -> Boolean)? = null
        /** Sees every agent input first. Return a result to handle or block it, or null to let RemoteSession perform it. */
        var inputInterceptor: ((RemoteInput) -> InputResult?)? = null
        /** Replaces the built-in consent card. Call the callback with the granted mode, or null to deny. */
        var consentHandler: ((Agent, (SessionMode?) -> Unit) -> Unit)? = null
        /** ARGB color of the built-in cards' buttons and code. */
        var tint: Int = 0xFF007AFF.toInt()
        /** Marks each agent tap on the user's screen with a dot and ripples. Set null to hide it. */
        var tapIndicatorColor: Int? = 0xFFFF6B00.toInt()
    }

    var onStateChange: ((SessionState) -> Unit)? = null
    /** Reports every agent input with its result and what it reached, for an audit log. */
    var onInput: ((InputEvent) -> Unit)? = null
    val state: SessionState? get() = session?.state
    val isRunning get() = session != null

    /** True while the agent has control and gave the last input that did something, with no touch from the user since. */
    val isAgentAction: Boolean get() = approval.isAgentAction(session?.state?.isControlling == true, now())

    /**
     * Asks the user to allow [action], such as "Delete the task", when the agent started it, and blocks agent input
     * until they answer. Calls back with true right away when the user started it.
     */
    fun confirmAgentAction(action: String, completion: (Boolean) -> Unit) {
        if (!isAgentAction) return completion(true)
        if (!approval.ask(action, completion)) return
        session?.let { overlay?.render(it.state, action) }
        session?.sendApproval(action, null)
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var windows: AppWindows? = null
    private var overlay: Overlay? = null
    private var approval = AgentApproval()
    private var streamer: FrameStreamer? = null
    private var makeStreamer: ((video: Boolean) -> FrameStreamer)? = null

    private fun now() = SystemClock.uptimeMillis() / 1000.0

    fun start(activity: Activity, configuration: Configuration) {
        if (session != null) return
        val windows = AppWindows(activity.application, activity)
        val session = Session(configuration.transport ?: WebSocketTransport(configuration.relay, configuration.headers), configuration.allowsControl)
        val dispatcher = InputDispatcher(windows, configuration.maskRule)

        // Described before it runs: a tap can change the screen.
        var described: Pair<String?, String?>? = null
        session.perform = perform@{ input ->
            if (approval.isPending) return@perform InputResult.blocked("waitingForUser")
            described = dispatcher.describe(input) to dispatcher.screenName()
            // Counted before it runs, so an approval the input triggers sees it as the agent's.
            val earlier = approval.agentInput(now())
            val result = configuration.inputInterceptor?.invoke(input) ?: dispatcher.perform(input)
            if (result.outcome != InputResult.Outcome.PERFORMED) approval.agentInputDidNothing(earlier)
            result
        }

        val overlay = Overlay(windows, configuration).apply {
            onCancel = ::end
            onEnd = ::end
            onAllow = session::grant
            onDeny = session::deny
            onViewOnly = session::switchToViewOnly
            onApprove = ::answerApproval
            onUserTouch = {
                approval.userInput()
                streamer?.captureSoon(StillStreamer.USER_TOUCH_CAPTURE_DELAY_MS)
            }
        }
        windows.onResumed = overlay::attach
        windows.onDestroying = overlay::detach

        makeStreamer = { video ->
            if (video) VideoStreamer(windows, session, configuration).apply { onFallback = { startStreaming(video = false) } }
            else StillStreamer(windows, session, configuration)
        }

        session.onKeyframeRequest = { streamer?.requestKeyframe() }
        session.onInput = { input, result ->
            val color = configuration.tapIndicatorColor
            val scene = windows.scene()
            if (input is RemoteInput.Tap && color != null && scene != null) {
                val origin = scene.origin()
                overlay.showTouch(origin[0] + scene.width * input.x.coerceIn(0.0, 1.0).toFloat(), origin[1] + scene.height * input.y.coerceIn(0.0, 1.0).toFloat(), color)
            }
            if (result.outcome == InputResult.Outcome.PERFORMED) streamer?.captureSoon(StillStreamer.INPUT_CAPTURE_DELAY_MS)
            val (target, screen) = described ?: (dispatcher.describe(input) to dispatcher.screenName())
            described = null
            onInput?.invoke(InputEvent(input, result, target, screen))
        }
        session.onStateChange = { state ->
            if (!state.isControlling) answerApproval(false)
            overlay.render(state, approval.action)
            if (state is SessionState.Active) {
                if (streamer == null) startStreaming(configuration.video && "h264" in state.agent.video)
            } else {
                stopStreaming()
            }
            if (state is SessionState.AwaitingConsent) {
                configuration.consentHandler?.invoke(state.agent) { mode ->
                    main.post { if (mode != null) session.grant(mode) else session.deny() }
                }
            }
            if (state is SessionState.Ended) tearDown()
            onStateChange?.invoke(state)
        }

        this.session = session
        this.windows = windows
        this.overlay = overlay
        overlay.attach(activity)
        overlay.render(SessionState.Connecting, null)
        session.start()
    }

    fun end() {
        session?.end()
    }

    private fun answerApproval(allowed: Boolean) {
        val action = approval.action ?: return
        val answers = approval.answer()
        session?.let { overlay?.render(it.state, null) }
        session?.sendApproval(action, allowed)
        answers.forEach { it(allowed) }
    }

    /** A new streamer per agent: the next agent's browser may not decode video. */
    private fun startStreaming(video: Boolean) {
        streamer?.stop()
        streamer = makeStreamer?.invoke(video)?.also { it.start() }
    }

    private fun stopStreaming() {
        streamer?.stop()
        streamer = null
    }

    private fun tearDown() {
        approval = AgentApproval()
        stopStreaming()
        makeStreamer = null
        overlay?.detach()
        overlay = null
        windows?.stop()
        windows = null
        session = null
    }
}
