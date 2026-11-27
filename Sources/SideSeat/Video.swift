import UIKit
import CoreImage
import CoreMedia
import ReplayKit
import VideoToolbox

/// Sends frames to the agent: JPEG stills (`Streamer`) or H.264 video (`VideoStreamer`).
protocol FrameStreamer: AnyObject {
    func start()
    func stop()
    func captureSoon(after delay: TimeInterval)
    func requestKeyframe()
}

protocol ScheduledWork {
    func cancel()
}

protocol Scheduler {
    func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> ScheduledWork
}

struct MainQueueScheduler: Scheduler {
    func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> ScheduledWork {
        let work = DispatchWorkItem(block: block)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
        return work
    }
}

extension DispatchWorkItem: ScheduledWork {}

/// Binary frame layout for video. JPEG frames stay bare JPEG, which always starts with 0xFF.
///
///     byte 0: version (1)   byte 1: kind (2 = H.264 Annex-B)   bytes 2–5: seq, big-endian
///     byte 6: flags (bit 0 = keyframe)   bytes 7…: one access unit, SPS and PPS in front of every keyframe
enum VideoFrame {

    static let version: UInt8 = 1
    static let h264Kind: UInt8 = 2
    static let headerLength = 7

    static func encode(seq: UInt32, h264: Data, keyframe: Bool) -> Data {
        var data = Data([version, h264Kind])
        withUnsafeBytes(of: seq.bigEndian) { data.append(contentsOf: $0) }
        data.append(keyframe ? 1 : 0)
        data.append(h264)
        return data
    }

    static func decode(_ data: Data) -> (seq: UInt32, keyframe: Bool, h264: Data)? {
        let bytes = [UInt8](data.prefix(headerLength))
        guard bytes.count == headerLength, bytes[0] == version, bytes[1] == h264Kind else { return nil }
        let seq = bytes[2...5].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return (seq, bytes[6] & 1 == 1, data.dropFirst(headerLength))
    }
}

enum AnnexB {

    static let startCode: [UInt8] = [0, 0, 0, 1]

    static func convert(avcc: Data, lengthSize: Int = 4, parameterSets: [Data] = []) -> Data? {
        var output = Data()
        for set in parameterSets {
            output.append(contentsOf: startCode)
            output.append(set)
        }
        var offset = avcc.startIndex
        while offset < avcc.endIndex {
            guard offset + lengthSize <= avcc.endIndex else { return nil }
            let length = avcc[offset..<offset + lengthSize].reduce(0) { $0 << 8 | Int($1) }
            let start = offset + lengthSize
            guard length > 0, start + length <= avcc.endIndex else { return nil }
            output.append(contentsOf: startCode)
            output.append(avcc[start..<start + length])
            offset = start + length
        }
        return output
    }

    static func frame(from sample: CMSampleBuffer) -> (data: Data, keyframe: Bool)? {
        guard let block = CMSampleBufferGetDataBuffer(sample), let format = CMSampleBufferGetFormatDescription(sample) else { return nil }
        var avcc = Data(count: CMBlockBufferGetDataLength(block))
        let copied = avcc.withUnsafeMutableBytes { bytes in
            bytes.baseAddress.map { CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: bytes.count, destination: $0) } ?? kCMBlockBufferBadPointerParameterErr
        }
        guard copied == noErr else { return nil }
        let keyframe = isKeyframe(sample)
        let (sets, lengthSize) = parameterSets(format)
        guard let data = convert(avcc: avcc, lengthSize: lengthSize, parameterSets: keyframe ? sets : []) else { return nil }
        return (data, keyframe)
    }

    private static func isKeyframe(_ sample: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]], let first = attachments.first else { return true }
        return !(first[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
    }

    private static func parameterSets(_ format: CMFormatDescription) -> ([Data], Int) {
        var count = 0
        var lengthSize: Int32 = 4
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: &lengthSize) == noErr else { return ([], 4) }
        let sets: [Data] = (0..<count).compactMap { index in
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: index, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) == noErr, let pointer else { return nil }
            return Data(bytes: pointer, count: size)
        }
        return (sets, Int(lengthSize))
    }
}

final class H264Encoder {

    static let averageBitRate = 1_800_000
    static let peakBytesPerSecond = 2_500_000 / 8
    static let keyframeInterval: TimeInterval = 2

