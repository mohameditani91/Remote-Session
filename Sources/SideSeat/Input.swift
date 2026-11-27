import UIKit
import SwiftUI

/// Adopt on views whose taps aren't plain controls, such as gesture-driven cells.
public protocol SideSeatTappable: AnyObject {
    /// `point` is in the view's own coordinates. Return false to let the tap reach the view's ancestors.
    func sideSeatTap(at point: CGPoint) -> Bool
}

/// Adopt on rows with swipe actions, for example SwipeCellKit cells. A sideways agent scroll over the row calls it.
public protocol SideSeatSwipeable: AnyObject {
    /// `delta` is the scroll in points: positive `dx` scrolls right, like a finger swiping left. Return false when there is nothing to reveal.
    func sideSeatSwipe(by delta: CGVector) -> Bool
}

public extension View {
    /// Lets the agent trigger `action` by tapping this view. Use it on SwiftUI views driven by gestures.
    func sideSeatTarget(_ action: @escaping () -> Void) -> some View {
        background(TargetMarker(action: action))
    }

    /// `onTapGesture` that the agent can also trigger.
    func sideSeatTapGesture(perform action: @escaping () -> Void) -> some View {
        onTapGesture(perform: action).sideSeatTarget(action)
    }

    /// Lets the agent press the buttons inside this view. Keeps any enclosing button style.
    func sideSeatButton() -> some View {
        buttonStyle(TargetButtonStyle())
    }
}

private struct TargetButtonStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button(configuration).sideSeatTarget(configuration.trigger)
    }
}

private struct TargetMarker: UIViewRepresentable {
    let action: () -> Void

    func makeUIView(context: Context) -> TargetView {
        let view = TargetView()
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: TargetView, context: Context) {
        uiView.action = context.environment.isEnabled ? action : nil
    }
}

/// A single-tap recognizer the agent can trigger. Use it in place of `UITapGestureRecognizer`.
open class SideSeatTapGestureRecognizer: UITapGestureRecognizer {

    private struct Entry {
        weak var target: AnyObject?
        let action: Selector
    }

    private var entries: [Entry] = []
    private var remoteLocation: CGPoint?
    private var remoteState: UIGestureRecognizer.State?

    public override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        if let target, let action { entries.append(Entry(target: target as AnyObject, action: action)) }
    }

    open override func addTarget(_ target: Any, action: Selector) {
        super.addTarget(target, action: action)
        entries.append(Entry(target: target as AnyObject, action: action))
    }

    open override func removeTarget(_ target: Any?, action: Selector?) {
        super.removeTarget(target, action: action)
        entries.removeAll { entry in
            (target == nil || entry.target === target as AnyObject) && (action == nil || entry.action == action)
        }
    }

    open override var state: UIGestureRecognizer.State {
        get { remoteState ?? super.state }
        set { super.state = newValue }
    }

    open override func location(in view: UIView?) -> CGPoint {
        guard let remoteLocation, let window = self.view?.window else { return super.location(in: view) }
        return view.map { $0.convert(remoteLocation, from: window) } ?? remoteLocation
    }

    func remoteTap(atWindowPoint point: CGPoint) -> Bool {
        let live = entries.filter { $0.target != nil }
        guard isEnabled, numberOfTapsRequired == 1, view != nil, !live.isEmpty else { return false }
        remoteLocation = point
        remoteState = .ended
        defer {
            remoteLocation = nil
            remoteState = nil
        }
        for entry in live { _ = entry.target?.perform(entry.action, with: self) }
        return true
    }
}

final class TargetView: UIView {
    var action: (() -> Void)?
}

final class InputDispatcher {

    private let windows: () -> [UIWindow]
    private let bounds: () -> CGRect
    private let firstResponder: () -> UIResponder?
    private let keyboard: () -> CGRect?
    private let maskRule: ((UIView) -> Bool)?

