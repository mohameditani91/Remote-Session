import UIKit
import SwiftUI
import CoreImage

private var maskedKey: UInt8 = 0

public extension UIView {
    /// Covers this view (and everything inside it) with a placeholder in frames sent to the agent.
    var isSideSeatMasked: Bool {
        get { (objc_getAssociatedObject(self, &maskedKey) as? Bool) ?? false }
        set { objc_setAssociatedObject(self, &maskedKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }
}

public extension View {
    /// Covers this view with a placeholder in frames sent to the agent.
    func sideSeatMasked() -> some View {
        background(MaskMarker())
    }
}

public enum MaskRules {
    /// Masks the whole root view of any view controller of the given types.
    public static func screens(_ types: [UIViewController.Type]) -> (UIView) -> Bool {
        { view in
            guard let controller = view.next as? UIViewController, controller.viewIfLoaded === view else { return false }
            return types.contains { controller.isKind(of: $0) }
        }
    }
}

private struct MaskMarker: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.isSideSeatMasked = true
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

enum MaskCollector {

    static let padding: CGFloat = 2

    static func rects(in window: UIWindow, rule: ((UIView) -> Bool)? = nil) -> [CGRect] {
        var masks: [CGRect] = []

        func visit(_ view: UIView, clip: CGRect) {
            guard !view.isHidden, view.alpha > 0.01 else { return }
            let frame = view.convert(view.bounds, to: window)
            if isMasked(view, rule: rule) {
                let visible = frame.intersection(clip)
                if !visible.isNull, visible.width >= 1, visible.height >= 1 { masks.append(visible) }
                return
            }
            if covers(view) { masks.removeAll { frame.contains($0) } }
            let childClip = view.clipsToBounds ? clip.intersection(frame) : clip
            view.subviews.forEach { visit($0, clip: childClip) }
        }

        window.subviews.forEach { visit($0, clip: window.bounds) }
        return masks.map { $0.insetBy(dx: -padding, dy: -padding) }
    }

    private static func isMasked(_ view: UIView, rule: ((UIView) -> Bool)?) -> Bool {
        if view.isSideSeatMasked { return true }
        if let field = view as? UITextField, field.isSecureTextEntry { return true }
        return rule?(view) ?? false
    }

    private static func covers(_ view: UIView) -> Bool {
        guard view.alpha >= 1, (view.backgroundColor?.cgColor.alpha ?? 0) >= 1 else { return false }
        return view.transform.isIdentity && CATransform3DIsIdentity(view.layer.transform) && view.layer.cornerRadius == 0
    }
}

/// What the agent sees over a masked area: blurred blank tiles, drawn from nothing on the screen.
enum MaskPlaceholder {

    static let blurRadius: CGFloat = 8

    private static let tile: CGFloat = 56
    private static let gap: CGFloat = 12
    private static let context = CIContext()
    private static let lock = NSLock()
    private static var cache: [String: CGImage] = [:]

    static func draw(in rect: CGRect) -> Bool {
        guard let image = image(size: rect.size) else { return false }
        UIImage(cgImage: image).draw(in: rect)
        return true
    }

    static func image(size: CGSize) -> CGImage? {
        let width = size.width.rounded(.up), height = size.height.rounded(.up)
        guard width >= 1, height >= 1 else { return nil }
        let key = "\(Int(width))x\(Int(height))"
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[key] { return cached }
        let bounds = CGRect(x: 0, y: 0, width: width, height: height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let sharp = UIGraphicsImageRenderer(size: bounds.size, format: format).image { _ in drawTiles(in: bounds) }
        guard let input = CIImage(image: sharp),
              let blurred = CIFilter(name: "CIGaussianBlur", parameters: [kCIInputImageKey: input.clampedToExtent(), kCIInputRadiusKey: blurRadius])?.outputImage,
              let image = context.createCGImage(blurred, from: input.extent) else { return nil }
        if cache.count >= 32 { cache.removeAll() }
        cache[key] = image
        return image
    }

    private static func drawTiles(in bounds: CGRect) {
        UIColor(red: 0.78, green: 0.79, blue: 0.82, alpha: 1).setFill()
        UIRectFill(bounds)
        UIColor(red: 0.88, green: 0.89, blue: 0.91, alpha: 1).setFill()
        let columns = max(1, Int((bounds.width - gap) / (tile + gap)))
        let rows = max(1, Int((bounds.height - gap) / (tile + gap)))
        let width = (bounds.width - gap * CGFloat(columns + 1)) / CGFloat(columns)
        let height = (bounds.height - gap * CGFloat(rows + 1)) / CGFloat(rows)
        guard width > 0, height > 0 else { return }
        for row in 0..<rows {
            for column in 0..<columns {
                let rect = CGRect(x: gap + CGFloat(column) * (width + gap), y: gap + CGFloat(row) * (height + gap), width: width, height: height)
                UIBezierPath(roundedRect: rect, cornerRadius: min(width, height) * 0.18).fill()
            }
        }
    }
}