    let width: Int
    let height: Int
    private let session: VTCompressionSession
    private let output: (Data, Bool) -> Void
    private let failed: () -> Void

    init?(width: Int, height: Int, output: @escaping (Data, Bool) -> Void, failed: @escaping () -> Void = {}) {
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey: [CFString: Any]()
        ]
        var created: VTCompressionSession?
        let status = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264, encoderSpecification: nil, imageBufferAttributes: attributes as CFDictionary, compressedDataAllocator: nil, outputCallback: nil, refcon: nil, compressionSessionOut: &created)
        guard status == noErr, let created else { return nil }
        let properties: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: kCFBooleanTrue as Any,
            kVTCompressionPropertyKey_AllowFrameReordering: kCFBooleanFalse as Any,
            kVTCompressionPropertyKey_ProfileLevel: kVTProfileLevel_H264_Main_AutoLevel,
            kVTCompressionPropertyKey_AverageBitRate: Self.averageBitRate,
            kVTCompressionPropertyKey_DataRateLimits: [Self.peakBytesPerSecond, 1] as CFArray,
            kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration: Self.keyframeInterval
        ]
        for (key, value) in properties { VTSessionSetProperty(created, key: key, value: value as CFTypeRef) }
        guard VTCompressionSessionPrepareToEncodeFrames(created) == noErr else {
            VTCompressionSessionInvalidate(created)
            return nil
        }
        self.width = width
        self.height = height
        self.session = created
        self.output = output
        self.failed = failed
    }

    deinit {
        VTCompressionSessionInvalidate(session)
    }

    var pixelBufferPool: CVPixelBufferPool? {
        VTCompressionSessionGetPixelBufferPool(session)
    }

    @discardableResult
    func encode(_ buffer: CVPixelBuffer, at time: CFTimeInterval, keyframe: Bool) -> Bool {
        let options = keyframe ? [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue] as CFDictionary : nil
        let presentation = CMTime(seconds: time, preferredTimescale: 1_000_000)
        let status = VTCompressionSessionEncodeFrame(session, imageBuffer: buffer, presentationTimeStamp: presentation, duration: .invalid, frameProperties: options, infoFlagsOut: nil) { [output, failed] status, _, sample in
            guard status == noErr else { return failed() }
            guard let sample, let frame = AnnexB.frame(from: sample) else { return }
            output(frame.data, frame.keyframe)
        }
        guard status == noErr else { return false }
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: presentation)
        return true
    }
}

/// Drops delta frames that can't be decoded: before the first keyframe, and after the send queue backs up.
struct KeyframeGate: Equatable {

    static let queueLimit = 512 * 1024

    enum Decision: Equatable {
        case send
        case drop
        case dropAndRequestKeyframe
    }

    private(set) var waitingForKeyframe = true

    mutating func decide(keyframe: Bool, queuedBytes: Int) -> Decision {
        if queuedBytes >= Self.queueLimit {
            let request = keyframe || !waitingForKeyframe
            waitingForKeyframe = true
            return request ? .dropAndRequestKeyframe : .drop
        }
        if waitingForKeyframe && !keyframe { return .drop }
        waitingForKeyframe = false
        return .send
    }

    mutating func reset() {
        waitingForKeyframe = true
    }
}

protocol CaptureSource: AnyObject {
    var isAvailable: Bool { get }
    func start(frame: @escaping (CVPixelBuffer, CGImagePropertyOrientation) -> Void, started: @escaping () -> Void, failed: @escaping (Error) -> Void)
    func stop()
}

/// In-app screen capture. iOS asks the user to allow screen recording first.
final class ReplayKitSource: NSObject, CaptureSource, RPScreenRecorderDelegate {

    struct Unavailable: Error {}

    private let recorder = RPScreenRecorder.shared()
    private var failed: ((Error) -> Void)?

    var isAvailable: Bool { recorder.isAvailable }

