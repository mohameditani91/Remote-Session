package dev.remotesession

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.View
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal class FrameRatePolicy(private val maxFps: Double, private val budget: Double) {

    companion object {
        const val FLOOR = 0.5
        const val SLOW_FRAMES_TO_SLOW_DOWN = 3
        const val FAST_FRAMES_TO_SPEED_UP = 10
    }

    var fps = maxFps
        private set
    private var slowStreak = 0
    private var fastStreak = 0

    /** Records how long a capture took on the main thread, in seconds. Returns true when the rate changed. */
    fun record(duration: Double): Boolean {
        val previous = fps
        when {
            duration > budget -> {
                fastStreak = 0
                if (++slowStreak >= SLOW_FRAMES_TO_SLOW_DOWN) {
                    slowStreak = 0
                    fps = maxOf(fps / 2, minOf(FLOOR, maxFps))
                }
            }
            duration < budget * 0.6 -> {
                slowStreak = 0
                if (++fastStreak >= FAST_FRAMES_TO_SPEED_UP) {
                    fastStreak = 0
                    fps = minOf(fps * 2, maxFps)
                }
            }
            else -> {
                slowStreak = 0
                fastStreak = 0
            }
        }
        return fps != previous
    }
}

/** Sends frames to the agent: JPEG stills ([StillStreamer]) or H.264 video ([VideoStreamer]). */
internal interface FrameStreamer {
    fun start()
    fun stop()
    fun captureSoon(delayMs: Long)
    fun requestKeyframe()
}

/** Everything one frame shows, read on the main thread just before drawing it. */
internal class Frame(
    val scene: Scene,
    val windows: List<AppWindow>,
    val masks: List<RectF>,
    val keyboard: RectF?,
    val keyboardLocked: Boolean,
) {
    /** One pixel per dp at scale 1, like iOS points. Even, for the video encoder. */
    fun outputSize(scale: Float): Pair<Int, Int> {
        fun even(pixels: Int) = maxOf(2, (pixels / scene.density * scale / 2).roundToInt() * 2)
        return even(scene.width) to even(scene.height)
    }

    /**
     * Draws the app's windows, then the masks and the keyboard over them, into a hardware canvas. Views record
     * by reference to their existing display lists, so this costs the main thread little.
     */
    fun draw(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.scale(width / scene.width.toFloat(), height / scene.height.toFloat())
        for (window in windows) {
            canvas.save()
            canvas.translate(window.left.toFloat(), window.top.toFloat())
            window.root.draw(canvas)
            canvas.restore()
        }
        masks.forEach { Placeholders.drawMask(canvas, it, scene.density) }
        keyboard?.let { Placeholders.drawKeyboard(canvas, it, scene.isDark, keyboardLocked, scene.density) }
        canvas.restore()
    }

    companion object {
        fun read(windows: AppWindows, rule: ((View) -> Boolean)?): Frame? {
            val scene = windows.scene() ?: return null
            val list = windows.list(scene)
            val masks = Masks.rects(list, rule, scene.density)
            val keyboard = Ime.rect(list)
            return Frame(scene, list, masks, keyboard, keyboard != null && Ime.isSensitive(list, masks, rule))
        }
    }
}

/** Renders recorded frames into a surface on the render thread: an ImageReader for stills, the encoder for video. */
internal class SurfaceRenderer(surface: Surface, val width: Int, val height: Int, density: Float) {

    private val node = RenderNode("RemoteSession").also { it.setPosition(0, 0, width, height) }
    private val renderer = HardwareRenderer().apply {
        setSurface(surface)
        setLightSourceGeometry(width / 2f, 0f, 600 * density, 800 * density)
        setLightSourceAlpha(0.039f, 0.19f)
        start()
    }

    fun draw(frame: Frame) {
        val canvas = node.beginRecording(width, height)
        try {
            frame.draw(canvas, width, height)
        } finally {
            node.endRecording()
        }
        renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(false).syncAndDraw()
    }

    fun destroy() {
        renderer.destroy()
        node.discardDisplayList()
    }
}

internal class StillStreamer(
    private val windows: AppWindows,
    private val session: Session,
    private val configuration: RemoteSession.Configuration,
) : FrameStreamer {

    companion object {
        /** Lets the screen settle after an agent input or a user touch, then sends it without waiting for the next tick. */
        const val INPUT_CAPTURE_DELAY_MS = 200L
        const val USER_TOUCH_CAPTURE_DELAY_MS = 600L
        private const val QUEUE_LIMIT = 1024 * 1024L
        private const val STUCK_MS = 2000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val policy = FrameRatePolicy(configuration.framesPerSecond, configuration.mainThreadBudget)
    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var renderer: SurfaceRenderer? = null
    private var running = false
    private var inFlightSince = 0L
    private var lastFrame: ByteArray? = null
    private var lastScreen: Pair<Int, Int>? = null
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, (1000 / policy.fps).toLong())
        }
    }
    private val soon = Runnable { tick(force = true) }

    override fun start() {
        running = true
        thread = HandlerThread("RemoteSession.stills").apply { start() }
        main.post(ticker)
    }

    override fun stop() {
        running = false
        main.removeCallbacks(ticker)
        main.removeCallbacks(soon)
        release()
        thread?.quitSafely()
        thread = null
    }

    override fun captureSoon(delayMs: Long) {
        if (!running) return
        main.removeCallbacks(soon)
        main.postDelayed(soon, delayMs)
    }

    override fun requestKeyframe() {}

    private fun release() {
        renderer?.destroy()
        reader?.close()
        renderer = null
        reader = null
        inFlightSince = 0
    }

    private fun tick(force: Boolean = false) {
        if (!running || !session.isStreaming) return
        val now = SystemClock.uptimeMillis()
        if (inFlightSince != 0L && now - inFlightSince < STUCK_MS) return
        if (session.queuedBytes > QUEUE_LIMIT) return
        if (!force && !windows.dirty && lastFrame != null) return
        val started = System.nanoTime()
        val frame = Frame.read(windows, configuration.maskRule) ?: return
        windows.dirty = false
        val screen = frame.scene.width to frame.scene.height
        if (screen != lastScreen) {
            lastScreen = screen
            lastFrame = null
            session.sendScreen(frame.scene.width / frame.scene.density.toDouble(), frame.scene.height / frame.scene.density.toDouble())
        }
        val (width, height) = frame.outputSize(configuration.scale)
        val renderer = renderer?.takeIf { it.width == width && it.height == height } ?: run {
            release()
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            reader.setOnImageAvailableListener(::encode, Handler(thread!!.looper))
            this.reader = reader
            SurfaceRenderer(reader.surface, width, height, frame.scene.density).also { this.renderer = it }
        }
        inFlightSince = now
        renderer.draw(frame)
        if (policy.record((System.nanoTime() - started) / 1e9)) {
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, (1000 / policy.fps).toLong())
        }
    }

    /** On the stills thread: copies the rendered image out and compresses it. */
    private fun encode(reader: ImageReader) {
        val jpeg = runCatching {
            reader.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val stride = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(stride, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (stride == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, configuration.jpegQuality, it) }.toByteArray()
            }
        }.getOrNull()
        main.post {
            inFlightSince = 0
            if (jpeg == null || !running || !session.isStreaming || jpeg.contentEquals(lastFrame)) return@post
            lastFrame = jpeg
            session.sendFrame(jpeg)
        }
    }
}
