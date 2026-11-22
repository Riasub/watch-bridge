package dev.watchbridge.galaxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun smallPayloadIsOneLastFrame() {
        val frames = Protocol.frames(7, "hi".toByteArray(), 20)
        assertEquals(1, frames.size)
        assertEquals(7.toByte(), frames[0][0])
        assertEquals(0x80.toByte(), frames[0][1]) // seq 0 + 마지막 표시
        assertArrayEquals("hi".toByteArray(), frames[0].copyOfRange(2, frames[0].size))
    }

    @Test
    fun largePayloadIsSplitAndRebuilds() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val frames = Protocol.frames(300, payload, 20) // msgId 는 1바이트로 잘린다
        assertEquals((1000 + 17) / 18, frames.size)
        assertTrue(frames.all { it.size <= 20 })
        assertTrue(frames.all { it[0] == (300 and 0xFF).toByte() })
        frames.forEachIndexed { i, f ->
            val last = i == frames.size - 1
            assertEquals(i or (if (last) 0x80 else 0), f[1].toInt() and 0xFF)
        }
        val rebuilt = frames.fold(ByteArray(0)) { acc, f -> acc + f.copyOfRange(2, f.size) }
        assertArrayEquals(payload, rebuilt)
    }

    @Test
    fun emptyPayloadStillSendsOneFrame() {
        val frames = Protocol.frames(1, ByteArray(0), 20)
        assertEquals(1, frames.size)
        assertEquals(2, frames[0].size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyFramesIsRejected() {
        Protocol.frames(1, ByteArray(5000), 20)
    }
}
