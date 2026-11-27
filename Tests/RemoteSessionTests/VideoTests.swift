import Testing
import Foundation
import CoreImage
import CoreVideo
import UIKit
@testable import RemoteSession

final class ManualScheduler: Scheduler {

    private final class Item: ScheduledWork {
        let fireAt: TimeInterval
        let block: () -> Void
        var cancelled = false
        init(fireAt: TimeInterval, block: @escaping () -> Void) {
            self.fireAt = fireAt
            self.block = block
        }
        func cancel() { cancelled = true }
    }

    private(set) var now: TimeInterval = 0
    private var items: [Item] = []

    func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> ScheduledWork {
        let item = Item(fireAt: now + delay, block: block)
        items.append(item)
        return item
    }

    func advance(by interval: TimeInterval) {
        let target = now + interval
        while let next = items.filter({ !$0.cancelled && $0.fireAt <= target }).min(by: { $0.fireAt < $1.fireAt }) {
            now = next.fireAt
            next.cancelled = true
            next.block()
        }
        now = target
    }
}

@Suite struct VideoProtocolTests {

    @Test func h264FramesCarrySeqAndKeyframeFlag() {
        let payload = Data([0, 0, 0, 1, 0x65, 0xAA])
        let frame = VideoFrame.encode(seq: 300, h264: payload, keyframe: true)
        #expect([UInt8](frame.prefix(7)) == [1, 2, 0, 0, 1, 0x2C, 1])
        let decoded = VideoFrame.decode(frame)
        #expect(decoded?.seq == 300)
        #expect(decoded?.keyframe == true)
        #expect(decoded?.h264 == payload)
        #expect(VideoFrame.decode(VideoFrame.encode(seq: 301, h264: payload, keyframe: false))?.keyframe == false)
    }

    @Test func jpegFramesAreNeverReadAsVideo() {
        #expect(VideoFrame.decode(Data([0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0])) == nil)
        #expect(VideoFrame.decode(Data([1, 2, 0, 0, 0, 1])) == nil)
    }

    @Test func agentsSayWhichCodecsTheirBrowserDecodes() throws {
        #expect(try IncomingMessage(json: #"{"type":"agentJoined","agentId":"a","agentName":"Sara","video":["h264"]}"#) == .agentJoined(Agent(id: "a", name: "Sara", video: ["h264"])))
        #expect(try IncomingMessage(json: #"{"type":"agentJoined","agentId":"a","agentName":"Sara"}"#) == .agentJoined(Agent(id: "a", name: "Sara")))
        #expect(try IncomingMessage(json: #"{"type":"requestKeyframe"}"#) == .requestKeyframe)
    }