    func start(frame: @escaping (CVPixelBuffer, CGImagePropertyOrientation) -> Void, started: @escaping () -> Void, failed: @escaping (Error) -> Void) {
        self.failed = failed
        recorder.delegate = self
        recorder.isMicrophoneEnabled = false
        recorder.startCapture(handler: { sample, type, error in
            if let error { return failed(error) }
            guard type == .video, let buffer = CMSampleBufferGetImageBuffer(sample) else { return }
            let raw = CMGetAttachment(sample, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber
            frame(buffer, raw.flatMap { CGImagePropertyOrientation(rawValue: $0.uint32Value) } ?? .up)
        }, completionHandler: { error in
            if let error { failed(error) } else { started() }
        })
    }

    func stop() {
        failed = nil
        recorder.stopCapture { _ in }
    }

    func screenRecorderDidChangeAvailability(_ screenRecorder: RPScreenRecorder) {
        guard !screenRecorder.isAvailable else { return }
        failed?(Unavailable())
    }

    static func reason(for error: Error) -> String {
        if error is Unavailable { return "unavailable" }
        let error = error as NSError
        if error.domain == RPRecordingErrorDomain, error.code == RPRecordingErrorCode.userDeclined.rawValue { return "denied" }
        return "\(error.domain) \(error.code)"
    }
}

#if targetEnvironment(simulator)
/// ReplayKit doesn't capture on the simulator, so the video path is fed from window snapshots there.
final class SimulatorCaptureSource: CaptureSource {

    static let framesPerSecond: Double = 15

    private weak var scene: UIWindowScene?
    private var timer: Timer?

    init(scene: UIWindowScene?) {
        self.scene = scene
    }

    var isAvailable: Bool { true }

    func start(frame: @escaping (CVPixelBuffer, CGImagePropertyOrientation) -> Void, started: @escaping () -> Void, failed: @escaping (Error) -> Void) {
        started()
        timer?.invalidate()
        let timer = Timer(timeInterval: 1 / Self.framesPerSecond, repeats: true) { [weak self] _ in
            guard let self, let scene = self.scene, let buffer = Self.draw(scene) else { return }
            frame(buffer, .up)
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private static func draw(_ scene: UIWindowScene) -> CVPixelBuffer? {
        let bounds = scene.coordinateSpace.bounds
        let width = Int(bounds.width), height = Int(bounds.height)
        var created: CVPixelBuffer?
        guard width > 0, height > 0, CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, [kCVPixelBufferIOSurfacePropertiesKey: [CFString: Any]()] as CFDictionary, &created) == kCVReturnSuccess, let buffer = created else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height, bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else { return nil }
        context.setFillColor(UIColor.black.cgColor)
        context.fill(bounds)
        context.translateBy(x: 0, y: bounds.height)
        context.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(context)
        // Like ReplayKit, the recording includes SideSeat's own banner.
        let overlays = scene.windows.filter { $0 is OverlayWindow && !$0.isHidden }
        for window in WindowSelection.capturable(scene.windows) + overlays {
            window.drawHierarchy(in: window.convert(window.bounds, to: scene.coordinateSpace), afterScreenUpdates: false)
        }
        UIGraphicsPopContext()
        return buffer
    }
}
#endif

struct KeyboardOverlay: Equatable {
    var rect: CGRect
    var dark: Bool
    var blurred: Bool
}

enum VideoFrameRenderer {

    static func outputSize(scene: CGSize, scale: CGFloat) -> CGSize {
        let even = { (value: CGFloat) in max(2, (value * scale / 2).rounded() * 2) }
        return CGSize(width: even(scene.width), height: even(scene.height))
    }

    static func content(of extent: CGRect, aspect: CGSize) -> CGRect {
        let fit = min(extent.width / aspect.width, extent.height / aspect.height)
        let size = CGSize(width: aspect.width * fit, height: aspect.height * fit)
        return CGRect(x: extent.midX - size.width / 2, y: extent.midY - size.height / 2, width: size.width, height: size.height)
    }

