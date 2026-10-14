package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.testutil.InMemorySharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.SecretKeySpec

/**
 * ISSUE-P2-598：生物识别解封链的敏感缓冲清理所有权。
 *
 * 覆盖三条注入路径（每条都以**引用观察字节内容**，不只看回调次数）：
 * 1. 载荷 `decode` 抛异常 → 已解密的字节仍被擦除；
 * 2. 密钥文件现读在挂起点抛异常 → 已解密字节与已解码载荷都被擦除；
 * 3. 工作线程已完成 `doFinal`、调用方在结果交付前被取消 → 分配结果仍被擦除
 *    （受控调度器 + 阻塞式假 Cipher 制造确定性交付窗口）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricUnlockBufferCleanupTest {

    private fun storage() = BiometricCredentialStorage(
        InMemorySharedPreferences().context(),
        keystoreManager = null
    )

    private fun coordinator(scope: CoroutineScope) = BiometricUnlockCoordinator(
        scope = scope,
        uiState = MutableStateFlow(UnlockUiState()),
        events = MutableSharedFlow(),
        vaultRepository = FakeVaultRepository(),
        activeDbId = { "db-1" },
        biometricAuthManager = null,
        biometricCredentialStorage = null,
        debugLog = DebugLogBuffer()
    )

    @Test
    fun `解码失败时仍擦除解密字节`() = runTest {
        val coordinator = coordinator(backgroundScope)
        // 魔数 "KPB1" + 未知版本(0x09) ⇒ decode 抛 IllegalStateException（发生在原实现的 try 之外）
        val decrypted = "KPB1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x09)

        runCatching { coordinator.completeBiometricUnlock(decrypted, storage(), "db-1") }

        assertTrue("解码失败后解密字节必须被擦除", decrypted.all { it == 0.toByte() })
    }

    @Test
    fun `密钥文件现读抛异常时擦除解密字节与载荷`() = runTest {
        val coordinator = coordinatorWithRestore(
            scope = backgroundScope,
            restore = { throw IllegalStateException("现读失败") }
        )
        // 仅主密码载荷（不带密钥文件）且会话驻留为空 ⇒ 走到现读挂起点并抛异常
        val decrypted = BiometricSealedPayloadCodec.encode("SecretPass#1".toCharArray(), null)

        runCatching { coordinator.completeBiometricUnlock(decrypted, storage(), "db-1") }

        assertTrue("现读抛异常后解密字节必须被擦除", decrypted.all { it == 0.toByte() })
    }

    @Test
    fun `交付前取消时工作线程产出的结果仍被擦除`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val scope = CoroutineScope(executor.asCoroutineDispatcher())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val out = ByteArray(32) { 0x5A }
            val cipher = BlockingCipher(out, entered, release)

            coordinator(scope).handleBiometricResult(
                BiometricResult.Success(cipher),
                storage(),
                "db-1",
                ByteArray(16)
            )

            assertTrue("工作线程应已进入 doFinal", entered.await(5, TimeUnit.SECONDS))
            // 结果已在工作线程产生（doFinal 尚未返回，但 out 已可被上层持有）；此刻取消调用方
            scope.cancel()
            release.countDown()

            assertTrue(
                "交付前取消后，工作线程产出的结果数组必须被擦除",
                pollUntil(5000) { out.all { it == 0.toByte() } }
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun coordinatorWithRestore(
        scope: CoroutineScope,
        restore: suspend () -> Unit
    ) = BiometricUnlockCoordinator(
        scope = scope,
        uiState = MutableStateFlow(UnlockUiState()),
        events = MutableSharedFlow(),
        vaultRepository = FakeVaultRepository(),
        activeDbId = { "db-1" },
        biometricAuthManager = null,
        biometricCredentialStorage = null,
        debugLog = DebugLogBuffer(),
        restoreRememberedKeyFile = restore
    )

    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /**
     * 阻塞式假 Cipher：`doFinal` 在 [entered] 上通知「已进入」、在 [release] 上等待放行，
     * 随后返回**调用方可见的** [out] 数组——使「工作线程已产出、交付前被取消」这一窗口可确定性复现。
     * （`Cipher.doFinal` 为 final，故经 `CipherSpi` 拦截。）
     */
    private class BlockingCipher(
        private val out: ByteArray,
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : Cipher(BlockingCipherSpi(out, entered, release), StubProvider(), "BlockingFake") {
        init {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(ByteArray(16), "AES"))
        }
    }

    private class BlockingCipherSpi(
        private val out: ByteArray,
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : CipherSpi() {
        override fun engineSetMode(mode: String) = throw java.security.NoSuchAlgorithmException(mode)

        override fun engineSetPadding(padding: String) = throw NoSuchPaddingException(padding)

        override fun engineGetBlockSize(): Int = 0

        override fun engineGetOutputSize(inputLen: Int): Int = out.size

        override fun engineGetIV(): ByteArray = ByteArray(0)

        override fun engineGetParameters(): AlgorithmParameters? = null

        override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) = Unit

        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit

        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) = Unit

        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray = ByteArray(0)

        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int
        ): Int = 0

        override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return out
        }

        override fun engineDoFinal(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int
        ): Int = 0
    }

    private class StubProvider : Provider("KPB598Stub", 1.0, "stub provider for buffered cipher test")
}
