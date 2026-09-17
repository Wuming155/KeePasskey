package com.keepasskey.crypto.passkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.core.model.PasskeyData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISSUE-P3-153 / §146 设备侧验证（真机 / instrumented）：
 * Passkey 签名内核（ES256 / Ed25519）在真实 Android 运行时可用且官方向量逐字节复现。
 */
@RunWith(AndroidJUnit4::class)
class PasskeyNativeDeviceTest {

    @Test
    fun 原生签名内核探活_真机必须可用() {
        assertTrue(
            "真机上 NativePasskeySign 探活必须为 true（RFC 6979 / RFC 8032 KAT 自测）",
            NativePasskeySign.available
        )
    }

    @Test
    fun 生产引擎签名_真机可验() {
        // 生产 signAssertion 路径（32 字节原始私钥 ⇒ 原生内核）；签名必须可被 BC 验签
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToByteArray()
        val expected =
            ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b").hexToByteArray()
        val sig = PasskeyCryptoEngine.signAssertion(PasskeyData.ALGORITHM_ED25519, seed, ByteArray(0))
        assertArrayEquals("RFC 8032 TEST 1 必须经生产路径在真机逐字节复现", expected, sig)
    }
}
