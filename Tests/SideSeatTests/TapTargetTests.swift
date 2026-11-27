import SwiftUI
import Testing
@testable import SideSeat

private final class Log {
    var events: [String] = []
}

private final class GestureHandler: NSObject {
    var calls: [(view: UIView?, location: CGPoint, state: UIGestureRecognizer.State)] = []

    @objc func tapped(_ recognizer: UITapGestureRecognizer) {
        calls.append((recognizer.view, recognizer.location(in: recognizer.view), recognizer.state))
    }
}

private struct Controls: View {
    let log: Log
    @State private var on = false

    var body: some View {
        VStack(spacing: 0) {
            Text("tap").frame(width: 300, height: 50).contentShape(Rectangle()).sideSeatTapGesture { log.events.append("tap") }
            Button("button") { log.events.append("button") }.sideSeatButton().frame(width: 300, height: 50)
            Toggle("plain", isOn: $on).frame(width: 300, height: 50)
            Toggle("hooked", isOn: Binding(get: { on }, set: { on = $0; log.events.append("toggle") }))
                .frame(width: 300, height: 50)
                .sideSeatTarget { on.toggle(); log.events.append("toggle") }
            List { Button("row") { log.events.append("row") }.sideSeatButton() }.frame(width: 300, height: 100)
            List {
                HStack { Text("Total"); Spacer(); Text("0.00") }
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                    .sideSeatTapGesture { log.events.append("total") }
                    .frame(minHeight: 80)
                HStack {
                    Button("a") { log.events.append("a") }.sideSeatButton()
                    Button("b") { log.events.append("b") }.sideSeatButton()
                    Spacer()
                }
            }
            .frame(width: 300, height: 260)
        }
        .frame(width: 300, height: 660, alignment: .top)
    }
}

private final class PlainSwiftTarget {
    @objc func fire() {}
}

private struct UIKitButton: UIViewRepresentable {
    let log: Log
    let plainSwiftTarget: Bool

    func makeCoordinator() -> PlainSwiftTarget { PlainSwiftTarget() }

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(configuration: .tinted(), primaryAction: UIAction(title: "Scan") { _ in log.events.append("scan") })
        if plainSwiftTarget { button.addTarget(context.coordinator, action: #selector(PlainSwiftTarget.fire), for: .touchUpInside) }
        button.setContentHuggingPriority(.required, for: .horizontal)
        return button
    }

    func updateUIView(_ uiView: UIButton, context: Context) {}
}

private struct FieldWithScanButton: View {
    let log: Log
    var plainSwiftTarget = false

    var body: some View {
        Form {
            HStack {
                Text("Address").frame(maxWidth: .infinity, alignment: .leading).sideSeatTapGesture { log.events.append("field") }
                UIKitButton(log: log, plainSwiftTarget: plainSwiftTarget).fixedSize()
            }
        }
    }
}

@MainActor
@Suite struct TapTargetTests {

    private func host<V: View>(_ view: V, size: CGSize = CGSize(width: 300, height: 400)) async throws -> UIWindow {
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        window.rootViewController = UIHostingController(rootView: view.ignoresSafeArea())
        window.isHidden = false
        window.layoutIfNeeded()
        try await Task.sleep(nanoseconds: 300_000_000)
        return window
    }

    private func tap(_ window: UIWindow, _ x: CGFloat, _ y: CGFloat) -> InputResult {
        InputDispatcher(windows: { [window] }, bounds: { window.bounds })
            .perform(.tap(x: Double(x / window.bounds.width), y: Double(y / window.bounds.height)))
    }

    private func cells(in view: UIView) -> [CGRect] {
        let own = (view as? UICollectionViewCell).map { [$0.convert($0.bounds, to: nil)] } ?? []
        return (own + view.subviews.flatMap(cells(in:))).sorted { $0.minY < $1.minY }
    }

    private func snapshot(_ window: UIWindow) -> Data? {
        UIGraphicsImageRenderer(bounds: window.bounds).image { context in
            window.layer.render(in: context.cgContext)
        }.pngData()
    }

