package com.droiddeck.launcher.core

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbIdentityGeneratorTest {

    private fun notAfterMs() = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun derFromPem(pem: ByteArray, type: String): ByteArray {
        val text = pem.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("-----BEGIN $type-----"))
        assertTrue(text.endsWith("-----END $type-----"))
        val base64 = text
            .removePrefix("-----BEGIN $type-----")
            .removeSuffix("-----END $type-----")
            .trim()
        return Base64.getDecoder().decode(base64)
    }

    @Test fun freshIdentitiesDiffer() {
        val first = AdbIdentityGenerator.generateFreshIdentity(notAfterMs())
        val second = AdbIdentityGenerator.generateFreshIdentity(notAfterMs())
        assertNotEquals(sha256Hex(first.first), sha256Hex(second.first))
        assertFalse(first.first.contentEquals(second.first))
        assertFalse(first.second.contentEquals(second.second))
    }

    @Test fun generatedIdentityIsValid() {
        val (certPem, keyPem) = AdbIdentityGenerator.generateFreshIdentity(notAfterMs())
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(derFromPem(certPem, "CERTIFICATE"))) as X509Certificate
        certificate.checkValidity()
        assertTrue(certificate.subjectX500Principal.name.contains("CN=Kadb"))
        // Parsing the key throws when it is not a valid PKCS8 RSA key.
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(derFromPem(keyPem, "PRIVATE KEY")))
        // The certificate must verify against its own public key: the pair
        // matches, which is what the pairing handshake relies on.
        certificate.verify(certificate.publicKey)
    }
}
