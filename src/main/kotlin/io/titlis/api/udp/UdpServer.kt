package io.titlis.api.udp

import io.titlis.api.config.UdpConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket

class UdpServer(
    private val config: UdpConfig,
    private val router: EventRouter,
) {
    private val logger = LoggerFactory.getLogger(UdpServer::class.java)
    private val queue = Channel<ByteArray>(config.queueSize)

    fun start(scope: CoroutineScope) {
        // Worker coroutines processam a fila
        repeat(config.workers) { workerId ->
            scope.launch(Dispatchers.IO) {
                logger.info("UDP worker $workerId started")
                for (payload in queue) {
                    runCatching { router.route(payload) }
                        .onFailure { logger.error("UDP route error: ${it.message}", it) }
                }
            }
        }

        // Receptor UDP em thread dedicada
        scope.launch(Dispatchers.IO) {
            DatagramSocket(config.port).use { socket ->
                logger.info("UDP server listening on port ${config.port}")
                val buffer = ByteArray(config.bufferSize)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive) {
                    runCatching {
                        // DatagramPacket keeps the last received length. Reset it so
                        // a short packet does not truncate the next larger envelope.
                        packet.length = buffer.size
                        socket.receive(packet)
                        val payload = extractPayload(packet, buffer.size)
                        if (!queue.trySend(payload).isSuccess) {
                            logger.warn("UDP queue full — dropping event")
                        }
                    }.onFailure {
                        if (isActive) logger.warn("UDP receive error: ${it.message}")
                    }
                }
            }
        }
    }

    internal fun extractPayload(packet: DatagramPacket, bufferSize: Int): ByteArray {
        val payload = packet.data.copyOf(packet.length)
        packet.length = bufferSize
        return payload
    }
}