    /// The captured buffer cropped to the scene and scaled to `output`, with alert windows, masks and the keyboard drawn on top.
    /// `masks` and the keyboard are in top-left scene coordinates.
    static func image(from buffer: CVPixelBuffer, orientation: CGImagePropertyOrientation, scene: CGSize, output: CGSize, masks: [CGRect], overlay: CGImage? = nil, keyboard: KeyboardOverlay? = nil) -> CIImage {
        let source = CIImage(cvPixelBuffer: buffer).oriented(orientation)
        let content = content(of: source.extent, aspect: scene)
        var image = source
            .cropped(to: content)
            .transformed(by: CGAffineTransform(scaleX: output.width / content.width, y: output.height / content.height).translatedBy(x: -content.minX, y: -content.minY))
        if let overlay {
            let drawn = CIImage(cgImage: overlay)
            image = drawn.transformed(by: CGAffineTransform(scaleX: output.width / drawn.extent.width, y: output.height / drawn.extent.height)).composited(over: image)
        }
        let kx = output.width / scene.width
        let ky = output.height / scene.height
        for mask in masks {
            let rect = CGRect(x: mask.minX * kx, y: (scene.height - mask.maxY) * ky, width: mask.width * kx, height: mask.height * ky)
            let cover = MaskPlaceholder.image(size: rect.size).map {
                CIImage(cgImage: $0).transformed(by: CGAffineTransform(scaleX: rect.width / CGFloat($0.width), y: rect.height / CGFloat($0.height)).concatenating(CGAffineTransform(translationX: rect.minX, y: rect.minY)))
            } ?? CIImage(color: .black)
            image = cover.cropped(to: rect).composited(over: image)
        }
        if let keyboard {
            let rect = CGRect(x: keyboard.rect.minX * kx, y: (scene.height - keyboard.rect.maxY) * ky, width: keyboard.rect.width * kx, height: keyboard.rect.height * ky)
            if let drawn = KeyboardPlaceholder.image(size: rect.size, dark: keyboard.dark, blurred: keyboard.blurred)?.cgImage {
                image = CIImage(cgImage: drawn)
                    .transformed(by: CGAffineTransform(scaleX: rect.width / CGFloat(drawn.width), y: rect.height / CGFloat(drawn.height)).concatenating(CGAffineTransform(translationX: rect.minX, y: rect.minY)))
                    .cropped(to: rect)
                    .composited(over: image)
            }
        }
        return image.cropped(to: CGRect(origin: .zero, size: output))
    }
}

/// ReplayKit on a device leaves out app windows above the normal level, such as custom alerts, so they're drawn over each frame.
enum VideoOverlays {

    static func windows(_ windows: [UIWindow]) -> [UIWindow] {
        WindowSelection.capturable(windows).filter { $0.windowLevel > .normal }
    }

    static func image(in scene: UIWindowScene) -> CGImage? {
        let windows = windows(scene.windows)
        guard !windows.isEmpty else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(bounds: scene.coordinateSpace.bounds, format: format).image { _ in
            for window in windows {
                window.drawHierarchy(in: window.convert(window.bounds, to: scene.coordinateSpace), afterScreenUpdates: false)
            }
        }.cgImage
    }
}

enum VideoMasks {

    static func rects(in scene: UIWindowScene, rule: ((UIView) -> Bool)?) -> [CGRect] {
        WindowSelection.capturable(scene.windows).flatMap { window in
            MaskCollector.rects(in: window, rule: rule).map { window.convert($0, to: scene.coordinateSpace) }
        }
    }
}

/// Streams H.264 from a capture source. Masks are snapshotted on the main thread after each frame arrives and painted
/// over it, together with the previous snapshot, so a moving masked view stays covered. Falls back to JPEG through
/// `onFallback` when capture is refused, unavailable, silent or the encoder keeps failing.
final class VideoStreamer: FrameStreamer {

    static let maxFramesPerSecond: Double = 30
    static let maskPadding: CGFloat = 4
    static let firstFrameTimeout: TimeInterval = 3
    static let keyframeSpacing: TimeInterval = 0.5
    static let encoderFailuresBeforeFallback = 3
    static let overlayInterval: TimeInterval = 0.2

    var onFallback: ((String) -> Void)?
    var onRendered: ((CVPixelBuffer) -> Void)?
    private(set) var seq: UInt32 = 0

    private struct Frame {
        let buffer: CVPixelBuffer
        let orientation: CGImagePropertyOrientation
    }

    private struct Shared {
        var accepting = false
        var pending: Frame?
        var hopScheduled = false
        var busy = false
        var sawFrame = false
        var lastRenderAt: CFTimeInterval = 0
        var encoderFailures = 0
    }

    private struct Encoding {
        var encoder: H264Encoder?
        var lastRendered: CVPixelBuffer?
        var lastForcedAt: CFTimeInterval = -.infinity
        var forceScheduled = false
    }

