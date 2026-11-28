package dev.remotesession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTransport : RemoteSessionTransport {
    override var onText: ((String) -> Unit)? = null
    override var onClose: ((Throwable?) -> Unit)? = null
    val sent = mutableListOf<String>()
    var connected = false
    var closed = false

    override fun connect() { connected = true }
    override fun send(text: String) { sent += text }
    override fun send(data: ByteArray) {}
    override fun close() { closed = true }

    fun deliver(json: String) = onText?.invoke(json)
    val lastSent get() = sent.lastOrNull()
}

class SessionTests {

    private val sara = Agent("a1", "Sara")

    private fun activeSession(mode: SessionMode, allowsControl: Boolean = true): Pair<Session, FakeTransport> {
        val transport = FakeTransport()
        val session = Session(transport, allowsControl)
        session.start()
        transport.deliver("""{"type":"session","code":"123456"}""")
        transport.deliver("""{"type":"agentJoined","agentId":"a1","agentName":"Sara"}""")
        session.grant(mode)
        return session to transport
    }

    @Test fun walksFromCodeToConsentToActive() {
        val transport = FakeTransport()
        val session = Session(transport, allowsControl = true)
        val states = mutableListOf<SessionState>()
        session.onStateChange = { states += it }

        session.start()
        assertTrue(transport.connected)
        transport.deliver("""{"type":"session","code":"123456"}""")
        transport.deliver("""{"type":"agentJoined","agentId":"a1","agentName":"Sara"}""")
        session.grant(SessionMode.CONTROL)

        assertEquals(
            listOf(
                SessionState.WaitingForAgent("123456"),
                SessionState.AwaitingConsent("123456", sara),
                SessionState.Active("123456", sara, SessionMode.CONTROL),
            ),
            states,
        )
        assertEquals("""{"mode":"control","type":"consent"}""", transport.lastSent)
    }

    @Test fun controlRunsInputsAndReportsTheResult() {
        val (session, transport) = activeSession(SessionMode.CONTROL)
        val performed = mutableListOf<RemoteInput>()
        session.perform = { performed += it; InputResult.nothing("noTarget") }

        transport.deliver("""{"type":"input","id":"i1","action":"tap","x":0.5,"y":0.25}""")

        assertEquals(listOf(RemoteInput.Tap(0.5, 0.25)), performed)
        assertEquals("""{"id":"i1","outcome":"nothing","reason":"noTarget","type":"result"}""", transport.lastSent)
    }

    @Test fun everyInputIsReportedWithItsResult() {
        val (session, transport) = activeSession(SessionMode.CONTROL)
        val reported = mutableListOf<InputResult>()
        session.perform = { InputResult.performed }
        session.onInput = { _, result -> reported += result }

        transport.deliver("""{"type":"input","id":"i1","action":"back"}""")
        session.switchToViewOnly()
        transport.deliver("""{"type":"input","id":"i2","action":"back"}""")

        assertEquals(listOf(InputResult.performed, InputResult.blocked("viewOnly")), reported)
    }

    @Test fun inputsFromANewerConsoleGetAnAnswer() {
        val (_, transport) = activeSession(SessionMode.CONTROL)
        transport.deliver("""{"type":"input","id":"i9","action":"pinch"}""")
        assertEquals("""{"id":"i9","outcome":"nothing","reason":"unsupported","type":"result"}""", transport.lastSent)
    }

    @Test fun viewOnlyBlocksInputs() {
        val (session, transport) = activeSession(SessionMode.VIEW)
        session.perform = { error("must not run") }
        transport.deliver("""{"type":"input","id":"i1","action":"back"}""")
        assertEquals("""{"id":"i1","outcome":"blocked","reason":"viewOnly","type":"result"}""", transport.lastSent)
    }