    @Test func gestureRecognizerGetsItsViewLocationAndEndedState() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 1000, height: 500))
        window.isHidden = false
        let view = UIView(frame: CGRect(x: 100, y: 100, width: 200, height: 60))
        let handler = GestureHandler()
        view.addGestureRecognizer(SideSeatTapGestureRecognizer(target: handler, action: #selector(GestureHandler.tapped(_:))))
        window.addSubview(view)
        #expect(tap(window, 150, 120) == .performed)
        #expect(handler.calls.count == 1)
        #expect(handler.calls.first?.view === view)
        #expect(handler.calls.first?.location == CGPoint(x: 50, y: 20))
        #expect(handler.calls.first?.state == .ended)
    }

    @Test func disabledDoubleTapAndPlainRecognizersAreNotFired() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 1000, height: 500))
        window.isHidden = false
        let handler = GestureHandler()
        let disabled = SideSeatTapGestureRecognizer(target: handler, action: #selector(GestureHandler.tapped(_:)))
        disabled.isEnabled = false
        let doubleTap = SideSeatTapGestureRecognizer(target: handler, action: #selector(GestureHandler.tapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        let plain = UITapGestureRecognizer(target: handler, action: #selector(GestureHandler.tapped(_:)))
        for (index, recognizer) in [disabled, doubleTap, plain].enumerated() {
            let view = UIView(frame: CGRect(x: 0, y: CGFloat(index) * 100, width: 200, height: 60))
            view.addGestureRecognizer(recognizer)
            window.addSubview(view)
            #expect(tap(window, 50, CGFloat(index) * 100 + 20) == .nothing("noTarget"))
        }
        #expect(handler.calls.isEmpty)
    }

    @Test func removedTargetsAreNotFired() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 1000, height: 500))
        window.isHidden = false
        let handler = GestureHandler()
        let recognizer = SideSeatTapGestureRecognizer(target: handler, action: #selector(GestureHandler.tapped(_:)))
        recognizer.removeTarget(handler, action: nil)
        let view = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 60))
        view.addGestureRecognizer(recognizer)
        window.addSubview(view)
        #expect(tap(window, 50, 20) == .nothing("noTarget"))
        #expect(handler.calls.isEmpty)
    }

    @Test func swiftUIHooksFireAndUnhookedSwiftUIControlsSayNothing() async throws {
        let log = Log()
        let window = try await host(Controls(log: log), size: CGSize(width: 300, height: 660))
        #expect(tap(window, 150, 25) == .performed)
        #expect(tap(window, 150, 75) == .performed)
        #expect(tap(window, 270, 125) == .nothing("swiftUIControl"))
        #expect(tap(window, 270, 175) == .performed)
        #expect(tap(window, 150, 250) == .performed)
        let rows = cells(in: window)
        #expect(rows.count == 3)
        if rows.count == 3 {
            #expect(tap(window, rows[1].maxX - 6, rows[1].maxY - 4) == .performed)
            #expect(tap(window, rows[2].maxX - 6, rows[2].midY).outcome != .performed)
        }
        #expect(log.events == ["tap", "button", "toggle", "row", "total"])
    }

    @Test func uikitButtonInAListRowBeatsTheRowsOnlyTarget() async throws {
        let log = Log()
        let window = try await host(FieldWithScanButton(log: log))
        let button = try #require(buttons(in: window).first)
        let center = button.convert(CGPoint(x: button.bounds.midX, y: button.bounds.midY), to: window)
        #expect(tap(window, center.x, center.y) == .performed)
        #expect(log.events == ["scan"])
    }

    @Test func uikitButtonWithAPlainSwiftTargetIsTappedWithoutCrashing() async throws {
        let window = try await host(FieldWithScanButton(log: Log(), plainSwiftTarget: true))
        let button = try #require(buttons(in: window).first)
        let center = button.convert(CGPoint(x: button.bounds.midX, y: button.bounds.midY), to: window)
        #expect(tap(window, center.x, center.y) == .performed)
    }

    private func buttons(in view: UIView) -> [UIButton] {
        ((view as? UIButton).map { [$0] } ?? []) + view.subviews.flatMap(buttons(in:))
    }

    @Test func disabledSwiftUITargetsAreNotFired() async throws {
        let log = Log()
        let view = VStack(spacing: 0) {
            Button("off") { log.events.append("button") }.sideSeatButton().disabled(true).frame(width: 300, height: 50)
            Text("off").frame(width: 300, height: 50).contentShape(Rectangle()).sideSeatTapGesture { log.events.append("tap") }.disabled(true)
        }
        .frame(width: 300, height: 100)
        let window = try await host(view, size: CGSize(width: 300, height: 100))
        #expect(tap(window, 150, 25).outcome != .performed)
        #expect(tap(window, 150, 75).outcome != .performed)
        #expect(log.events.isEmpty)
    }

    @Test func sideSeatButtonKeepsTheEnclosingButtonStyle() async throws {
        let size = CGSize(width: 240, height: 80)
        let styled = try await host(Button("Pay") {}.buttonStyle(.borderedProminent).padding(), size: size)
        let hooked = try await host(Button("Pay") {}.sideSeatButton().buttonStyle(.borderedProminent).padding(), size: size)
        let plain = try await host(Button("Pay") {}.padding(), size: size)
        #expect(snapshot(styled) == snapshot(hooked))
        #expect(snapshot(styled) != snapshot(plain))
    }

    private func barWindow(_ nav: UINavigationController) async throws -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 800, height: 600))
        window.rootViewController = nav
        window.isHidden = false
        try await Task.sleep(nanoseconds: 600_000_000)
        return window
    }

    private func center(of item: UIBarButtonItem, in window: UIWindow) -> CGPoint? {
        guard #available(iOS 17.0, *), let frame = item.frame(in: window) else { return nil }
        return CGPoint(x: frame.midX, y: frame.midY)
    }

    @Test func barButtonItemsFireTheirActionsAndDisabledOnesDoNot() async throws {
        guard #available(iOS 17.0, *) else { return }
        let log = Log()
        let root = UIViewController()
        let add = UIBarButtonItem(primaryAction: UIAction(title: "Add") { _ in log.events.append("add") })
        let off = UIBarButtonItem(primaryAction: UIAction(title: "Off") { _ in log.events.append("off") })
        off.isEnabled = false
        root.navigationItem.rightBarButtonItems = [add, off]
        root.toolbarItems = [UIBarButtonItem(primaryAction: UIAction(title: "Tool") { _ in log.events.append("tool") })]
        let nav = UINavigationController(rootViewController: root)
        nav.isToolbarHidden = false
        let window = try await barWindow(nav)
        let point = try #require(center(of: add, in: window))
        #expect(tap(window, point.x, point.y) == .performed)
        let disabled = try #require(center(of: off, in: window))
        #expect(tap(window, disabled.x, disabled.y) == .nothing("disabled"))
        let toolItem = try #require(root.toolbarItems?.first)
        let tool = try #require(center(of: toolItem, in: window))
        #expect(tap(window, tool.x, tool.y) == .performed)
        #expect(log.events == ["add", "tool"])
    }

    @Test func standaloneNavigationBarItemsFireTheirActions() async throws {
        guard #available(iOS 17.0, *) else { return }
        let log = Log()
        let screen = UIViewController()
        let bar = UINavigationBar(frame: CGRect(x: 0, y: 0, width: 800, height: 50))
        let item = UINavigationItem(title: "Tasks")
        let back = UIBarButtonItem(primaryAction: UIAction(title: "Back") { _ in log.events.append("back") })
        let edit = UIBarButtonItem(primaryAction: UIAction(title: "Edit") { _ in log.events.append("edit") })
        item.leftBarButtonItem = back
        item.rightBarButtonItems = [edit]
        bar.setItems([item], animated: false)
        screen.view.addSubview(bar)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 800, height: 600))
        window.rootViewController = screen
        window.isHidden = false
        try await Task.sleep(nanoseconds: 600_000_000)
        let backPoint = try #require(center(of: back, in: window))
        #expect(tap(window, backPoint.x, backPoint.y) == .performed)
        let editPoint = try #require(center(of: edit, in: window))
        #expect(tap(window, editPoint.x, editPoint.y) == .performed)
        #expect(log.events == ["back", "edit"])
    }

    @Test func barBackButtonPopsUnlessHidden() async throws {
        guard #available(iOS 17.0, *) else { return }
        let nav = UINavigationController(rootViewController: UIViewController())
        nav.pushViewController(UIViewController(), animated: false)
        let window = try await barWindow(nav)
        let backY = nav.navigationBar.convert(nav.navigationBar.bounds, to: window).midY
        #expect(tap(window, 30, backY) == .performed)
        try await Task.sleep(nanoseconds: 600_000_000)
        #expect(nav.viewControllers.count == 1)

        let locked = UIViewController()
        nav.pushViewController(locked, animated: false)
        try await Task.sleep(nanoseconds: 600_000_000)
        locked.navigationItem.hidesBackButton = true
        try await Task.sleep(nanoseconds: 300_000_000)
        #expect(tap(window, 30, backY).outcome != .performed)
        #expect(nav.viewControllers.count == 2)
    }
}
