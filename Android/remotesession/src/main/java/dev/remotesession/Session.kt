package dev.remotesession

sealed interface SessionState {
    data object Connecting : SessionState
    data class WaitingForAgent(val code: String) : SessionState
    data class AwaitingConsent(val code: String, val agent: Agent) : SessionState
    data class Active(val code: String, val agent: Agent, val mode: SessionMode) : SessionState
    data class Ended(val reason: EndReason) : SessionState
}

internal val SessionState.isControlling get() = this is SessionState.Active && mode == SessionMode.CONTROL

/** The protocol state machine. Main thread only. */
internal class Session(private val transport: RemoteSessionTransport, private val allowsControl: Boolean) {

    var onStateChange: ((SessionState) -> Unit)? = null
    var perform: ((RemoteInput) -> InputResult)? = null
    var onInput: ((RemoteInput, InputResult) -> Unit)? = null
    var onKeyframeRequest: (() -> Unit)? = null

    var state: SessionState = SessionState.Connecting
        private set(value) {
            if (value == field) return
            field = value
            onStateChange?.invoke(value)
        }

    val isStreaming get() = state is SessionState.Active
    val queuedBytes get() = transport.queuedBytes

    fun start() {
        transport.onText = ::receive
        transport.onClose = { finish(EndReason.DISCONNECTED, notify = false) }
        transport.connect()
    }

    fun receive(text: String) {
        val message = IncomingMessage.parse(text) ?: return
        val current = state
        when {
            message is IncomingMessage.Session && current is SessionState.Connecting ->
                state = SessionState.WaitingForAgent(message.code)
            message is IncomingMessage.AgentJoined && current is SessionState.WaitingForAgent ->
                state = SessionState.AwaitingConsent(current.code, message.agent)
            message is IncomingMessage.AgentLeft && current is SessionState.AwaitingConsent ->
                state = SessionState.WaitingForAgent(current.code)
            message is IncomingMessage.AgentLeft && current is SessionState.Active ->
                state = SessionState.WaitingForAgent(current.code)
            message is IncomingMessage.Input -> handled(
                message.id,
                message.input,
                when {
                    current.isControlling -> perform?.invoke(message.input) ?: InputResult.nothing("notAvailable")
                    current is SessionState.Active -> InputResult.blocked("viewOnly")
                    else -> InputResult.blocked("noConsent")
                },
            )
            message is IncomingMessage.RequestKeyframe && current is SessionState.Active -> onKeyframeRequest?.invoke()
            message is IncomingMessage.UnsupportedInput -> send(OutgoingMessage.Result(message.id, InputResult.nothing("unsupported")))
            message is IncomingMessage.End -> finish(message.reason, notify = false)
        }
    }

    fun grant(mode: SessionMode) {
        val current = state as? SessionState.AwaitingConsent ?: return
        val granted = if (allowsControl) mode else SessionMode.VIEW
        send(OutgoingMessage.Consent(granted))
        state = SessionState.Active(current.code, current.agent, granted)
    }

    fun deny() {
        val current = state as? SessionState.AwaitingConsent ?: return
        send(OutgoingMessage.Denied)
        state = SessionState.WaitingForAgent(current.code)
    }

    fun switchToViewOnly() {
        val current = state as? SessionState.Active ?: return
        if (current.mode != SessionMode.CONTROL) return
        send(OutgoingMessage.Mode(SessionMode.VIEW))
        state = current.copy(mode = SessionMode.VIEW)
    }

    fun end() = finish(EndReason.USER_ENDED, notify = true)

    fun sendApproval(action: String, allowed: Boolean?) = send(OutgoingMessage.Approval(action, allowed))

    fun sendScreen(width: Double, height: Double) = send(OutgoingMessage.Screen(width, height))

    fun sendFrame(data: ByteArray) = transport.send(data)

    private fun handled(id: String, input: RemoteInput, result: InputResult) {
        send(OutgoingMessage.Result(id, result))
        onInput?.invoke(input, result)
    }

    private fun send(message: OutgoingMessage) = transport.send(message.json)

    private fun finish(reason: EndReason, notify: Boolean) {
        if (state is SessionState.Ended) return
        if (notify) send(OutgoingMessage.End(reason))
        state = SessionState.Ended(reason)
        transport.close()
    }
}

/** Decides whether an action the app is about to take came from the agent, and holds the user's pending answer. */
internal class AgentApproval {

    companion object {
        /** How long the agent's last input counts, in seconds. Long enough to outlast a slow request; the user's own touch ends it. */
        const val WINDOW = 90.0
    }

    var action: String? = null
        private set
    private var answers = mutableListOf<(Boolean) -> Unit>()
    private var lastAgentInputAt: Double? = null

    val isPending get() = action != null

    fun agentInput(at: Double): Double? = lastAgentInputAt.also { lastAgentInputAt = at }

    fun agentInputDidNothing(restoring: Double?) {
        lastAgentInputAt = restoring
    }

    fun userInput() {
        lastAgentInputAt = null
    }

    fun isAgentAction(inControl: Boolean, at: Double): Boolean {
        val last = lastAgentInputAt ?: return false
        return inControl && at - last < WINDOW
    }

    /** Returns true when the card should go up; later asks share the open card's answer. */
    fun ask(action: String, completion: (Boolean) -> Unit): Boolean {
        answers += completion
        if (this.action != null) return false
        this.action = action
        return true
    }

    fun answer(): List<(Boolean) -> Unit> = answers.also {
        action = null
        answers = mutableListOf()
        lastAgentInputAt = null
    }
}
