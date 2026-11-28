package dev.remotesession

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

/**
 * Binary frame layout for video. JPEG frames stay bare JPEG, which always starts with 0xFF.
 *
 *     byte 0: version (1)   byte 1: kind (2 = H.264 Annex-B)   bytes 2–5: seq, big-endian
 *     byte 6: flags (bit 0 = keyframe)   bytes 7…: one access unit, SPS and PPS in front of every keyframe
 */
internal object VideoFrame {

    const val VERSION: Byte = 1
    const val H264_KIND: Byte = 2
    const val HEADER_LENGTH = 7

    fun encode(seq: Int, h264: ByteArray, keyframe: Boolean): ByteArray {
        val frame = ByteArray(HEADER_LENGTH + h264.size)
        frame[0] = VERSION
        frame[1] = H264_KIND
        for (i in 0..3) frame[2 + i] = (seq ushr (24 - 8 * i)).toByte()
        frame[6] = if (keyframe) 1 else 0
        h264.copyInto(frame, HEADER_LENGTH)
        return frame
    }

    data class Decoded(val seq: Int, val keyframe: Boolean, val h264: ByteArray)

    fun decode(data: ByteArray): Decoded? {
        if (data.size < HEADER_LENGTH || data[0] != VERSION || data[1] != H264_KIND) return null
        val seq = (2..5).fold(0) { acc, i -> (acc shl 8) or (data[i].toInt() and 0xFF) }
        return Decoded(seq, data[6].toInt() and 1 == 1, data.copyOfRange(HEADER_LENGTH, data.size))
    }
}

/** Drops delta frames that can't be decoded: before the first keyframe, and after the send queue backs up. */
internal class KeyframeGate {

    companion object {
        const val QUEUE_LIMIT = 512 * 1024L
    }

    enum class Decision { SEND, DROP, DROP_AND_REQUEST_KEYFRAME }

    var waitingForKeyframe = true
        private set

    fun decide(keyframe: Boolean, queuedBytes: Long): Decision {
        if (queuedBytes >= QUEUE_LIMIT) {
            val request = keyframe || !waitingForKeyframe
            waitingForKeyframe = true
            return if (request) Decision.DROP_AND_REQUEST_KEYFRAME else Decision.DROP
        }
        if (waitingForKeyframe && !keyframe) return Decision.DROP
        waitingForKeyframe = false
        return Decision.SEND
    }

    fun reset() {
        waitingForKeyframe = true
    }
}

/**
 * Streams H.264 of the same masked frames the stills path draws, rendered straight into the encoder's input
 * surface. No screen recording, so no system prompt, and nothing outside the app's own windows. Draws only when an
 * app window drew. Falls back to stills through [onFallback] when the encoder can't start or stays silent.
 */
