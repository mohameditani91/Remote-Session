import UIKit
import RemoteSession

enum Support {

    static let defaultRelay = "ws://localhost:8080/device"

    static var relay: String {
        get { UserDefaults.standard.string(forKey: "RemoteSessionRelay") ?? defaultRelay }
        set { UserDefaults.standard.set(newValue, forKey: "RemoteSessionRelay") }
    }

    /// On: H.264 video when the agent's browser can decode it. Off: JPEG stills.
    static var video: Bool {
        get { UserDefaults.standard.object(forKey: "RemoteSessionVideo") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "RemoteSessionVideo") }
    }

    static func start(in scene: UIWindowScene) {
        guard let url = URL(string: relay) else { return }
        var configuration = RemoteSession.Configuration(relay: url)
        configuration.tint = .systemIndigo
        configuration.video = video
        configuration.maskRule = MaskRules.screens([RecoveryKeyViewController.self])
        if let mode = UserDefaults.standard.string(forKey: "RemoteSessionAutoConsent").flatMap(SessionMode.init(rawValue:)) {
            configuration.consentHandler = { _, decide in decide(mode) }
        }
        RemoteSession.shared.start(in: scene, configuration: configuration)
    }

    static func startIfRequested(in scene: UIWindowScene) {
        guard UserDefaults.standard.bool(forKey: "RemoteSessionAutoStart") else { return }
        DispatchQueue.main.async { start(in: scene) }
    }
}
