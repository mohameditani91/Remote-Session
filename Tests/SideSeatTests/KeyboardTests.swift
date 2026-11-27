import UIKit
import Testing
@testable import SideSeat

@MainActor
@Suite struct KeyboardTests {

    @Test func followsTheKeyboardUntilItHides() {
        let center = NotificationCenter()
        let keyboard = KeyboardFrame(center: center)
        let shown = CGRect(x: 0, y: 700, width: 1024, height: 324)

        center.post(name: UIResponder.keyboardWillChangeFrameNotification, object: nil, userInfo: [UIResponder.keyboardFrameEndUserInfoKey: shown])
        #expect(keyboard.screenRect == shown)

        center.post(name: UIResponder.keyboardWillHideNotification, object: nil)
        #expect(keyboard.screenRect == nil)
    }

    @Test func onlyTheOnScreenPartOfTheKeyboardCounts() {
        let bounds = CGRect(x: 0, y: 0, width: 1024, height: 768)
        #expect(KeyboardFrame.visible(CGRect(x: 0, y: 500, width: 1024, height: 400), in: bounds) == CGRect(x: 0, y: 500, width: 1024, height: 268))
        #expect(KeyboardFrame.visible(CGRect(x: 0, y: 768, width: 1024, height: 300), in: bounds) == nil)
        #expect(KeyboardFrame.visible(nil, in: bounds) == nil)
    }

    @Test func thePictureShowsAKeyboardWhereTheKeyboardIs() throws {
        let bounds = CGRect(x: 0, y: 0, width: 400, height: 300)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.preferredRange = .standard
        let image = try #require(UIGraphicsImageRenderer(bounds: bounds, format: format).image { context in
            UIColor.red.setFill()
            context.fill(bounds)
            KeyboardPlaceholder.draw(in: CGRect(x: 0, y: 200, width: 400, height: 100), dark: false, blurred: false)
        }.cgImage)

        let keyboard = color(image, x: 200, y: 250)
        #expect(!(keyboard[0] > 200 && keyboard[1] < 50))
        let above = color(image, x: 5, y: 100)
        #expect(above[0] > 200 && above[1] < 50)
    }

    @Test func theKeyboardFollowsDarkModeAndIsDrawnOnce() throws {
        let size = CGSize(width: 400, height: 100)
        let light = try #require(KeyboardPlaceholder.image(size: size, dark: false, blurred: true))
        #expect(KeyboardPlaceholder.image(size: size, dark: false, blurred: true) === light)
        let dark = try #require(KeyboardPlaceholder.image(size: size, dark: true, blurred: true))
        let lightPixel = color(try #require(light.cgImage), x: 200, y: 50)
        let darkPixel = color(try #require(dark.cgImage), x: 200, y: 50)
        #expect(Int(darkPixel[0]) + Int(darkPixel[1]) + Int(darkPixel[2]) < Int(lightPixel[0]) + Int(lightPixel[1]) + Int(lightPixel[2]))
    }

    @Test func everyDrawnKeyMapsBackToItself() {
        let bounds = CGRect(x: 0, y: 0, width: 1024, height: 320)
        let keys = KeyboardPlaceholder.keys(in: bounds)
        let labelled = keys.filter { $0.label != nil }
        #expect(labelled.count == 47)
        for entry in labelled {
            #expect(KeyboardPlaceholder.key(at: CGPoint(x: entry.rect.midX, y: entry.rect.midY), size: bounds.size) == .character(entry.label!))
        }
        #expect(keys.filter { $0.key == .delete }.count == 1)
        #expect(keys.filter { $0.key == .enter }.count == 1)
        #expect(keys.filter { $0.key == .space }.count == 1)
        #expect(KeyboardPlaceholder.key(at: CGPoint(x: 512, y: 5), size: bounds.size) == nil)
    }

    @Test func sharpAndBlurredKeyboardsDiffer() throws {
        let size = CGSize(width: 400, height: 120)
        let sharp = try #require(KeyboardPlaceholder.image(size: size, dark: false, blurred: false)?.cgImage)
        let blurred = try #require(KeyboardPlaceholder.image(size: size, dark: false, blurred: true)?.cgImage)
        let points = stride(from: 5, to: 395, by: 3).map { (x: $0, y: 50) }
        #expect(points.contains { color(sharp, x: $0.x, y: $0.y) != color(blurred, x: $0.x, y: $0.y) })
    }

    @Test func passwordAndHiddenFieldsAreSensitive() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 800, height: 600))
        window.isHidden = false
        let plain = UITextField(frame: CGRect(x: 10, y: 10, width: 200, height: 40))
        let password = UITextField(frame: CGRect(x: 10, y: 60, width: 200, height: 40))
        password.isSecureTextEntry = true
        let hidden = UITextField(frame: CGRect(x: 10, y: 110, width: 200, height: 40))
        hidden.isSideSeatMasked = true
        [plain, password, hidden].forEach(window.addSubview)

        #expect(!KeyboardPlaceholder.isSensitive(plain))
        #expect(KeyboardPlaceholder.isSensitive(password))
        #expect(KeyboardPlaceholder.isSensitive(hidden))
        #expect(!KeyboardPlaceholder.isSensitive(nil))
    }

}

private func color(_ image: CGImage, x: Int, y: Int) -> [UInt8] {
    var data = [UInt8](repeating: 0, count: 4)
    let context = CGContext(data: &data, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.draw(image, in: CGRect(x: -x, y: y - image.height + 1, width: image.width, height: image.height))
    return data
}