    private let source: CaptureSource
    private let scheduler: Scheduler
    private let scale: CGFloat
    private let sceneSize: () -> CGSize?
    private let masks: () -> [CGRect]
    private let overlays: () -> CGImage?
    private let keyboard: () -> KeyboardOverlay?
    private let isAllowed: () -> Bool
    private let sendFrame: (Data, @escaping () -> Void) -> Void
    private let sendSceneSize: (CGSize) -> Void
    private let queue = DispatchQueue(label: "SideSeat.video", qos: .userInitiated)
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let lock = NSLock()
    private var shared = Shared()
    private var encoding = Encoding()

    private var running = false
    private var firstFrameTimer: ScheduledWork?
    private var gate = KeyframeGate()
    private var queuedBytes = 0
    private var lastSceneSize: CGSize?
    private var lastMasks: [CGRect] = []
    private var overlay: CGImage?
    private var overlayTimer: ScheduledWork?
    private var lastFrame: Frame?

    init(source: CaptureSource, scheduler: Scheduler = MainQueueScheduler(), scale: CGFloat = 1, sceneSize: @escaping () -> CGSize?, masks: @escaping () -> [CGRect], overlays: @escaping () -> CGImage? = { nil }, keyboard: @escaping () -> KeyboardOverlay? = { nil }, isAllowed: @escaping () -> Bool, sendFrame: @escaping (Data, @escaping () -> Void) -> Void, sendSceneSize: @escaping (CGSize) -> Void) {
        self.source = source
        self.scheduler = scheduler
        self.scale = scale
        self.sceneSize = sceneSize
        self.masks = masks
        self.overlays = overlays
        self.keyboard = keyboard
        self.isAllowed = isAllowed
        self.sendFrame = sendFrame
        self.sendSceneSize = sendSceneSize
    }

    func start() {
        guard !running else { return }
        running = true
        gate.reset()
        lastMasks = []
        overlay = nil
        lastFrame = nil
        withLock {
            shared = Shared()
            shared.accepting = true
        }
        refreshOverlay()
        guard source.isAvailable else { return fallBack("unavailable") }
        let source = self.source
        source.start(frame: { [weak self] buffer, orientation in
            self?.received(Frame(buffer: buffer, orientation: orientation))
        }, started: { [weak self] in
            DispatchQueue.main.async {
                guard let self, self.running else { return source.stop() }
                self.waitForFirstFrame()
            }
        }, failed: { [weak self] error in
            DispatchQueue.main.async { self?.fallBack(ReplayKitSource.reason(for: error)) }
        })
    }

    func stop() {
        guard running else { return }
        running = false
        firstFrameTimer?.cancel()
        overlayTimer?.cancel()
        overlayTimer = nil
        lastFrame = nil
        source.stop()
        withLock {
            shared.accepting = false
            shared.pending = nil
        }
        queue.async { [self] in encoding = Encoding() }
    }

    /// Video runs continuously, so there is nothing to hurry.
    func captureSoon(after delay: TimeInterval) {}

    /// Sends the scene size and a keyframe again, for example once streaming is allowed.
    func resendEverything() {
        lastSceneSize = nil
        gate.reset()
        withLock { if shared.pending != nil { scheduleRender() } }
        requestKeyframe()
    }

    func requestKeyframe() {
        queue.async { [weak self] in self?.forceKeyframe() }
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    private func refreshOverlay() {
        guard running else { return }
        let drawn = overlays()
        let changed = drawn != nil || overlay != nil
        overlay = drawn
        if changed, let frame = lastFrame {
            withLock {
                guard shared.accepting else { return }
                if shared.pending == nil { shared.pending = frame }
                scheduleRender()
            }
        }
        overlayTimer = scheduler.schedule(after: Self.overlayInterval) { [weak self] in self?.refreshOverlay() }
    }

    private func waitForFirstFrame() {
        guard running else { return }
        firstFrameTimer = scheduler.schedule(after: Self.firstFrameTimeout) { [weak self] in
            guard let self, !self.withLock({ self.shared.sawFrame }) else { return }
            self.fallBack("noFrames")
        }
    }

    private func fallBack(_ reason: String) {
        guard running else { return }
        stop()
        onFallback?(reason)
    }

    private func received(_ frame: Frame) {
        withLock {
            guard shared.accepting else { return }
            shared.sawFrame = true
            shared.pending = frame
            scheduleRender()
        }
    }

    private func scheduleRender() {
        guard !shared.hopScheduled, !shared.busy else { return }
        shared.hopScheduled = true
        let wait = max(0, shared.lastRenderAt + 1 / Self.maxFramesPerSecond - CACurrentMediaTime())
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in self?.snapshotAndRender() }
    }