internal class VideoStreamer(
    private val windows: AppWindows,
    private val session: Session,
    private val configuration: RemoteSession.Configuration,
) : FrameStreamer {

    companion object {
        const val MAX_FPS = 30.0
        const val BIT_RATE = 1_800_000
        const val KEYFRAME_INTERVAL_S = 2
        const val FIRST_OUTPUT_TIMEOUT_MS = 3000L
        const val KEYFRAME_SPACING_MS = 500L
        const val ENCODER_FAILURES_BEFORE_FALLBACK = 3

        /** Main-thread seconds per frame before the rate halves. */
        const val BUDGET = 0.012

        /**
         * Frames handed to the encoder that it hasn't returned yet. The encoder's surface is drawn on the app's own
         * render thread, so a slow encoder would stall the app's UI; past this, frames wait.
         */
        const val MAX_IN_FLIGHT = 2
        const val IN_FLIGHT_TIMEOUT_MS = 1000L
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    var onFallback: ((String) -> Unit)? = null

    private class Encoder(val codec: MediaCodec, val surface: Surface, val renderer: SurfaceRenderer) {
        val width get() = renderer.width
        val height get() = renderer.height

        fun release() {
            renderer.destroy()
            runCatching { codec.stop() }
            codec.release()
            surface.release()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val policy = FrameRatePolicy(MAX_FPS, BUDGET)
    private val gate = KeyframeGate()
    private var thread: HandlerThread? = null
    private var encoder: Encoder? = null
    private var generation = 0
    private var running = false
    private var seq = 0
    private var lastScreen: Pair<Int, Int>? = null
    private var forceFrame = false
    private var lastKeyframeAt = 0L
    private var keyframeScheduled = false
    private var failures = 0
    private var firstDrawAt = 0L
    private var sawOutput = false
    private var inFlight = 0
    private var lastDrawAt = 0L
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (running) main.postDelayed(this, (1000 / policy.fps).toLong())
        }
    }

    override fun start() {
        running = true
        gate.reset()
        thread = HandlerThread("RemoteSession.video").apply { start() }
        main.post(ticker)
    }

    override fun stop() {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        releaseEncoder()
        thread?.quitSafely()
        thread = null
    }

    /** Video follows every draw already. */
    override fun captureSoon(delayMs: Long) {}

    /** Asks the encoder for a keyframe and draws a frame for it to encode, at most every half second. */
    override fun requestKeyframe() {
        val wait = lastKeyframeAt + KEYFRAME_SPACING_MS - SystemClock.uptimeMillis()
        if (wait > 0) {
            if (!keyframeScheduled) {
                keyframeScheduled = true
                main.postDelayed({
                    keyframeScheduled = false
                    requestKeyframe()
                }, wait)
            }
            return
        }
        lastKeyframeAt = SystemClock.uptimeMillis()
        encoder?.let { runCatching { it.codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }
        gate.reset()
        forceFrame = true
    }

    private fun tick() {
        if (!running || !session.isStreaming) return
        if (!sawOutput && firstDrawAt != 0L && SystemClock.uptimeMillis() - firstDrawAt > FIRST_OUTPUT_TIMEOUT_MS) return fallBack("noFrames")
        if (!windows.dirty && !forceFrame && encoder != null) return
        if (inFlight >= MAX_IN_FLIGHT && SystemClock.uptimeMillis() - lastDrawAt < IN_FLIGHT_TIMEOUT_MS) return
        val started = System.nanoTime()
        val frame = Frame.read(windows, configuration.maskRule) ?: return
        windows.dirty = false
        forceFrame = false
        val screen = frame.scene.width to frame.scene.height
        if (screen != lastScreen) {
            lastScreen = screen
            session.sendScreen(frame.scene.width / frame.scene.density.toDouble(), frame.scene.height / frame.scene.density.toDouble())
        }
        val (width, height) = frame.outputSize(configuration.scale)
        val encoder = encoder?.takeIf { it.width == aligned(it.codec, width, true) && it.height == aligned(it.codec, height, false) }
            ?: makeEncoder(width, height, frame.scene.density) ?: return
        lastDrawAt = SystemClock.uptimeMillis()
        if (firstDrawAt == 0L) firstDrawAt = lastDrawAt
        inFlight += 1
        encoder.renderer.draw(frame)
        policy.record((System.nanoTime() - started) / 1e9)
    }

    private fun aligned(codec: MediaCodec, size: Int, horizontal: Boolean): Int {
        val capabilities = runCatching { codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities }.getOrNull() ?: return size
        val alignment = if (horizontal) capabilities.widthAlignment else capabilities.heightAlignment
        val range = if (horizontal) capabilities.supportedWidths else capabilities.supportedHeights
        return (size.coerceAtMost(range.upper) / alignment * alignment).coerceAtLeast(alignment)
    }

    private fun makeEncoder(width: Int, height: Int, density: Float): Encoder? {
        releaseEncoder()
        val looper = thread?.looper ?: return null
        val codec = runCatching { MediaCodec.createEncoderByType(MIME) }.getOrElse { return encoderFailed() }
        return runCatching {
            val w = aligned(codec, width, true)
            val h = aligned(codec, height, false)
            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, MAX_FPS.toInt())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            val current = ++generation
            // SPS and PPS, from the codec-config buffer or the output format, whichever the encoder sends.
            val config = AtomicReference<ByteArray?>()
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val bytes = runCatching {
                        val buffer = codec.getOutputBuffer(index) ?: return@runCatching null
                        ByteArray(info.size).also {
                            buffer.position(info.offset)
                            buffer.get(it)
                        }
                    }.getOrNull()
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    if (bytes == null || bytes.isEmpty()) return
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        config.set(bytes)
                        return
                    }
                    val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    // The console builds its decoder from the SPS, so every keyframe carries it.
                    val data = config.get()?.takeIf { keyframe && !AnnexB.startsWithSps(bytes) }?.let { it + bytes } ?: bytes
                    main.post { if (current == generation) encoded(data, keyframe) }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    val sps = format.getByteBuffer("csd-0") ?: return
                    val pps = format.getByteBuffer("csd-1")
                    config.compareAndSet(null, sps.bytes() + (pps?.bytes() ?: ByteArray(0)))
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    main.post { if (current == generation) encoderFailed() }
                }
            }, Handler(looper))
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            Encoder(codec, surface, SurfaceRenderer(surface, w, h, density))
        }.onSuccess { encoder = it }.getOrElse {
            codec.release()
            encoderFailed()
        }
    }

    private fun releaseEncoder() {
        generation++
        encoder?.release()
        encoder = null
        firstDrawAt = 0
        sawOutput = false
        inFlight = 0
        gate.reset()
    }

    private fun encoderFailed(): Encoder? {
        releaseEncoder()
        if (++failures >= ENCODER_FAILURES_BEFORE_FALLBACK) fallBack("encoder") else forceFrame = true
        return null
    }

    private fun fallBack(reason: String) {
        if (!running) return
        stop()
        onFallback?.invoke(reason)
    }

    private fun encoded(data: ByteArray, keyframe: Boolean) {
        if (!running || !session.isStreaming) return
        sawOutput = true
        failures = 0
        // Any output means the encoder is keeping up; hardware encoders may drop frames without returning them.
        inFlight = 0
        when (gate.decide(keyframe, session.queuedBytes)) {
            KeyframeGate.Decision.DROP -> return
            KeyframeGate.Decision.DROP_AND_REQUEST_KEYFRAME -> return requestKeyframe()
            KeyframeGate.Decision.SEND -> {}
        }
        seq += 1
        session.sendFrame(VideoFrame.encode(seq, data, keyframe))
    }

    private fun java.nio.ByteBuffer.bytes() = ByteArray(remaining()).also { duplicate().get(it) }
}

internal object AnnexB {
    /** True when the access unit opens with a sequence parameter set (NAL type 7). */
    fun startsWithSps(data: ByteArray): Boolean {
        val offset = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte() -> 3
            else -> return false
        }
        return data[offset].toInt() and 0x1F == 7
    }
}