    @Test func keyframeRequestsReachTheStreamerOnlyWhileActive() {
        let transport = FakeTransport()
        let session = Session(transport: transport, allowsControl: true)
        var requests = 0
        session.onKeyframeRequest = { requests += 1 }
        session.start()
        transport.deliver(#"{"type":"session","code":"123456"}"#)
        transport.deliver(#"{"type":"requestKeyframe"}"#)
        transport.deliver(#"{"type":"agentJoined","agentId":"a1","agentName":"Sara","video":["h264"]}"#)
        session.grant(.view)
        transport.deliver(#"{"type":"requestKeyframe"}"#)
        #expect(requests == 1)
    }
}


@Suite struct AnnexBTests {

    @Test func lengthPrefixesBecomeStartCodesWithParameterSetsFirst() {
        let avcc = Data([0, 0, 0, 2, 0x65, 0x01, 0, 0, 0, 1, 0x06])
        let out = AnnexB.convert(avcc: avcc, parameterSets: [Data([0x67, 0x4D]), Data([0x68])])
        #expect(out == Data([0, 0, 0, 1, 0x67, 0x4D, 0, 0, 0, 1, 0x68, 0, 0, 0, 1, 0x65, 0x01, 0, 0, 0, 1, 0x06]))
    }

    @Test func truncatedInputIsRejected() {
        #expect(AnnexB.convert(avcc: Data([0, 0, 0, 5, 0x65])) == nil)
        #expect(AnnexB.convert(avcc: Data([0, 0, 0, 1, 0x65, 0, 0])) == nil)
        #expect(AnnexB.convert(avcc: Data([0, 0, 0, 0])) == nil)
    }
}

@Suite struct KeyframeGateTests {

    @Test func waitsForTheFirstKeyframe() {
        var gate = KeyframeGate()
        #expect(gate.decide(keyframe: false, queuedBytes: 0) == .drop)
        #expect(gate.decide(keyframe: true, queuedBytes: 0) == .send)
        #expect(gate.decide(keyframe: false, queuedBytes: 0) == .send)
    }

    @Test func backpressureDropsUntilTheNextKeyframe() {
        var gate = KeyframeGate()
        _ = gate.decide(keyframe: true, queuedBytes: 0)
        #expect(gate.decide(keyframe: false, queuedBytes: KeyframeGate.queueLimit) == .dropAndRequestKeyframe)
        #expect(gate.decide(keyframe: false, queuedBytes: KeyframeGate.queueLimit) == .drop)
        #expect(gate.decide(keyframe: false, queuedBytes: 0) == .drop)
        #expect(gate.decide(keyframe: true, queuedBytes: KeyframeGate.queueLimit) == .dropAndRequestKeyframe)
        #expect(gate.decide(keyframe: true, queuedBytes: 0) == .send)
        #expect(gate.decide(keyframe: false, queuedBytes: 0) == .send)
    }

    @Test func resetWaitsAgain() {
        var gate = KeyframeGate()
        _ = gate.decide(keyframe: true, queuedBytes: 0)
        gate.reset()
        #expect(gate.decide(keyframe: false, queuedBytes: 0) == .drop)
    }
}

private func leftHalfRed(width: Int, height: Int) -> CGImage {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = false
    return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
        UIColor.red.setFill()
        context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
    }.cgImage!
}

private func makeBuffer(width: Int, height: Int, fill: CIColor) -> CVPixelBuffer {
    var buffer: CVPixelBuffer?
    CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer)
    CIContext().render(CIImage(color: fill).cropped(to: CGRect(x: 0, y: 0, width: width, height: height)), to: buffer!)
    return buffer!
}

private func isMaskPlaceholder(_ pixel: [UInt8]) -> Bool {
    pixel.allSatisfy { (180...240).contains($0) }
}

private func pixel(_ buffer: CVPixelBuffer, x: Int, y: Int) -> [UInt8] {
    CVPixelBufferLockBaseAddress(buffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
    let row = CVPixelBufferGetBaseAddress(buffer)!.advanced(by: y * CVPixelBufferGetBytesPerRow(buffer)).assumingMemoryBound(to: UInt8.self)
    return (0..<3).map { row[x * 4 + $0] }
}

@Suite struct VideoFrameRendererTests {

    @Test func outputSizeIsEven() {
        #expect(VideoFrameRenderer.outputSize(scene: CGSize(width: 1024, height: 768), scale: 1) == CGSize(width: 1024, height: 768))
        #expect(VideoFrameRenderer.outputSize(scene: CGSize(width: 1194, height: 835), scale: 1) == CGSize(width: 1194, height: 836))
    }

    @Test func letterboxedBufferIsCroppedToTheScene() {
        let content = VideoFrameRenderer.content(of: CGRect(x: 0, y: 0, width: 2388, height: 1668), aspect: CGSize(width: 1024, height: 768))
        #expect(abs(content.width - 2224) < 0.5)
        #expect(content.height == 1668)
        #expect(abs(content.minX - 82) < 0.5)
    }

    @Test func masksScaleWithTheOutput() {
        let source = makeBuffer(width: 400, height: 300, fill: CIColor(red: 1, green: 1, blue: 1))
        let image = VideoFrameRenderer.image(from: source, orientation: .up, scene: CGSize(width: 200, height: 150), output: CGSize(width: 400, height: 300), masks: [CGRect(x: 10, y: 10, width: 40, height: 20)])
        let rendered = makeBuffer(width: 400, height: 300, fill: CIColor(red: 0, green: 1, blue: 0))
        CIContext().render(image, to: rendered, bounds: image.extent, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        #expect(isMaskPlaceholder(pixel(rendered, x: 90, y: 50)))
        #expect(pixel(rendered, x: 110, y: 50) == [255, 255, 255])
        #expect(pixel(rendered, x: 30, y: 70) == [255, 255, 255])
    }

    @Test func portraitBuffersAreTurnedUpright() {
        let source = makeBuffer(width: 100, height: 200, fill: CIColor(red: 0, green: 0, blue: 1))
        CIContext().render(CIImage(color: CIColor(red: 1, green: 0, blue: 0)).cropped(to: CGRect(x: 0, y: 100, width: 100, height: 100)), to: source, bounds: CGRect(x: 0, y: 0, width: 100, height: 200), colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        let image = VideoFrameRenderer.image(from: source, orientation: .right, scene: CGSize(width: 200, height: 100), output: CGSize(width: 200, height: 100), masks: [])
        let rendered = makeBuffer(width: 200, height: 100, fill: CIColor(red: 0, green: 1, blue: 0))
        CIContext().render(image, to: rendered, bounds: image.extent, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        #expect(image.extent == CGRect(x: 0, y: 0, width: 200, height: 100))
        #expect(pixel(rendered, x: 150, y: 50) == [0, 0, 255])
        #expect(pixel(rendered, x: 50, y: 50) == [255, 0, 0])
    }

    @Test func overlaysAreDrawnOverTheFrameAtTheOutputScale() {
        let source = makeBuffer(width: 400, height: 300, fill: CIColor(red: 1, green: 1, blue: 1))
        let image = VideoFrameRenderer.image(from: source, orientation: .up, scene: CGSize(width: 200, height: 150), output: CGSize(width: 400, height: 300), masks: [], overlay: leftHalfRed(width: 200, height: 150))
        let rendered = makeBuffer(width: 400, height: 300, fill: CIColor(red: 0, green: 1, blue: 0))
        CIContext().render(image, to: rendered, bounds: image.extent, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        #expect(pixel(rendered, x: 150, y: 150) == [0, 0, 255])
        #expect(pixel(rendered, x: 250, y: 150) == [255, 255, 255])
    }

    @MainActor @Test func onlyWindowsAboveTheAppAreRedrawn() {
        let app = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 240))
        let alert = UIWindow(frame: app.frame)
        alert.windowLevel = .alert
        let support = OverlayWindow(frame: app.frame)
        support.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 100)
        [app, alert, support].forEach { $0.isHidden = false }
        #expect(VideoOverlays.windows([app, alert, support]) == [alert])
        alert.isHidden = true
        #expect(VideoOverlays.windows([app, alert, support]).isEmpty)
    }

    @Test func nothingUnderAMaskShowsThrough() {
        let source = makeBuffer(width: 400, height: 300, fill: CIColor(red: 1, green: 0, blue: 0))
        let mask = CGRect(x: 30, y: 20, width: 140, height: 90)
        let image = VideoFrameRenderer.image(from: source, orientation: .up, scene: CGSize(width: 200, height: 150), output: CGSize(width: 400, height: 300), masks: [mask])
        let rendered = makeBuffer(width: 400, height: 300, fill: CIColor(red: 0, green: 1, blue: 0))
        CIContext().render(image, to: rendered, bounds: image.extent, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        let inside = stride(from: 62, to: 338, by: 7).flatMap { x in stride(from: 42, to: 218, by: 7).map { pixel(rendered, x: x, y: $0) } }
        #expect(inside.allSatisfy(isMaskPlaceholder))
        #expect(pixel(rendered, x: 20, y: 20) == [0, 0, 255])
    }

    @Test func theKeyboardIsDrawnWhereItIsOnEveryVideoPath() {
        let source = makeBuffer(width: 400, height: 300, fill: CIColor(red: 1, green: 0, blue: 0))
        let keyboard = KeyboardOverlay(rect: CGRect(x: 0, y: 100, width: 200, height: 50), dark: false, blurred: false)
        let image = VideoFrameRenderer.image(from: source, orientation: .up, scene: CGSize(width: 200, height: 150), output: CGSize(width: 400, height: 300), masks: [], keyboard: keyboard)
        let rendered = makeBuffer(width: 400, height: 300, fill: CIColor(red: 0, green: 1, blue: 0))
        CIContext().render(image, to: rendered, bounds: image.extent, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        let inside = stride(from: 10, to: 390, by: 9).flatMap { x in stride(from: 205, to: 295, by: 9).map { pixel(rendered, x: x, y: $0) } }
        #expect(!inside.contains([0, 0, 255]))
        #expect(pixel(rendered, x: 200, y: 100) == [0, 0, 255])
    }

    @Test func masksPaintThePlaceholderInTopLeftSceneCoordinates() {
        let source = makeBuffer(width: 400, height: 300, fill: CIColor(red: 1, green: 1, blue: 1))
        let scene = CGSize(width: 200, height: 150)
        let output = CGSize(width: 200, height: 150)
        let image = VideoFrameRenderer.image(from: source, orientation: .up, scene: scene, output: output, masks: [CGRect(x: 10, y: 10, width: 40, height: 20)])
        let rendered = makeBuffer(width: 200, height: 150, fill: CIColor(red: 0, green: 1, blue: 0))
        CIContext().render(image, to: rendered, bounds: image.extent, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        #expect(image.extent == CGRect(origin: .zero, size: output))
        #expect(isMaskPlaceholder(pixel(rendered, x: 20, y: 15)))
        #expect(pixel(rendered, x: 100, y: 15) == [255, 255, 255])
        #expect(pixel(rendered, x: 20, y: 140) == [255, 255, 255])
    }
}

@Suite struct H264EncoderTests {

    @Test func encodesAnnexBKeyframesWithParameterSets() async throws {
        final class Box: @unchecked Sendable {
            let lock = NSLock()
            var frames: [(Data, Bool)] = []
            var snapshot: [(Data, Bool)] {
                lock.lock()
                defer { lock.unlock() }
                return frames
            }
        }
        let box = Box()
        let encoder = try #require(H264Encoder(width: 320, height: 240) { data, keyframe in
            box.lock.lock()
            box.frames.append((data, keyframe))
            box.lock.unlock()
        })
        let buffer = makeBuffer(width: 320, height: 240, fill: CIColor(red: 0.2, green: 0.4, blue: 0.6))
        for index in 0..<4 {
            #expect(encoder.encode(buffer, at: Double(index) / 30, keyframe: index == 3))
        }
        for _ in 0..<50 where box.snapshot.count < 4 {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let frames = box.snapshot
        try #require(frames.count == 4)
        #expect(frames[0].1)
        #expect(frames[3].1)
        #expect(!frames[1].1)
        #expect([UInt8](frames[0].0.prefix(4)) == [0, 0, 0, 1])
        #expect(frames[0].0.dropFirst(4).first.map { $0 & 0x1F } == 7)
        #expect(frames[3].0.dropFirst(4).first.map { $0 & 0x1F } == 7)
        #expect([UInt8](frames[1].0.prefix(4)) == [0, 0, 0, 1])
        #expect(frames[1].0.dropFirst(4).first.map { $0 & 0x1F } != 7)
    }
}

private final class FakeCaptureSource: CaptureSource {
    var isAvailable = true
    var frame: ((CVPixelBuffer, CGImagePropertyOrientation) -> Void)?
    var started: (() -> Void)?
    var failed: ((Error) -> Void)?
    private(set) var stopCount = 0

    func start(frame: @escaping (CVPixelBuffer, CGImagePropertyOrientation) -> Void, started: @escaping () -> Void, failed: @escaping (Error) -> Void) {
        self.frame = frame
        self.started = started
        self.failed = failed
    }

    func stop() { stopCount += 1 }
}

@MainActor
@Suite struct VideoStreamerTests {

    private func makeStreamer(_ source: FakeCaptureSource, _ scheduler: ManualScheduler, masks: @escaping () -> [CGRect] = { [] }, overlays: @escaping () -> CGImage? = { nil }, isAllowed: @escaping () -> Bool = { true }, sent: @escaping (Data) -> Void = { _ in }) -> VideoStreamer {
        VideoStreamer(source: source, scheduler: scheduler, sceneSize: { CGSize(width: 320, height: 240) }, masks: masks, overlays: overlays, isAllowed: isAllowed, sendFrame: { data, done in
            sent(data)
            done()
        }, sendSceneSize: { _ in })
    }

    private func settle() async throws {
        try await Task.sleep(nanoseconds: 100_000_000)
    }

    private func until(_ condition: () -> Bool) async throws {
        for _ in 0..<50 where !condition() { try await settle() }
    }

    private func white() -> CVPixelBuffer {
        makeBuffer(width: 320, height: 240, fill: CIColor(red: 1, green: 1, blue: 1))
    }

    @Test func masksComeFromASnapshotTakenAfterTheFrameArrives() async throws {
        let source = FakeCaptureSource()
        var rects: [CGRect] = []
        var rendered: [CVPixelBuffer] = []
        let streamer = makeStreamer(source, ManualScheduler(), masks: { rects })
        streamer.onRendered = { buffer in DispatchQueue.main.async { rendered.append(buffer) } }
        streamer.start()
        source.frame?(white(), .up)
        rects = [CGRect(x: 10, y: 10, width: 40, height: 20)]
        try await until { !rendered.isEmpty }
        let frame = try #require(rendered.first)
        #expect(isMaskPlaceholder(pixel(frame, x: 20, y: 15)))
        #expect(pixel(frame, x: 200, y: 120) == [255, 255, 255])
        streamer.stop()
    }

    @Test func alertWindowsAreRedrawnOnTheLastFrameUntilTheyClose() async throws {
        let source = FakeCaptureSource()
        let scheduler = ManualScheduler()
        var alert: CGImage?
        var rendered: [CVPixelBuffer] = []
        let streamer = makeStreamer(source, scheduler, overlays: { alert })
        streamer.onRendered = { buffer in DispatchQueue.main.async { rendered.append(buffer) } }
        streamer.start()
        source.frame?(white(), .up)
        try await until { rendered.count == 1 }
        #expect(pixel(rendered[0], x: 40, y: 120) == [255, 255, 255])

        alert = leftHalfRed(width: 320, height: 240)
        scheduler.advance(by: VideoStreamer.overlayInterval)
        try await until { rendered.count == 2 }
        #expect(pixel(rendered[1], x: 40, y: 120) == [0, 0, 255])
        #expect(pixel(rendered[1], x: 280, y: 120) == [255, 255, 255])

        alert = nil
        scheduler.advance(by: VideoStreamer.overlayInterval)
        try await until { rendered.count == 3 }
        #expect(pixel(rendered[2], x: 40, y: 120) == [255, 255, 255])

        scheduler.advance(by: VideoStreamer.overlayInterval)
        try await settle()
        try await settle()
        #expect(rendered.count == 3)
        streamer.stop()
    }

    @Test func theLastSnapshotStillCoversTheNextFrame() async throws {
        let source = FakeCaptureSource()
        var rects = [CGRect(x: 10, y: 10, width: 40, height: 20)]
        var rendered: [CVPixelBuffer] = []
        let streamer = makeStreamer(source, ManualScheduler(), masks: { rects })
        streamer.onRendered = { buffer in DispatchQueue.main.async { rendered.append(buffer) } }
        streamer.start()
        source.frame?(white(), .up)
        try await until { rendered.count == 1 }
        rects = [CGRect(x: 200, y: 150, width: 40, height: 20)]
        source.frame?(white(), .up)
        try await until { rendered.count == 2 }
        let second = try #require(rendered.last)
        #expect(isMaskPlaceholder(pixel(second, x: 20, y: 15)))
        #expect(isMaskPlaceholder(pixel(second, x: 210, y: 160)))
        #expect(pixel(second, x: 120, y: 100) == [255, 255, 255])
        streamer.stop()
    }

    @Test func nothingIsRenderedOrSentAfterStop() async throws {
        let source = FakeCaptureSource()
        var sent: [Data] = []
        var rendered = 0
        let streamer = makeStreamer(source, ManualScheduler()) { sent.append($0) }
        streamer.onRendered = { _ in DispatchQueue.main.async { rendered += 1 } }
        streamer.start()
        source.frame?(white(), .up)
        streamer.stop()
        source.frame?(white(), .up)
        try await settle()
        try await settle()
        #expect(rendered == 0)
        #expect(sent.isEmpty)
    }

    @Test func nothingIsSentUntilStreamingIsAllowed() async throws {
        let source = FakeCaptureSource()
        var allowed = false
        var sent: [Data] = []
        let streamer = makeStreamer(source, ManualScheduler(), isAllowed: { allowed }) { sent.append($0) }
        streamer.start()
        source.frame?(white(), .up)
        try await settle()
        try await settle()
        #expect(sent.isEmpty)
        allowed = true
        streamer.resendEverything()
        try await until { !sent.isEmpty }
        #expect(sent.first.flatMap(VideoFrame.decode)?.keyframe == true)
        streamer.stop()
    }

    @Test func keyframeRequestsAreCoalescedAndDeferred() async throws {
        let source = FakeCaptureSource()
        var sent: [Data] = []
        let streamer = makeStreamer(source, ManualScheduler()) { sent.append($0) }
        let keyframes = { sent.compactMap(VideoFrame.decode).filter(\.keyframe).count }
        streamer.start()
        source.frame?(white(), .up)
        try await until { keyframes() == 1 }
        streamer.requestKeyframe()
        streamer.requestKeyframe()
        streamer.requestKeyframe()
        try await until { keyframes() == 2 }
        try await settle()
        #expect(keyframes() == 2)
        try await until { keyframes() == 3 }
        #expect(keyframes() == 3)
        streamer.stop()
    }

    @Test func fallsBackWhenReplayKitIsUnavailable() {
        let source = FakeCaptureSource()
        source.isAvailable = false
        let streamer = makeStreamer(source, ManualScheduler())
        var reasons: [String] = []
        streamer.onFallback = { reasons.append($0) }
        streamer.start()
        #expect(reasons == ["unavailable"])
        #expect(source.frame == nil)
    }

    @Test func fallsBackWhenTheUserDeclines() async throws {
        let source = FakeCaptureSource()
        let streamer = makeStreamer(source, ManualScheduler())
        var reasons: [String] = []
        streamer.onFallback = { reasons.append($0) }
        streamer.start()
        source.failed?(NSError(domain: "com.apple.ReplayKit.RPRecordingErrorDomain", code: -5801))
        source.failed?(NSError(domain: "com.apple.ReplayKit.RPRecordingErrorDomain", code: -5801))
        try await settle()
        #expect(reasons == ["denied"])
        #expect(source.stopCount == 1)
    }

    @Test func waitsForThePromptThenFallsBackWithoutFrames() async throws {
        let source = FakeCaptureSource()
        let scheduler = ManualScheduler()
        let streamer = makeStreamer(source, scheduler)
        var reasons: [String] = []
        streamer.onFallback = { reasons.append($0) }
        streamer.start()
        scheduler.advance(by: 30)
        #expect(reasons.isEmpty)
        source.started?()
        try await settle()
        scheduler.advance(by: VideoStreamer.firstFrameTimeout)
        #expect(reasons == ["noFrames"])
    }

    @Test func firstFrameIsSentAsAKeyframe() async throws {
        let source = FakeCaptureSource()
        let scheduler = ManualScheduler()
        var sent: [Data] = []
        let streamer = makeStreamer(source, scheduler) { sent.append($0) }
        var reasons: [String] = []
        streamer.onFallback = { reasons.append($0) }
        streamer.start()
        source.started?()
        try await settle()
        source.frame?(makeBuffer(width: 640, height: 480, fill: CIColor(red: 1, green: 0, blue: 0)), .up)
        for _ in 0..<50 where sent.isEmpty { try await settle() }
        scheduler.advance(by: VideoStreamer.firstFrameTimeout)
        let first = try #require(sent.first.flatMap(VideoFrame.decode))
        #expect(first.seq == 1)
        #expect(first.keyframe)
        #expect(reasons.isEmpty)
        streamer.stop()
        #expect(source.stopCount == 1)
    }
}
