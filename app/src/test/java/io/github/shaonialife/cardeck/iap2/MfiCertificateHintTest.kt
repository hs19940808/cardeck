package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class MfiCertificateHintTest {
    @Test
    fun rsaDerSuggests20Bytes() {
        assertEquals(
            MfiCertificateHint.KeyType.RSA,
            MfiCertificateHint.fromBody(Iap2Csm.param(0, certificate("rsa").encoded)),
        )
    }

    @Test
    fun ecDerSuggests32Bytes() {
        assertEquals(
            MfiCertificateHint.KeyType.EC,
            MfiCertificateHint.fromBody(Iap2Csm.param(0, certificate("ec").encoded)),
        )
    }

    @Test
    fun pkcs7UsesLeafPublicKeyInsteadOfRsaCa() {
        val factory = CertificateFactory.getInstance("X.509")
        val collection = factory.generateCertPath(listOf(certificate("rsa-ca"), certificate("ec"))).getEncoded("PKCS7")
        assertEquals(MfiCertificateHint.KeyType.EC, MfiCertificateHint.fromBody(Iap2Csm.param(0, collection)))
    }

    @Test
    fun ambiguousOrCaOnlyCollectionDoesNotGuess() {
        val factory = CertificateFactory.getInstance("X.509")
        val ambiguous = factory.generateCertPath(listOf(certificate("rsa"), certificate("ec"))).getEncoded("PKCS7")
        assertNull(MfiCertificateHint.fromBody(Iap2Csm.param(0, ambiguous)))
        assertNull(MfiCertificateHint.fromBody(Iap2Csm.param(0, certificate("rsa-ca").encoded)))
    }

    @Test
    fun absentMalformedOrTruncatedCertificateKeepsFallbackAvailable() {
        assertNull(MfiCertificateHint.fromBody(byteArrayOf()))
        assertNull(MfiCertificateHint.fromBody(Iap2Csm.param(1, certificate("ec").encoded)))
        assertNull(MfiCertificateHint.fromBody(Iap2Csm.param(0, ByteArray(908))))
        assertNull(MfiCertificateHint.fromBody(Iap2Csm.param(0, certificate("rsa").encoded.copyOf(118))))
    }

    companion object {
        /** 仅公钥测试证书，不包含真实车机身份或私钥。 */
        fun certificate(name: String): X509Certificate =
            checkNotNull(MfiCertificateHintTest::class.java.getResourceAsStream("/mfi/$name.pem")).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
    }
}