    init(windows: @escaping () -> [UIWindow], bounds: @escaping () -> CGRect, firstResponder: @escaping () -> UIResponder? = UIResponder.sideSeatFirstResponder, keyboard: @escaping () -> CGRect? = { nil }, maskRule: ((UIView) -> Bool)? = nil) {
        self.windows = windows
        self.bounds = bounds
        self.firstResponder = firstResponder
        self.keyboard = keyboard
        self.maskRule = maskRule
    }

    func perform(_ input: RemoteInput) -> InputResult {
        switch input {
        case .tap(let x, let y):
            return tap(at: point(x, y))
        case .scroll(let x, let y, let dx, let dy):
            let size = bounds().size
            return scroll(at: point(x, y), by: CGVector(dx: dx * size.width, dy: dy * size.height))
        case .text(let text):
            return insert(text)
        case .key(let key):
            return press(key)
        case .back:
            return goBack()
        case .hideKeyboard:
            guard let responder = firstResponder(), responder.isFirstResponder else { return .nothing("noKeyboard") }
            return responder.resignFirstResponder() ? .performed : .nothing("keyboardStays")
        }
    }

    /// Names what an input reached, for the audit log. Never reads typed text, row text, labels or masked areas.
    func describe(_ input: RemoteInput) -> String? {
        switch input {
        case .tap(let x, let y):
            let point = point(x, y)
            if keyboard()?.contains(point) == true { return "keyboard" }
            guard let hit = hit(at: point) else { return nil }
            if MaskCollector.rects(in: hit.window, rule: maskRule).contains(where: { $0.contains(hit.point) }) { return "hidden area" }
            return sequence(first: hit.view, next: \.superview).lazy.compactMap(Self.describe).first
        case .text, .key:
            return (firstResponder() as? UIView).flatMap(Self.describe)
        case .scroll, .back, .hideKeyboard:
            return nil
        }
    }

    static func describe(_ view: UIView) -> String? {
        let named = { (text: String?) -> String? in
            guard let text = text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
            return text.count > 40 ? String(text.prefix(40)) + "…" : text
        }
        switch view {
        case let field as UITextField:
            return named(field.placeholder ?? field.accessibilityLabel ?? field.accessibilityIdentifier).map { "\"\($0)\" field" } ?? "text field"
        case let textView as UITextView:
            return named(textView.accessibilityLabel ?? textView.accessibilityIdentifier).map { "\"\($0)\" text box" } ?? "text box"
        case let button as UIButton:
            return named(button.accessibilityLabel ?? button.title(for: .normal) ?? button.configuration?.title ?? button.accessibilityIdentifier).map { "\"\($0)\" button" } ?? "button"
        case let toggle as UISwitch:
            return named(toggle.accessibilityLabel ?? toggle.accessibilityIdentifier).map { "\"\($0)\" switch" } ?? "switch"
        case let segmented as UISegmentedControl:
            return named(segmented.accessibilityLabel ?? segmented.accessibilityIdentifier).map { "\"\($0)\" tabs" } ?? "tabs"
        case let cell as UITableViewCell:
            return cell.ancestor(UITableView.self)?.indexPath(for: cell).map { "row \($0.row + 1)" } ?? "row"
        case let cell as UICollectionViewCell:
            let labels = cell.contentView.subviews.compactMap { $0 as? UILabel }.filter { !$0.isHidden }
            if labels.count == 1, let key = labels[0].text, (1...2).contains(key.count) { return "key \"\(key)\"" }
            return cell.ancestor(UICollectionView.self)?.indexPath(for: cell).map { "item \($0.item + 1)" } ?? "item"
        case let control as UIControl:
            return named(control.accessibilityLabel ?? control.accessibilityIdentifier)
        default:
            guard view.isAccessibilityElement, !(view is UILabel) else { return nil }
            return named(view.accessibilityLabel ?? view.accessibilityIdentifier)
        }
    }

