package io.github.shaonialife.cardeck.iap2

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 从配件叶证书的公钥类型选择首次挑战长度，避免把陌生盒子一律当成 32B 测试台。
 *
 * RSA / EC 分别提示优先尝试 20B / 32B；这只是排序依据，固件行为仍以成功记忆为准。
 * 不能看证书签名算法（那是颁发者的算法），也不能用证书长度或蓝牙名称猜认证芯片。
 * 此处只解析公钥类型，不改变现有的证书信任校验策略。
 */
internal object MfiCertificateHint {
    enum class KeyType(
        val challengeBytes: Int,
    ) {
        RSA(20),
        EC(32),
    }

    fun fromBody(body: ByteArray): KeyType? {
        val certificate = Iap2Csm.parseParams(body).singleOrNull { it.id == 0 }?.value ?: return null
        return try {
            // 兼容单张 X.509 DER 和 0xAA01 常用的 PKCS#7 证书集合。
            val certificates =
                CertificateFactory
                    .getInstance("X.509")
                    .generateCertificates(ByteArrayInputStream(certificate))
                    .filterIsInstance<X509Certificate>()
            val leaf = certificates.singleOrNull { it.basicConstraints < 0 } ?: return null
            when (leaf.publicKey.algorithm.uppercase()) {
                "RSA" -> KeyType.RSA
                "EC", "ECDSA" -> KeyType.EC
                else -> null
            }
        } catch (_: java.security.GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
