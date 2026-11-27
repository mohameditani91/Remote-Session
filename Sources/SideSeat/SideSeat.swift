import UIKit

/// Lets a support agent see, and with the user's consent control, this app from a browser.
public final class SideSeat {

    public struct Configuration {
        /// The relay's device endpoint, for example `ws://localhost:8080/device`. Add auth headers or query items here.
        public var request: URLRequest
        /// Replaces the WebSocket transport, for example to run over your own backend.
        public var transport: SideSeatTransport?
        public var allowsControl = true
        public var framesPerSecond: Double = 2
        public var scale: CGFloat = 1
        public var jpegQuality: CGFloat = 0.7
        /// Frame rate halves while a capture takes longer than this on the main thread.
        public var mainThreadBudget: TimeInterval = 0.05
        /// Returns true for views to paint black. Secure text fields and `isSideSeatMasked` views are always masked.
        public var maskRule: ((UIView) -> Bool)?
        /// Sees every agent input first. Return a result to handle or block it, or nil to let SideSeat perform it.
        public var inputInterceptor: ((RemoteInput) -> InputResult?)?
        /// Replaces the built-in consent card. Call the completion with the granted mode, or nil to deny.
        public var consentHandler: ((Agent, @escaping (SessionMode?) -> Void) -> Void)?
        public var strings = SideSeatStrings()
        public var tint: UIColor = .systemBlue
        /// Rings each agent tap on the user's screen so they can follow along. Set nil to hide it.
        public var tapIndicatorColor: UIColor? = UIColor(red: 1, green: 0.42, blue: 0, alpha: 1)

        public init(request: URLRequest) {
            self.request = request
        }

        public init(relay url: URL) {
            self.init(request: URLRequest(url: url))
        }
    }

    public static let shared = SideSeat()

    public var onStateChange: ((SessionState) -> Void)?
    /// Reports every agent input with its result and what it reached, for an audit log.
    public var onInput: ((InputEvent) -> Void)?
    public var state: SessionState? { session?.state }
    public var isRunning: Bool { session != nil }

    /// True while the agent has control and gave the last input that did something, with no touch from the user since.
    public var isAgentAction: Bool {
        approval.isAgentAction(inControl: session?.state.isControlling == true, at: ProcessInfo.processInfo.systemUptime)
    }

    /// Asks the user to allow `action`, such as "Delete the task", when the agent started it, and blocks agent input until they answer.
    /// Calls back with true right away when the user started it.
    public func confirmAgentAction(_ action: String, completion: @escaping (Bool) -> Void) {
        guard isAgentAction else { return completion(true) }
        guard approval.ask(action, completion: completion) else { return }
        controller?.askApproval(action)
        session?.sendApproval(action: action, allowed: nil)
    }

    private var session: Session?
    private weak var controller: OverlayViewController?
    private var approval = AgentApproval()
    private var streamer: Streamer?
    private var overlay: OverlayWindow?
    private var keyboard: KeyboardFrame?

    public init() {}

