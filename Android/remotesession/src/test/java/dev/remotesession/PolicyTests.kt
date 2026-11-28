package dev.remotesession

import dev.remotesession.KeyboardLayout.Key
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolicyTests {

    @Test fun frameRateDropsWhenCaptureIsSlowAndRecovers() {
        val policy = FrameRatePolicy(maxFps = 2.0, budget = 0.05)
        assertEquals(listOf(false, false, true), List(3) { policy.record(0.08) })
        assertEquals(1.0, policy.fps, 0.0)
        assertEquals(List(9) { false } + true, List(10) { policy.record(0.01) })
        assertEquals(2.0, policy.fps, 0.0)
        repeat(30) { policy.record(0.5) }
        assertEquals(FrameRatePolicy.FLOOR, policy.fps, 0.0)
    }

    @Test fun h264FramesCarrySeqAndKeyframeFlag() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 0xAA.toByte())
        val frame = VideoFrame.encode(seq = 300, h264 = payload, keyframe = true)
        assertArrayEquals(byteArrayOf(1, 2, 0, 0, 1, 0x2C, 1), frame.copyOf(7))
        val decoded = VideoFrame.decode(frame)!!
        assertEquals(300, decoded.seq)
        assertEquals(true, decoded.keyframe)
        assertArrayEquals(payload, decoded.h264)
        assertEquals(false, VideoFrame.decode(VideoFrame.encode(seq = 301, h264 = payload, keyframe = false))!!.keyframe)
        assertEquals(-1, VideoFrame.decode(VideoFrame.encode(seq = -1, h264 = payload, keyframe = false))!!.seq)
    }

    @Test fun jpegFramesAreNeverReadAsVideo() {
        assertNull(VideoFrame.decode(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)))
        assertNull(VideoFrame.decode(byteArrayOf(1, 2, 0, 0, 0, 1)))
    }

    @Test fun waitsForTheFirstKeyframe() {
        val gate = KeyframeGate()
        assertEquals(KeyframeGate.Decision.DROP, gate.decide(keyframe = false, queuedBytes = 0))
        assertEquals(KeyframeGate.Decision.SEND, gate.decide(keyframe = true, queuedBytes = 0))
        assertEquals(KeyframeGate.Decision.SEND, gate.decide(keyframe = false, queuedBytes = 0))
    }

    @Test fun backpressureDropsUntilTheNextKeyframe() {
        val gate = KeyframeGate()
        gate.decide(keyframe = true, queuedBytes = 0)
        val full = KeyframeGate.QUEUE_LIMIT
        assertEquals(KeyframeGate.Decision.DROP_AND_REQUEST_KEYFRAME, gate.decide(keyframe = false, queuedBytes = full))
        assertEquals(KeyframeGate.Decision.DROP, gate.decide(keyframe = false, queuedBytes = full))
        assertEquals(KeyframeGate.Decision.DROP, gate.decide(keyframe = false, queuedBytes = 0))
        assertEquals(KeyframeGate.Decision.DROP_AND_REQUEST_KEYFRAME, gate.decide(keyframe = true, queuedBytes = full))
        assertEquals(KeyframeGate.Decision.SEND, gate.decide(keyframe = true, queuedBytes = 0))
        assertEquals(KeyframeGate.Decision.SEND, gate.decide(keyframe = false, queuedBytes = 0))
    }

    @Test fun resetWaitsAgain() {
        val gate = KeyframeGate()
        gate.decide(keyframe = true, queuedBytes = 0)
        gate.reset()
        assertEquals(KeyframeGate.Decision.DROP, gate.decide(keyframe = false, queuedBytes = 0))
    }

    @Test fun everyDrawnKeyMapsBackToItself() {
        val keys = KeyboardLayout.keys(1024f, 320f)
        val labelled = keys.filter { it.label != null }
        assertEquals(47, labelled.size)
        for (box in labelled) {
            assertEquals(Key.Character(box.label!!), KeyboardLayout.key((box.left + box.right) / 2, (box.top + box.bottom) / 2, 1024f, 320f))
        }
        assertEquals(1, keys.count { it.key == Key.Delete })
        assertEquals(1, keys.count { it.key == Key.Enter })
        assertEquals(1, keys.count { it.key == Key.Space })
        assertNull(KeyboardLayout.key(512f, 5f, 1024f, 320f))
    }
}