    @Test fun inputsBeforeConsentAreBlocked() {
        val transport = FakeTransport()
        val session = Session(transport, allowsControl = true)
        session.start()
        transport.deliver("""{"type":"session","code":"123456"}""")
        transport.deliver("""{"type":"input","id":"i1","action":"text","text":"hi"}""")
        assertEquals("""{"id":"i1","outcome":"blocked","reason":"noConsent","type":"result"}""", transport.lastSent)
    }

    @Test fun controlIsDowngradedWhenTheAppDisallowsIt() {
        val (session, transport) = activeSession(SessionMode.CONTROL, allowsControl = false)
        assertEquals(SessionState.Active("123456", sara, SessionMode.VIEW), session.state)
        assertEquals("""{"mode":"view","type":"consent"}""", transport.lastSent)
    }

    @Test fun switchingToViewOnlyTellsTheAgent() {
        val (session, transport) = activeSession(SessionMode.CONTROL)
        session.switchToViewOnly()
        assertEquals(SessionState.Active("123456", sara, SessionMode.VIEW), session.state)
        assertEquals("""{"mode":"view","type":"mode"}""", transport.lastSent)
    }

    @Test fun denyingSendsTheAgentAwayAndKeepsTheCode() {
        val transport = FakeTransport()
        val session = Session(transport, allowsControl = true)
        session.start()
        transport.deliver("""{"type":"session","code":"123456"}""")
        transport.deliver("""{"type":"agentJoined","agentId":"a1","agentName":"Sara"}""")
        session.deny()
        assertEquals(SessionState.WaitingForAgent("123456"), session.state)
        assertEquals("""{"type":"denied"}""", transport.lastSent)
    }

    @Test fun theAgentLeavingReturnsToTheCode() {
        val (session, transport) = activeSession(SessionMode.CONTROL)
        transport.deliver("""{"type":"agentLeft"}""")
        assertEquals(SessionState.WaitingForAgent("123456"), session.state)
    }