    public func start(in scene: UIWindowScene, configuration: Configuration) {
        guard session == nil else { return }

        let session = Session(transport: configuration.transport ?? WebSocketTransport(request: configuration.request), allowsControl: configuration.allowsControl)
        let keyboard = KeyboardFrame()
        let keyboardRect = { [weak scene, weak keyboard] () -> CGRect? in
            guard let scene else { return nil }
            return keyboard?.rect(in: scene)
        }
        let dispatcher = InputDispatcher(
            windows: { [weak scene] in scene?.windows ?? [] },
            bounds: { [weak scene] in scene?.coordinateSpace.bounds ?? .zero },
            keyboard: keyboardRect,
            maskRule: configuration.maskRule
        )
        // Described before it runs: a tap can change the screen.
        var described: (target: String?, screen: String?)?
        let describe = { (input: RemoteInput) in (target: dispatcher.describe(input), screen: dispatcher.screenName()) }
        session.perform = { [weak self] input in
            guard let self, !self.approval.isPending else { return .blocked("waitingForUser") }
            described = describe(input)
            // Counted before it runs, so an approval the input triggers sees it as the agent's.
            let earlier = self.approval.agentInput(at: ProcessInfo.processInfo.systemUptime)
            let result = configuration.inputInterceptor?(input) ?? dispatcher.perform(input)
            if result.outcome != .performed { self.approval.agentInputDidNothing(restoring: earlier) }
            return result
        }

        let controller = OverlayViewController(strings: configuration.strings, tint: configuration.tint, allowsControl: configuration.allowsControl, showsConsent: configuration.consentHandler == nil)
        controller.onCancel = { [weak self] in self?.end() }
        controller.onEnd = { [weak self] in self?.end() }
        controller.onAllow = { [weak session] in session?.grant($0) }
        controller.onDeny = { [weak session] in session?.deny() }
        controller.onViewOnly = { [weak session] in session?.switchToViewOnly() }
        controller.onApprove = { [weak self] in self?.answerApproval($0) }

        let window = OverlayWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        window.rootViewController = controller
        window.isHidden = false

        let streamer = Streamer(scene: scene, configuration: configuration, session: session, keyboard: keyboardRect)
        window.onTouch = { [weak self, weak streamer] in
            self?.approval.userInput()
            streamer?.captureSoon(after: Streamer.userTouchCaptureDelay)
        }
        session.onInput = { [weak self, weak controller, weak streamer] input, result in
            if case .tap(let x, let y) = input, let color = configuration.tapIndicatorColor {
                controller?.showTouch(at: dispatcher.point(x, y), color: color)
            }
            if result.outcome == .performed { streamer?.captureSoon(after: Streamer.inputCaptureDelay) }
            let (target, screen) = described ?? describe(input)
            described = nil
            self?.onInput?(InputEvent(input: input, result: result, target: target, screen: screen))
        }
        session.onStateChange = { [weak self, weak session, weak controller, weak streamer] state in
            if !state.isControlling { self?.answerApproval(false) }
            controller?.render(state)
            if case .active = state { streamer?.restart() }
            if case .awaitingConsent(_, let agent) = state, let ask = configuration.consentHandler {
                ask(agent) { mode in
                    DispatchQueue.main.async {
                        if let mode { session?.grant(mode) } else { session?.deny() }
                    }
                }
            }
            if case .ended = state { self?.tearDown() }
            self?.onStateChange?(state)
        }

        self.session = session
        self.streamer = streamer
        self.overlay = window
        self.controller = controller
        self.keyboard = keyboard
        controller.render(.connecting)
        streamer.start()
        session.start()
    }

    public func end() {
        session?.end()
    }

    private func answerApproval(_ allowed: Bool) {
        guard let action = approval.action else { return }
        let answers = approval.answer()
        controller?.askApproval(nil)
        session?.sendApproval(action: action, allowed: allowed)
        answers.forEach { $0(allowed) }
    }

    private func tearDown() {
        approval = AgentApproval()
        streamer?.stop()
        overlay?.isHidden = true
        overlay = nil
        keyboard = nil
        streamer = nil
        session = nil
    }
}

/// Decides whether an action the app is about to take came from the agent, and holds the user's pending answer.
struct AgentApproval {

    /// How long the agent's last input counts. Long enough to outlast a slow request before the app asks; the user's own touch ends it.
    static let window: TimeInterval = 90

    private(set) var action: String?
    private var answers: [(Bool) -> Void] = []
    private var lastAgentInputAt: TimeInterval?

    var isPending: Bool { action != nil }

    @discardableResult
    mutating func agentInput(at time: TimeInterval) -> TimeInterval? {
        defer { lastAgentInputAt = time }
        return lastAgentInputAt
    }

    mutating func agentInputDidNothing(restoring earlier: TimeInterval?) {
        lastAgentInputAt = earlier
    }

    mutating func userInput() {
        lastAgentInputAt = nil
    }

    func isAgentAction(inControl: Bool, at time: TimeInterval) -> Bool {
        guard inControl, let lastAgentInputAt else { return false }
        return time - lastAgentInputAt < Self.window
    }

    /// Returns true when the card should go up; later asks share the open card's answer.
    mutating func ask(_ action: String, completion: @escaping (Bool) -> Void) -> Bool {
        answers.append(completion)
        guard self.action == nil else { return false }
        self.action = action
        return true
    }

    mutating func answer() -> [(Bool) -> Void] {
        defer {
            action = nil
            answers = []
            lastAgentInputAt = nil
        }
        return answers
    }
}

private extension SessionState {
    var isControlling: Bool {
        if case .active(_, _, .control) = self { return true }
        return false
    }
}