    private func snapshotAndRender() {
        withLock { shared.hopScheduled = false }
        guard running, isAllowed(), let scene = sceneSize(), scene.width > 0, scene.height > 0 else { return }
        if scene != lastSceneSize {
            lastSceneSize = scene
            sendSceneSize(scene)
        }
        let current = masks().map { $0.insetBy(dx: -Self.maskPadding, dy: -Self.maskPadding) }
        let covering = lastMasks + current
        lastMasks = current
        let frame: Frame? = withLock {
            guard !shared.busy, let frame = shared.pending else { return nil }
            shared.pending = nil
            shared.busy = true
            shared.lastRenderAt = CACurrentMediaTime()
            return frame
        }
        guard let frame else { return }
        lastFrame = frame
        let overlay = self.overlay
        let keyboard = keyboard()
        queue.async { [weak self] in self?.render(frame, scene: scene, masks: covering, overlay: overlay, keyboard: keyboard) }
    }

    private func render(_ frame: Frame, scene: CGSize, masks: [CGRect], overlay: CGImage?, keyboard: KeyboardOverlay?) {
        autoreleasepool {
            let startedAt = CACurrentMediaTime()
            let output = VideoFrameRenderer.outputSize(scene: scene, scale: scale)
            var rendered: CVPixelBuffer?
            guard let encoder = makeEncoder(for: output), let pool = encoder.pixelBufferPool, CVPixelBufferPoolCreatePixelBuffer(nil, pool, &rendered) == kCVReturnSuccess, let rendered else {
                return encoderFailed()
            }
            let image = VideoFrameRenderer.image(from: frame.buffer, orientation: frame.orientation, scene: scene, output: output, masks: masks, overlay: overlay, keyboard: keyboard)
            context.render(image, to: rendered, bounds: image.extent, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
            encoding.lastRendered = rendered
            onRendered?(rendered)
            if !encoder.encode(rendered, at: startedAt, keyframe: false) { encoderFailed() }
        }
        withLock {
            shared.busy = false
            if shared.accepting, shared.pending != nil { scheduleRender() }
        }
    }

    private func makeEncoder(for output: CGSize) -> H264Encoder? {
        if let encoder = encoding.encoder, encoder.width == Int(output.width), encoder.height == Int(output.height) { return encoder }
        encoding.encoder = H264Encoder(width: Int(output.width), height: Int(output.height), output: { [weak self] data, keyframe in
            self?.withLock { self?.shared.encoderFailures = 0 }
            DispatchQueue.main.async { self?.encoded(data, keyframe: keyframe) }
        }, failed: { [weak self] in
            self?.queue.async { self?.encoderFailed() }
        })
        return encoding.encoder
    }

    private func encoderFailed() {
        encoding.encoder = nil
        let failures = withLock { () -> Int in
            shared.encoderFailures += 1
            return shared.encoderFailures
        }
        guard failures >= Self.encoderFailuresBeforeFallback else { return }
        DispatchQueue.main.async { [weak self] in self?.fallBack("encoder") }
    }

    private func forceKeyframe() {
        let wait = encoding.lastForcedAt + Self.keyframeSpacing - CACurrentMediaTime()
        guard wait <= 0 else {
            guard !encoding.forceScheduled else { return }
            encoding.forceScheduled = true
            queue.asyncAfter(deadline: .now() + wait) { [weak self] in
                self?.encoding.forceScheduled = false
                self?.forceKeyframe()
            }
            return
        }
        guard let encoder = encoding.encoder, let lastRendered = encoding.lastRendered else { return }
        let now = CACurrentMediaTime()
        encoding.lastForcedAt = now
        if !encoder.encode(lastRendered, at: now, keyframe: true) { encoderFailed() }
    }

    private func encoded(_ data: Data, keyframe: Bool) {
        guard running, isAllowed() else { return }
        switch gate.decide(keyframe: keyframe, queuedBytes: queuedBytes) {
        case .drop:
            return
        case .dropAndRequestKeyframe:
            return requestKeyframe()
        case .send:
            break
        }
        seq &+= 1
        let frame = VideoFrame.encode(seq: seq, h264: data, keyframe: keyframe)
        queuedBytes += frame.count
        sendFrame(frame) { [weak self] in self?.queuedBytes -= frame.count }
    }
}
