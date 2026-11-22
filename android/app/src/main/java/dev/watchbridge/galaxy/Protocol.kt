package dev.watchbridge.galaxy

import java.util.UUID

/**
 * 갤럭시 <-> 애플워치 BLE 규약. 자세한 설명은 docs/PROTOCOL.md.
 * 워치 쪽 상수는 watch/WatchBridge/Protocol.swift 에 같은 값으로 있어야 한다.
 */
object Protocol {
    val SERVICE: UUID = UUID.fromString("5F1D0001-8C3B-4C2A-9A1E-2B7F6D3A0C11")

    /** 갤럭시 -> 워치. NOTIFY. */
    val NOTIFY: UUID = UUID.fromString("5F1D0002-8C3B-4C2A-9A1E-2B7F6D3A0C11")

    /** 워치 -> 갤럭시. WRITE. */
    val CONTROL: UUID = UUID.fromString("5F1D0003-8C3B-4C2A-9A1E-2B7F6D3A0C11")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HEADER_BYTES = 2
    private const val LAST_FLAG = 0x80
    private const val MAX_FRAMES = 0x80

    /**
     * payload 를 BLE 한 번에 보낼 수 있는 크기(maxFrame, 헤더 포함)로 나눈다.
     * 프레임 = [msgId][seq | 마지막이면 0x80] + 조각.
     */
    fun frames(msgId: Int, payload: ByteArray, maxFrame: Int): List<ByteArray> {
        val chunk = (maxFrame - HEADER_BYTES).coerceAtLeast(1)
        val total = if (payload.isEmpty()) 1 else (payload.size + chunk - 1) / chunk
        require(total <= MAX_FRAMES) { "payload too large: ${payload.size} bytes" }
        return List(total) { i ->
            val from = i * chunk
            val to = minOf(from + chunk, payload.size)
            val seq = i or (if (i == total - 1) LAST_FLAG else 0)
            byteArrayOf((msgId and 0xFF).toByte(), seq.toByte()) + payload.copyOfRange(from, to)
        }
    }
}
