package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.transport.TlsVerificationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectResultTest {
    @Test
    fun aRefusedCertificateIsFoundAmongTheCauses() {
        val tls = TlsVerificationException("self-signed", peerCertificateSha256 = "ab12")
        assertEquals("ab12", ConnectResult.Failed(IllegalStateException("attempt failed", tls)).untrustedCertificate)
        assertNull(ConnectResult.Failed(IllegalStateException("no route")).untrustedCertificate)
    }
}
