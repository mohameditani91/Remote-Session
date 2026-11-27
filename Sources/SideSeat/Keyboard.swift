import UIKit
import CoreImage

/// Tracks the system keyboard, which `drawHierarchy` can't capture because it runs out of process.
final class KeyboardFrame {

    private(set) var screenRect: CGRect?
    private let center: NotificationCenter
    private var observers: [NSObjectProtocol] = []

    init(center: NotificationCenter = .default) {
        self.center = center
        observers = [
            center.addObserver(forName: UIResponder.keyboardWillChangeFrameNotification, object: nil, queue: nil) { [weak self] note in
                self?.screenRect = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
            },
            center.addObserver(forName: UIResponder.keyboardWillHideNotification, object: nil, queue: nil) { [weak self] _ in
                self?.screenRect = nil
            },
        ]
    }

    deinit {
        observers.forEach(center.removeObserver)
    }

    func rect(in scene: UIWindowScene) -> CGRect? {
        Self.visible(screenRect.map { scene.coordinateSpace.convert($0, from: scene.screen.coordinateSpace) }, in: scene.coordinateSpace.bounds)
    }

    static func visible(_ rect: CGRect?, in bounds: CGRect) -> CGRect? {
        guard let rect else { return nil }
        let shown = rect.intersection(bounds)
        return shown.isNull || shown.height < 1 ? nil : shown
    }
}

/// A drawn keyboard the agent sees, and can tap, where the system keyboard is open.
/// It is blurred and ignores taps while the focused field is a password or masked.
enum KeyboardPlaceholder {

    enum Key: Hashable {
        case character(String)
        case delete
        case enter
        case space
    }

    static let blurRadius: CGFloat = 8

    private static let rows: [[CGFloat]] = [
        Array(repeating: 1, count: 13) + [1.5],
        [1.5] + Array(repeating: 1, count: 13),
        [1.75] + Array(repeating: 1, count: 11) + [1.75],
        [2.25] + Array(repeating: 1, count: 10) + [2.25],
        [1.5, 1.5, 1.5, 6.5, 1.5, 2],
    ]
    private static let letters = ["`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./", ""].map { $0.map(String.init) }
    private static let context = CIContext()
    private static var cached: [String: UIImage] = [:]
    private static let lock = NSLock()

    static func draw(in rect: CGRect, dark: Bool, blurred: Bool) {
        image(size: rect.size, dark: dark, blurred: blurred)?.draw(in: rect)
    }

    static func isSensitive(_ responder: UIResponder?, rule: ((UIView) -> Bool)? = nil) -> Bool {
        if (responder as? UITextField)?.isSecureTextEntry == true || (responder as? UITextView)?.isSecureTextEntry == true { return true }
        guard let view = responder as? UIView, let window = view.window else { return false }
        if view.isSideSeatMasked { return true }
        let frame = view.convert(view.bounds, to: window)
        return MaskCollector.rects(in: window, rule: rule).contains { $0.intersects(frame) }
    }

    static func key(at point: CGPoint, size: CGSize) -> Key? {
        keys(in: CGRect(origin: .zero, size: size)).first { $0.rect.contains(point) }?.key
    }

    static func image(size: CGSize, dark: Bool, blurred: Bool) -> UIImage? {
        let name = "\(size.width)x\(size.height)-\(dark)-\(blurred)"
        lock.lock()
        defer { lock.unlock() }
        if let image = cached[name] { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        var image = UIGraphicsImageRenderer(size: size, format: format).image { _ in drawKeys(in: CGRect(origin: .zero, size: size), dark: dark) }
        if blurred {
            guard let input = CIImage(image: image),
                  let output = CIFilter(name: "CIGaussianBlur", parameters: [kCIInputImageKey: input.clampedToExtent(), kCIInputRadiusKey: blurRadius])?.outputImage,
                  let cgImage = context.createCGImage(output, from: input.extent) else { return nil }
            image = UIImage(cgImage: cgImage)
        }
        if cached.count >= 8 { cached.removeAll() }
        cached[name] = image
        return image
    }

    static func keys(in bounds: CGRect) -> [(rect: CGRect, key: Key?, width: CGFloat, label: String?)] {
        let pad = bounds.width * 0.006
        let area = bounds.inset(by: UIEdgeInsets(top: bounds.height * 0.12 + pad, left: pad, bottom: pad, right: pad))
        let gap = area.width * 0.006
        let rowHeight = (area.height - gap * CGFloat(rows.count - 1)) / CGFloat(rows.count)
        guard rowHeight > 0 else { return [] }
        var keys: [(rect: CGRect, key: Key?, width: CGFloat, label: String?)] = []
        for (index, row) in rows.enumerated() {
            let unit = (area.width - gap * CGFloat(row.count - 1)) / row.reduce(0, +)
            let y = area.minY + CGFloat(index) * (rowHeight + gap)
            var labels = letters[index].makeIterator()
            var x = area.minX
            for (position, width) in row.enumerated() {
                let rect = CGRect(x: x, y: y, width: unit * width, height: rowHeight)
                let label = width == 1 ? labels.next() : nil
                let key: Key?
                switch (index, position, label) {
                case (_, _, let label?): key = .character(label)
                case (0, row.count - 1, nil): key = .delete
                case (2, row.count - 1, nil): key = .enter
                case (4, 3, nil): key = .space
                default: key = nil
                }
                keys.append((rect, key, width, label))
                x = rect.maxX + gap
            }
        }
        return keys
    }

    private static func drawKeys(in bounds: CGRect, dark: Bool) {
        let back = dark ? UIColor(white: 0.17, alpha: 1) : UIColor(red: 0.82, green: 0.83, blue: 0.86, alpha: 1)
        let key = dark ? UIColor(white: 0.42, alpha: 1) : UIColor.white
        let special = dark ? UIColor(white: 0.29, alpha: 1) : UIColor(red: 0.67, green: 0.7, blue: 0.74, alpha: 1)
        back.setFill()
        UIRectFill(bounds)
        let all = keys(in: bounds)
        guard let rowHeight = all.first?.rect.height else { return }
        let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: rowHeight * 0.4), .foregroundColor: dark ? UIColor.white : UIColor.black]
        let symbols: [Key: String] = [.delete: "⌫", .enter: "⏎"]
        for entry in all {
            (entry.width > 1 && entry.width < 6 ? special : key).setFill()
            UIBezierPath(roundedRect: entry.rect, cornerRadius: rowHeight * 0.12).fill()
            guard let text = entry.label ?? entry.key.flatMap({ symbols[$0] }) else { continue }
            let label = text as NSString
            let size = label.size(withAttributes: attributes)
            label.draw(at: CGPoint(x: entry.rect.midX - size.width / 2, y: entry.rect.midY - size.height / 2), withAttributes: attributes)
        }
    }
}
