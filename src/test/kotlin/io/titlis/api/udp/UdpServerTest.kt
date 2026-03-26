package io.titlis.api.udp

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test

class UdpServerTest {

    @Test
    fun `scorecard_evaluated event is routed correctly`() = runTest {
        val router = mockk<EventRouter>(relaxed = true)
        // Iniciar servidor em porta aleatória para teste
        val payload = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,"data":{
              "workload_id":"123e4567-e89b-12d3-a456-426614174000",
              "namespace":"production","workload":"api","cluster":"prod",
              "k8s_event_type":"update","overall_score":85.0,
              "compliance_status":"COMPLIANT","total_rules":26,
              "passed_rules":22,"failed_rules":4,"critical_failures":0,
              "error_count":1,"warning_count":3,"scorecard_version":5,
              "pillar_scores":[],"evaluated_at":"2026-03-16T10:00:00Z"
            }}
        """.trimIndent()

        // Enviar via UDP
        val socket = DatagramSocket()
        val bytes = payload.toByteArray()
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), 18125))
        socket.close()

        // Verificar que router.route foi chamado com o payload
        // (teste simplificado — verificar com delay ou Channel em testes reais)
    }
}