    @Test fun endingNotifiesTheRelayOnce() {
        val (session, transport) = activeSession(SessionMode.CONTROL)
        session.end()
        session.end()
        assertEquals(SessionState.Ended(EndReason.USER_ENDED), session.state)
        assertEquals(1, transport.sent.count { it.contains(""""type":"end"""") })
        assertTrue(transport.closed)
    }

    @Test fun theAgentEndingOrTheSocketDroppingEndsTheSession() {
        val (agentEnded, first) = activeSession(SessionMode.VIEW)
        first.deliver("""{"type":"end","reason":"agentEnded"}""")
        assertEquals(SessionState.Ended(EndReason.AGENT_ENDED), agentEnded.state)

        val (dropped, second) = activeSession(SessionMode.VIEW)
        second.onClose?.invoke(null)
        assertEquals(SessionState.Ended(EndReason.DISCONNECTED), dropped.state)
    }

    @Test fun malformedMessagesAreIgnored() {
        val transport = FakeTransport()
        val session = Session(transport, allowsControl = true)
        session.start()
        transport.deliver("not json")
        transport.deliver("""{"type":"session"}""")
        transport.deliver("""{"type":"input","id":"i1","action":"tap"}""")
        assertEquals(SessionState.Connecting, session.state)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun decodesEveryInput() {
        fun parse(json: String) = IncomingMessage.parse(json)
        assertEquals(IncomingMessage.Input("1", RemoteInput.Scroll(0.1, 0.2, 0.0, 0.3)), parse("""{"type":"input","id":"1","action":"scroll","x":0.1,"y":0.2,"dx":0,"dy":0.3}"""))
        assertEquals(IncomingMessage.Input("2", RemoteInput.Key(RemoteKey.RETURN)), parse("""{"type":"input","id":"2","action":"key","key":"return"}"""))
        assertEquals(IncomingMessage.Input("3", RemoteInput.Key(RemoteKey.BACKSPACE)), parse("""{"type":"input","id":"3","action":"key","key":"backspace"}"""))
        assertEquals(IncomingMessage.Input("4", RemoteInput.Text("ab")), parse("""{"type":"input","id":"4","action":"text","text":"ab"}"""))
        assertEquals(IncomingMessage.Input("5", RemoteInput.HideKeyboard), parse("""{"type":"input","id":"5","action":"hideKeyboard"}"""))
        assertEquals(IncomingMessage.UnsupportedInput("6"), parse("""{"type":"input","id":"6","action":"swipe"}"""))
        assertNull(parse("""{"type":"input","action":"swipe"}"""))
    }

    @Test fun agentsSayWhichCodecsTheirBrowserDecodes() {
        assertEquals(IncomingMessage.AgentJoined(Agent("a", "Sara", listOf("h264"))), IncomingMessage.parse("""{"type":"agentJoined","agentId":"a","agentName":"Sara","video":["h264"]}"""))
        assertEquals(IncomingMessage.AgentJoined(Agent("a", "Sara")), IncomingMessage.parse("""{"type":"agentJoined","agentId":"a","agentName":"Sara"}"""))
        assertEquals(IncomingMessage.RequestKeyframe, IncomingMessage.parse("""{"type":"requestKeyframe"}"""))
    }

    @Test fun screenSizesAreWholeNumbersWhenTheyCanBe() {
        assertEquals("""{"height":844,"type":"screen","width":390.5}""", OutgoingMessage.Screen(390.5, 844.0).json)
    }
}

class ApprovalTests {

    @Test fun onlyRecentAgentInputInControlCountsAsAnAgentAction() {
        val approval = AgentApproval()
        assertFalse(approval.isAgentAction(inControl = true, at = 100.0))

        approval.agentInput(at = 100.0)
        assertTrue(approval.isAgentAction(inControl = true, at = 100 + AgentApproval.WINDOW - 0.1))
        assertFalse(approval.isAgentAction(inControl = true, at = 100 + AgentApproval.WINDOW))
        assertFalse(approval.isAgentAction(inControl = false, at = 101.0))
    }

    @Test fun anAgentInputThatDidNothingDoesNotCount() {
        val approval = AgentApproval()
        approval.agentInputDidNothing(restoring = approval.agentInput(at = 100.0))
        assertFalse(approval.isAgentAction(inControl = true, at = 101.0))

        approval.agentInput(at = 200.0)
        approval.agentInputDidNothing(restoring = approval.agentInput(at = 250.0))
        assertTrue(approval.isAgentAction(inControl = true, at = 200 + AgentApproval.WINDOW - 0.1))
        assertFalse(approval.isAgentAction(inControl = true, at = 200 + AgentApproval.WINDOW))
    }

    @Test fun actionsAskedWhileACardIsShowingShareItsAnswer() {
        val approval = AgentApproval()
        val answers = mutableMapOf<String, Boolean>()
        assertTrue(approval.ask("Delete the task") { answers["first"] = it })
        assertFalse(approval.ask("Delete the task") { answers["second"] = it })
        assertEquals("Delete the task", approval.action)

        approval.answer().forEach { it(false) }
        assertEquals(mapOf("first" to false, "second" to false), answers)
        assertFalse(approval.isPending)
    }

    @Test fun theUsersOwnTouchEndsTheAgentWindow() {
        val approval = AgentApproval()
        approval.agentInput(at = 100.0)
        approval.userInput()
        assertFalse(approval.isAgentAction(inControl = true, at = 101.0))
    }

    @Test fun anAnswerUsesUpTheAgentsInput() {
        val approval = AgentApproval()
        approval.agentInput(at = 100.0)
        approval.ask("Pay") {}
        approval.answer().forEach { it(true) }
        assertFalse(approval.isAgentAction(inControl = true, at = 101.0))
    }

    @Test fun theAgentSeesTheQuestionAndTheAnswer() {
        assertEquals("""{"action":"Pay","type":"approval"}""", OutgoingMessage.Approval("Pay", null).json)
        assertEquals("""{"action":"Pay","allowed":false,"type":"approval"}""", OutgoingMessage.Approval("Pay", false).json)
    }
}