    /// The type name of the view controller the user is looking at.
    func screenName() -> String? {
        guard var controller = WindowSelection.forInput(windows()).first?.rootViewController else { return nil }
        while true {
            if let presented = controller.presentedViewController, !presented.isBeingDismissed {
                controller = presented
            } else if let navigation = controller as? UINavigationController, let visible = navigation.visibleViewController, visible !== controller {
                controller = visible
            } else if let tabs = controller as? UITabBarController, let selected = tabs.selectedViewController {
                controller = selected
            } else if let child = controller.children.last {
                controller = child
            } else {
                return String(describing: type(of: controller))
            }
        }
    }

    func point(_ x: Double, _ y: Double) -> CGPoint {
        let bounds = bounds()
        return CGPoint(x: bounds.minX + CGFloat(min(max(x, 0), 1)) * bounds.width, y: bounds.minY + CGFloat(min(max(y, 0), 1)) * bounds.height)
    }

    func hit(at point: CGPoint) -> (window: UIWindow, view: UIView, point: CGPoint)? {
        for window in WindowSelection.forInput(windows()) {
            let local = CGPoint(x: point.x - window.frame.minX, y: point.y - window.frame.minY)
            guard window.point(inside: local, with: nil), let view = window.hitTest(local, with: nil) else { continue }
            return (window, view, local)
        }
        return nil
    }

    private func tap(at point: CGPoint) -> InputResult {
        if let board = keyboard(), board.contains(point) { return typeKey(at: CGPoint(x: point.x - board.minX, y: point.y - board.minY), on: board.size) }
        guard let hit = hit(at: point) else { return .nothing("noTarget") }
        if let result = closePopover(tappedOn: hit.view, in: hit.window) { return result }
        if let target = Self.target(near: hit.view, at: hit.point, in: hit.window) {
            target.action?()
            return .performed
        }
        if #available(iOS 17.0, *), let result = tapBarItem(hit: hit) { return result }
        var view: UIView? = hit.view
        while let current = view, current !== hit.window {
            if current.gestureRecognizers?.contains(where: { ($0 as? SideSeatTapGestureRecognizer)?.remoteTap(atWindowPoint: hit.point) == true }) == true { return .performed }
            if let result = activate(current, at: hit.window.convert(hit.point, to: current)) { return result }
            view = current.superview
        }
        if Self.containsDisabledControl(at: hit.point, under: hit.view, in: hit.window) { return .nothing("disabled") }
        return accessibilityActivation(from: hit.view) ?? .nothing("noTarget")
    }

    @available(iOS 17.0, *)
    private func tapBarItem(hit: (window: UIWindow, view: UIView, point: CGPoint)) -> InputResult? {
        let chain = Array(sequence(first: hit.view, next: \.superview))
        let bar = chain.lazy.compactMap({ $0 as? UINavigationBar }).first
        if let bar, chain.contains(where: { $0.subviews.contains { String(describing: type(of: $0)).contains("BackButton") } }) {
            return popBack(bar)
        }
        guard bar != nil || chain.contains(where: { $0 is UIToolbar || String(describing: type(of: $0)).contains("BarButton") }) else { return nil }
        guard let item = Self.barButtonItems(in: hit.window).first(where: { $0.customView == nil && $0.frame(in: hit.window)?.contains(hit.point) == true }) else {
            return chain.contains { String(describing: type(of: $0)) == "_UIButtonBarButton" } ? .nothing("barItemNotFound") : nil
        }
        guard item.isEnabled else { return .nothing("disabled") }
        if let action = item.primaryAction {
            (chain.lazy.compactMap { $0 as? UIControl }.first ?? UIButton()).sendAction(action)
            return .performed
        }
        if let selector = item.action, UIApplication.shared.sendAction(selector, to: item.target, from: item, for: nil) {
            return .performed
        }
        return .nothing(item.menu == nil ? "noTarget" : "menu")
    }

