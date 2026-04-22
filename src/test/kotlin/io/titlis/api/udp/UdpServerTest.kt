package io.titlis.api.udp

import io.mockk.mockk
import io.titlis.api.config.UdpConfig
import java.net.DatagramPacket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class UdpServerTest {

    @Test
    fun `extractPayload resets packet length for the next receive`() {
        val server = UdpServer(
            config = UdpConfig(port = 8125, bufferSize = 1024, workers = 1, queueSize = 16),
            router = mockk(relaxed = true),
        )
        val backingBuffer = ByteArray(1024)
        val packet = DatagramPacket(backingBuffer, backingBuffer.size)
        val payload = """{"v":1,"t":"scorecard_evaluated"}""".toByteArray()

        System.arraycopy(payload, 0, backingBuffer, 0, payload.size)
        packet.length = payload.size

        val extracted = server.extractPayload(packet, backingBuffer.size)

        assertContentEquals(payload, extracted)
        assertEquals(backingBuffer.size, packet.length)
    }

    @Test
    fun `extractPayload preserves a larger envelope after a shorter packet`() {
        val server = UdpServer(
            config = UdpConfig(port = 8125, bufferSize = 1024, workers = 1, queueSize = 16),
            router = mockk(relaxed = true),
        )
        val backingBuffer = ByteArray(1024)
        val packet = DatagramPacket(backingBuffer, 48)

        // Simulate a previous short datagram that left a reduced packet length.
        packet.length = 48

        val largerPayload = """
            {"v":1,"t":"slo_reconciled","ts":1710000000000,"api_key":"tls_k_test","data":{"slo_name":"manual-test-slo","cluster":"titlis-preprod"}}
        """.trimIndent().toByteArray()
        System.arraycopy(largerPayload, 0, backingBuffer, 0, largerPayload.size)
        packet.length = largerPayload.size

        val extracted = server.extractPayload(packet, backingBuffer.size)

        assertContentEquals(largerPayload, extracted)
        assertEquals(backingBuffer.size, packet.length)
    }
}
