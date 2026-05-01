package io.titlis.api.auth

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.Payload
import io.mockk.every
import io.mockk.mockk
import io.titlis.api.routes.testAuthConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OktaTokenVerifierTest {

    @Test
    fun `payloadToIdentity reads groups from group claim`() {
        val verifier = OktaTokenVerifier(testAuthConfig())
        val payload = mockPayload(
            claimLists = mapOf("group" to listOf("Jeitto Confia - Viewer")),
        )

        val identity = verifier.payloadToIdentity(payload)

        assertEquals(listOf("Jeitto Confia - Viewer"), identity?.groups)
        assertEquals(PlatformRole.VIEWER, identity?.platformRole())
    }

    @Test
    fun `payloadToIdentity returns null platform role when no recognized group exists`() {
        val verifier = OktaTokenVerifier(testAuthConfig())
        val payload = mockPayload(
            claimLists = mapOf("group" to emptyList()),
        )

        val identity = verifier.payloadToIdentity(payload)

        assertEquals(emptyList(), identity?.groups)
        assertNull(identity?.platformRole())
    }

    @Test
    fun `payloadToIdentity falls back to subject when email claim is absent`() {
        val verifier = OktaTokenVerifier(testAuthConfig())
        val payload = mockPayload(
            subject = "user@jeitto.com",
            claimLists = mapOf("group" to listOf("Jeitto Confia - Viewer")),
        )

        val identity = verifier.payloadToIdentity(payload)

        assertEquals("user@jeitto.com", identity?.email)
    }

    private fun mockPayload(
        subject: String = "subject-1",
        claimLists: Map<String, List<String>> = emptyMap(),
        claimStrings: Map<String, String> = emptyMap(),
    ): Payload {
        val payload = mockk<Payload>()
        every { payload.subject } returns subject
        every { payload.issuer } returns "https://example.okta.com/oauth2/default"
        every { payload.getClaim(any()) } answers {
            val name = firstArg<String>()
            val claim = mockk<Claim>()
            every { claim.asList(String::class.java) } returns claimLists[name]
            every { claim.asString() } returns claimStrings[name]
            every { claim.asLong() } returns null
            claim
        }
        return payload
    }
}
