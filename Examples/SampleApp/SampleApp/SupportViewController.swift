import UIKit
import SideSeat

final class SupportViewController: UIViewController {

    private let relayField = UITextField()
    private let videoSwitch = UISwitch()
    private let status = UILabel()
    private let button = UIButton(configuration: .filled())

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Support"
        view.backgroundColor = .systemGroupedBackground

        let intro = UILabel()
        intro.text = "Start a session, then read the code to someone who opened the relay's console in a browser."
        intro.numberOfLines = 0
        intro.textColor = .secondaryLabel

        relayField.text = Support.relay
        relayField.borderStyle = .roundedRect
        relayField.autocapitalizationType = .none
        relayField.autocorrectionType = .no
        relayField.keyboardType = .URL
        relayField.addAction(UIAction { [weak self] _ in Support.relay = self?.relayField.text ?? "" }, for: .editingChanged)

        let videoLabel = UILabel()
        videoLabel.text = "Video"
        videoSwitch.isOn = Support.video
        videoSwitch.accessibilityLabel = "Video"
        videoSwitch.addAction(UIAction { [weak self] _ in Support.video = self?.videoSwitch.isOn ?? true }, for: .valueChanged)
        let videoRow = UIStackView(arrangedSubviews: [videoLabel, videoSwitch])
        videoRow.distribution = .equalSpacing

        let videoNote = UILabel()
        videoNote.text = "On: H.264 video when the agent's browser can decode it. Off: JPEG stills. Applies to the next session."
        videoNote.font = .preferredFont(forTextStyle: .footnote)
        videoNote.textColor = .secondaryLabel
        videoNote.numberOfLines = 0

        status.textColor = .secondaryLabel
        status.numberOfLines = 0

        button.addAction(UIAction { [weak self] _ in self?.toggle() }, for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [intro, relayField, videoRow, videoNote, button, status])
        stack.axis = .vertical
        stack.spacing = 16
        stack.setCustomSpacing(6, after: videoRow)
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            stack.leadingAnchor.constraint(equalTo: view.readableContentGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.readableContentGuide.trailingAnchor)
        ])

        SideSeat.shared.onStateChange = { [weak self] in self?.refresh($0) }
        refresh(SideSeat.shared.state)
    }

    private func toggle() {
        if SideSeat.shared.isRunning {
            SideSeat.shared.end()
        } else if let scene = view.window?.windowScene {
            Support.start(in: scene)
            refresh(SideSeat.shared.state)
        }
    }

    private func refresh(_ state: SessionState?) {
        button.configuration?.title = SideSeat.shared.isRunning ? "End support session" : "Start support session"
        relayField.isEnabled = !SideSeat.shared.isRunning
        videoSwitch.isEnabled = !SideSeat.shared.isRunning
        switch state {
        case .waitingForAgent(let code)?: status.text = "Code \(code). Waiting for an agent."
        case .awaitingConsent(_, let agent)?: status.text = "\(agent.name) is asking to join."
        case .active(_, let agent, let mode)?: status.text = "\(agent.name) is \(mode == .control ? "controlling" : "viewing")."
        case .ended(let reason)?: status.text = "Ended (\(reason.rawValue))."
        case .connecting?: status.text = "Connecting…"
        case nil: status.text = nil
        }
    }
}