    private static func barButtonItems(in window: UIWindow) -> [UIBarButtonItem] {
        var all: [UIBarButtonItem] = []

        func visit(_ controller: UIViewController) {
            if let navigation = controller as? UINavigationController {
                all += navigationItems(navigation.navigationBar.topItem) + (navigation.topViewController?.toolbarItems ?? [])
            }
            controller.children.forEach(visit)
            if let presented = controller.presentedViewController { visit(presented) }
        }

        func bars(_ view: UIView) {
            if let toolbar = view as? UIToolbar { all += toolbar.items ?? [] }
            if let bar = view as? UINavigationBar { all += navigationItems(bar.topItem) }
            view.subviews.forEach(bars)
        }

        window.rootViewController.map(visit)
        bars(window)
        var seen = Set<ObjectIdentifier>()
        return all.filter { seen.insert(ObjectIdentifier($0)).inserted }
    }

    private static func navigationItems(_ item: UINavigationItem?) -> [UIBarButtonItem] {
        guard let item else { return [] }
        var all = (item.leftBarButtonItems ?? []) + (item.rightBarButtonItems ?? [])
        if #available(iOS 16.0, *) {
            all += (item.leadingItemGroups + item.trailingItemGroups + item.centerItemGroups).flatMap(\.barButtonItems)
        }
        return all
    }

    private func popBack(_ bar: UINavigationBar) -> InputResult {
        guard let navigation = bar.delegate as? UINavigationController, navigation.viewControllers.count > 1 else { return .nothing("nothingToGoBack") }
        guard navigation.topViewController?.navigationItem.hidesBackButton != true else { return .nothing("backHidden") }
        navigation.popViewController(animated: true)
        return .performed
    }

    private func activate(_ view: UIView, at point: CGPoint) -> InputResult? {
        if let tappable = view as? SideSeatTappable {
            return tappable.sideSeatTap(at: point) ? .performed : nil
        }
        if let control = view as? UIControl, !(control is UITextField), control.isManagedBySwiftUI {
            return .nothing("swiftUIControl")
        }
        switch view {
        case let toggle as UISwitch:
            guard toggle.isEnabled else { return .nothing("disabled") }
            toggle.setOn(!toggle.isOn, animated: true)
            toggle.sendActions(for: .valueChanged)
            return .performed
        case let segmented as UISegmentedControl:
            return select(segmented, at: point)
        case let slider as UISlider:
            guard slider.isEnabled else { return .nothing("disabled") }
            var fraction = Float(min(max(point.x / max(slider.bounds.width, 1), 0), 1))
            if slider.effectiveUserInterfaceLayoutDirection == .rightToLeft { fraction = 1 - fraction }
            slider.setValue(slider.minimumValue + fraction * (slider.maximumValue - slider.minimumValue), animated: true)
            slider.sendActions(for: .valueChanged)
            return .performed
        case let field as UITextField:
            guard field.isEnabled else { return .nothing("disabled") }
            return field.becomeFirstResponder() || field.isFirstResponder ? .performed : .nothing("cannotFocus")
        case let textView as UITextView:
            guard textView.isEditable else { return nil }
            return textView.becomeFirstResponder() || textView.isFirstResponder ? .performed : .nothing("cannotFocus")
        case let control as UIControl:
            return fire(control)
        case let cell as UITableViewCell:
            return select(cell)
        case let cell as UICollectionViewCell:
            return select(cell)
        default:
            return nil
        }
    }

    private func fire(_ control: UIControl) -> InputResult? {
        let events = control.allControlEvents.intersection([.touchDown, .touchUpInside, .primaryActionTriggered])
        guard !events.isEmpty else { return nil }
        guard control.isEnabled else { return .nothing("disabled") }
        if events.contains(.touchDown) { control.sendActions(for: .touchDown) }
        if events.contains(.touchUpInside) { control.sendActions(for: .touchUpInside) }
        if events.contains(.primaryActionTriggered) { control.sendActions(for: .primaryActionTriggered) }
        return .performed
    }

    private func select(_ segmented: UISegmentedControl, at point: CGPoint) -> InputResult {
        guard segmented.isEnabled, segmented.numberOfSegments > 0 else { return .nothing("disabled") }
        var x = min(max(point.x, 0), segmented.bounds.width)
        if segmented.effectiveUserInterfaceLayoutDirection == .rightToLeft { x = segmented.bounds.width - x }
        let widths = (0..<segmented.numberOfSegments).map { segmented.widthForSegment(at: $0) }
        let automaticCount = CGFloat(max(widths.filter { $0 == 0 }.count, 1))
        let automaticWidth = max(segmented.bounds.width - widths.reduce(0, +), 0) / automaticCount
        var edge: CGFloat = 0
        var index = segmented.numberOfSegments - 1
        for (i, width) in widths.enumerated() {
            edge += width == 0 ? automaticWidth : width
            if x < edge {
                index = i
                break
            }
        }
        guard segmented.isEnabledForSegment(at: index) else { return .nothing("disabled") }
        guard segmented.isMomentary || segmented.selectedSegmentIndex != index else { return .performed }
        segmented.selectedSegmentIndex = index
        segmented.sendActions(for: .valueChanged)
        return .performed
    }

    private func select(_ cell: UITableViewCell) -> InputResult? {
        guard let table = cell.ancestor(UITableView.self), let indexPath = table.indexPath(for: cell) else { return nil }
        guard table.isEditing ? table.allowsSelectionDuringEditing : table.allowsSelection else { return .nothing("selectionDisabled") }
        guard table.delegate?.responds(to: #selector(UITableViewDelegate.tableView(_:didSelectRowAt:))) == true else { return nil }
        let delegate = table.delegate
        if delegate?.tableView?(table, shouldHighlightRowAt: indexPath) == false { return .nothing("notSelectable") }

        let multiple = table.isEditing ? table.allowsMultipleSelectionDuringEditing : table.allowsMultipleSelection
        if multiple, table.indexPathsForSelectedRows?.contains(indexPath) == true {
            guard let target = Self.willDeselect(indexPath, in: table) else { return .nothing("notSelectable") }
            table.deselectRow(at: target, animated: true)
            delegate?.tableView?(table, didDeselectRowAt: target)
            return .performed
        }

        guard let target = Self.willSelect(indexPath, in: table) else { return .nothing("notSelectable") }
        if !multiple, let previous = table.indexPathForSelectedRow, previous != target {
            table.deselectRow(at: previous, animated: false)
            delegate?.tableView?(table, didDeselectRowAt: previous)
        }
        table.selectRow(at: target, animated: true, scrollPosition: .none)
        delegate?.tableView?(table, didSelectRowAt: target)
        return .performed
    }

    private static func willSelect(_ indexPath: IndexPath, in table: UITableView) -> IndexPath? {
        guard let delegate = table.delegate, delegate.responds(to: #selector(UITableViewDelegate.tableView(_:willSelectRowAt:))) else { return indexPath }
        return delegate.tableView?(table, willSelectRowAt: indexPath)
    }

    private static func willDeselect(_ indexPath: IndexPath, in table: UITableView) -> IndexPath? {
        guard let delegate = table.delegate, delegate.responds(to: #selector(UITableViewDelegate.tableView(_:willDeselectRowAt:))) else { return indexPath }
        return delegate.tableView?(table, willDeselectRowAt: indexPath)
    }

    private func select(_ cell: UICollectionViewCell) -> InputResult? {
        guard let collection = cell.ancestor(UICollectionView.self), let indexPath = collection.indexPath(for: cell) else { return nil }
        guard collection.isEditing ? collection.allowsSelectionDuringEditing : collection.allowsSelection else { return .nothing("selectionDisabled") }
        guard collection.delegate?.responds(to: #selector(UICollectionViewDelegate.collectionView(_:didSelectItemAt:))) == true else { return nil }
        let delegate = collection.delegate
        if delegate?.collectionView?(collection, shouldHighlightItemAt: indexPath) == false { return .nothing("notSelectable") }

        let multiple = collection.isEditing ? collection.allowsMultipleSelectionDuringEditing : collection.allowsMultipleSelection
        if multiple, collection.indexPathsForSelectedItems?.contains(indexPath) == true {
            guard delegate?.collectionView?(collection, shouldDeselectItemAt: indexPath) != false else { return .nothing("notSelectable") }
            collection.deselectItem(at: indexPath, animated: true)
            delegate?.collectionView?(collection, didDeselectItemAt: indexPath)
            return .performed
        }

        guard delegate?.collectionView?(collection, shouldSelectItemAt: indexPath) != false else { return .nothing("notSelectable") }
        if !multiple {
            for previous in collection.indexPathsForSelectedItems ?? [] where previous != indexPath {
                collection.deselectItem(at: previous, animated: false)
                delegate?.collectionView?(collection, didDeselectItemAt: previous)
            }
        }
        collection.selectItem(at: indexPath, animated: true, scrollPosition: [])
        delegate?.collectionView?(collection, didSelectItemAt: indexPath)
        return .performed
    }

    private static func containsDisabledControl(at point: CGPoint, under root: UIView, in window: UIWindow) -> Bool {
        root.subviews.reversed().contains { view in
            guard !view.isHidden, view.alpha > 0.01, view.bounds.contains(window.convert(point, to: view)) else { return false }
            if let control = view as? UIControl, !control.isEnabled { return true }
            return containsDisabledControl(at: point, under: view, in: window)
        }
    }

    private func accessibilityActivation(from view: UIView) -> InputResult? {
        var current: UIView? = view
        while let candidate = current {
            if candidate.isAccessibilityElement { return candidate.accessibilityActivate() ? .performed : nil }
            current = candidate.superview
        }
        return nil
    }

    static func target(near view: UIView, at point: CGPoint, in window: UIWindow) -> TargetView? {
        let isOnUIKitControl = sequence(first: view, next: \.superview).contains { ($0 as? UIControl)?.isManagedBySwiftUI == false }
        var container: UIView? = view
        while let current = container, current !== window {
            if let target = smallestTarget(in: current, at: point, window: window) { return target }
            if !isOnUIKitControl, current.isSwiftUIListCell, let only = onlyTarget(in: current) { return only }
            if current.next is UIViewController { return nil }
            container = current.superview
        }
        return nil
    }

    /// A SwiftUI list row with one marked target fires it from anywhere in the row, like a real tap would.
    private static func onlyTarget(in root: UIView) -> TargetView? {
        var found: [TargetView] = []
        func visit(_ view: UIView) {
            guard !view.isHidden, view.alpha > 0.01, found.count < 2 else { return }
            if let target = view as? TargetView, target.action != nil { found.append(target) }
            view.subviews.forEach(visit)
        }
        visit(root)
        return found.count == 1 ? found[0] : nil
    }

    private static func smallestTarget(in root: UIView, at point: CGPoint, window: UIWindow) -> TargetView? {
        var best: (view: TargetView, area: CGFloat)?
        func visit(_ view: UIView, clip: CGRect) {
            guard !view.isHidden, view.alpha > 0.01 else { return }
            let frame = view.convert(view.bounds, to: window)
            if let target = view as? TargetView, target.action != nil, frame.contains(point), clip.contains(point) {
                let area = frame.width * frame.height
                if area < (best?.area ?? .greatestFiniteMagnitude) { best = (target, area) }
            }
            let childClip = view.clipsToBounds ? clip.intersection(frame) : clip
            view.subviews.forEach { visit($0, clip: childClip) }
        }
        visit(root, clip: window.bounds)
        return best?.view
    }

    private func closePopover(tappedOn view: UIView, in window: UIWindow) -> InputResult? {
        guard let top = window.rootViewController?.topPresented,
              let popover = top.presentationController as? UIPopoverPresentationController,
              let popup = popover.presentedView, !view.isDescendant(of: popup),
              !(popover.passthroughViews ?? []).contains(where: view.isDescendant(of:)) else { return nil }
        if top is UIAlertController { return .nothing("alertOpen") }
        guard !top.isModalInPresentation, Self.shouldDismiss(popover) else { return .nothing("popoverStaysOpen") }
        popover.delegate?.presentationControllerWillDismiss?(popover)
        top.dismiss(animated: true) { Self.didDismiss(popover) }
        return .performed
    }

    static func shouldDismiss(_ popover: UIPopoverPresentationController) -> Bool {
        guard let delegate = popover.delegate else { return true }
        if let answer = delegate.presentationControllerShouldDismiss?(popover) { return answer }
        return unsafeBitCast(delegate, to: LegacyPopoverDelegate.self).popoverPresentationControllerShouldDismissPopover?(popover) ?? true
    }

    static func didDismiss(_ popover: UIPopoverPresentationController) {
        guard let delegate = popover.delegate else { return }
        if delegate.presentationControllerDidDismiss?(popover) == nil {
            unsafeBitCast(delegate, to: LegacyPopoverDelegate.self).popoverPresentationControllerDidDismissPopover?(popover)
        }
    }

    private func scroll(at point: CGPoint, by delta: CGVector) -> InputResult {
        guard let hit = hit(at: point) else { return .nothing("noScrollView") }
        let vertical = abs(delta.dy) >= abs(delta.dx)
        let chain = Array(sequence(first: hit.view, next: \.superview))
        let below = Self.scrollViews(at: hit.point, under: hit.view, in: hit.window)
        let ancestors = chain.compactMap { $0 as? UIScrollView }.filter(\.isScrollEnabled)
        var declinedSwipe = false
        if !vertical, let index = chain.firstIndex(where: { $0 is SideSeatSwipeable }), let swipeable = chain[index] as? SideSeatSwipeable {
            let inside = below + chain[..<index].compactMap { $0 as? UIScrollView }.filter(\.isScrollEnabled)
            if !inside.contains(where: { $0.canScroll(vertically: false) }) {
                if swipeable.sideSeatSwipe(by: delta) { return .performed }
                declinedSwipe = true
            }
        }
        let candidates = below + ancestors
        guard let target = candidates.first(where: { $0.canScroll(vertically: vertical) }) ?? candidates.first else {
            return .nothing(declinedSwipe ? "noSwipeActions" : "noScrollView")
        }
        let result = move(target, by: delta)
        return declinedSwipe && result.outcome != .performed ? .nothing("noSwipeActions") : result
    }

    private static func scrollViews(at point: CGPoint, under view: UIView, in window: UIWindow) -> [UIScrollView] {
        view.subviews.reversed().flatMap { subview -> [UIScrollView] in
            guard !subview.isHidden, subview.alpha > 0.01, subview.isUserInteractionEnabled else { return [] }
            let inside = subview.bounds.contains(window.convert(point, to: subview))
            guard inside || !subview.clipsToBounds else { return [] }
            let nested = scrollViews(at: point, under: subview, in: window)
            guard inside, let scrollView = subview as? UIScrollView, scrollView.isScrollEnabled else { return nested }
            return nested + [scrollView]
        }
    }

    private func move(_ scrollView: UIScrollView, by delta: CGVector) -> InputResult {
        let inset = scrollView.adjustedContentInset
        let minX = -inset.left
        let minY = -inset.top
        let maxX = max(minX, scrollView.contentSize.width + inset.right - scrollView.bounds.width)
        let maxY = max(minY, scrollView.contentSize.height + inset.bottom - scrollView.bounds.height)
        let offset = scrollView.contentOffset
        let target = CGPoint(x: min(max(offset.x + delta.dx, minX), maxX), y: min(max(offset.y + delta.dy, minY), maxY))
        guard abs(target.x - offset.x) > 0.5 || abs(target.y - offset.y) > 0.5 else { return .nothing("atEdge") }
        scrollView.setContentOffset(target, animated: false)
        return .performed
    }

    private func insert(_ text: String) -> InputResult {
        guard let input = firstResponder() as? UIKeyInput else { return .nothing("noFocusedField") }
        input.insertText(text)
        return .performed
    }

    private func typeKey(at point: CGPoint, on size: CGSize) -> InputResult {
        guard !KeyboardPlaceholder.isSensitive(firstResponder(), rule: maskRule) else { return .nothing("keyboard") }
        switch KeyboardPlaceholder.key(at: point, size: size) {
        case .character(let character)?: return insert(character)
        case .space?: return insert(" ")
        case .delete?: return press(.backspace)
        case .enter?: return press(.return)
        case nil: return .nothing("keyboard")
        }
    }

    private func press(_ key: RemoteKey) -> InputResult {
        guard let responder = firstResponder() else { return .nothing("noFocusedField") }
        switch key {
        case .backspace:
            guard let input = responder as? UIKeyInput else { return .nothing("noFocusedField") }
            input.deleteBackward()
        case .return:
            if let field = responder as? UITextField {
                if field.delegate?.textFieldShouldReturn?(field) ?? true {
                    field.sendActions(for: .editingDidEndOnExit)
                }
            } else if let input = responder as? UIKeyInput {
                input.insertText("\n")
            } else {
                return .nothing("noFocusedField")
            }
        }
        return .performed
    }

    private func goBack() -> InputResult {
        guard let top = WindowSelection.forInput(windows()).lazy.compactMap(\.rootViewController).first?.topPresented else { return .nothing("nothingToGoBack") }
        if top is UIAlertController { return .nothing("alertOpen") }
        if let navigation = Self.navigationToPop(in: top) {
            guard navigation.topViewController?.navigationItem.hidesBackButton != true else { return .nothing("backHidden") }
            navigation.popViewController(animated: true)
            return .performed
        }
        guard top.presentingViewController != nil, !top.isModalInPresentation else { return .nothing("nothingToGoBack") }
        top.dismiss(animated: true)
        return .performed
    }

    static func navigationToPop(in controller: UIViewController) -> UINavigationController? {
        if let navigation = controller as? UINavigationController, navigation.viewControllers.count > 1 { return navigation }
        for child in controller.children where child.isViewLoaded && child.view.window != nil && !child.view.isHidden {
            if let navigation = navigationToPop(in: child) { return navigation }
        }
        return nil
    }
}

@objc private protocol LegacyPopoverDelegate {
    @objc optional func popoverPresentationControllerShouldDismissPopover(_ popoverPresentationController: UIPopoverPresentationController) -> Bool
    @objc optional func popoverPresentationControllerDidDismissPopover(_ popoverPresentationController: UIPopoverPresentationController)
}

private extension UIView {
    func ancestor<T: UIView>(_ type: T.Type) -> T? {
        sequence(first: superview, next: { $0?.superview }).lazy.compactMap { $0 as? T }.first
    }

    var isSwiftUIListCell: Bool {
        (self is UICollectionViewCell || self is UITableViewCell) && String(reflecting: type(of: self)).hasPrefix("SwiftUI.")
    }
}

private extension UIControl {
    /// SwiftUI's own controls ignore UIKit actions, so tapping them would report a false success.
    var isManagedBySwiftUI: Bool {
        // Read through KVC: bridging `allTargets` to Swift crashes on targets that aren't NSObjects.
        guard let targets = value(forKey: "allTargets") as? NSSet, targets.count > 0 else { return false }
        return targets.allSatisfy { String(reflecting: type(of: $0)).hasPrefix("SwiftUI.") }
    }
}

private extension UIViewController {
    var topPresented: UIViewController {
        var top = self
        while let presented = top.presentedViewController, !presented.isBeingDismissed { top = presented }
        return top
    }
}

private extension UIScrollView {
    func canScroll(vertically: Bool) -> Bool {
        let inset = adjustedContentInset
        return vertically
            ? contentSize.height + inset.top + inset.bottom > bounds.height + 0.5
            : contentSize.width + inset.left + inset.right > bounds.width + 0.5
    }
}

extension UIResponder {

    private static weak var found: UIResponder?

    static func sideSeatFirstResponder() -> UIResponder? {
        found = nil
        UIApplication.shared.sendAction(#selector(UIResponder.sideSeatCaptureFirstResponder(_:)), to: nil, from: nil, for: nil)
        return found
    }

    @objc private func sideSeatCaptureFirstResponder(_ sender: Any?) {
        UIResponder.found = self
    }
}
