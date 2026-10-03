package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.KeyFileVaultCopyStore
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-460 AC③ 回归：生成型密钥文件一次性交付的字节分型内核。
 *
 * 真机取证（§408 建库窗口）：弹窗宿主随会话锁定被拆 ⇒ 导出通道恒取不到字节 ⇒
 * 原实现只发一条「保存失败」然后静默，生成型密钥文件永久丢失。整改后：
 * - 会话导出失败时**回落本库私有目录收编副本**（AC② 建库即收编的兜底消费）；
 * - 两级都取不到 ⇒ 判定「密钥文件已丢失」（调用方显式分型提示，不再与普通写盘失败混同）。
 *
 * `android.net.Uri` 无 JVM 实现（无 Robolectric），故此处直接驱动 internal 分型内核
 * `resolveDeliveryBytes`；「null ⇒ db_picker_keyfile_lost 消息」的映射在真机走查留痕。
 * 全程使用虚构假字节。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePickerKeyFileDeliveryLostTest {

    /** 会话导出恒失败（Fake 默认即不支持导出 ⇒ 等价「会话已锁定取不到字节」） */
    private fun sessionLostRepository(): FakeVaultRepository = FakeVaultRepository()

    /** 带假封印挂钩的临时副本库（KeyFileSessionCoordinatorTest 同范式；JVM 无 Keystore） */
    private fun newCopyStore(): KeyFileVaultCopyStore {
        val dir = File.createTempFile("kfc", "").parentFile.resolve("kfc-${System.nanoTime()}")
        return KeyFileVaultCopyStore(context = null, keystoreManager = null).also { store ->
            store.baseDirOverride = dir
            store.sealHook = { dek ->
                val iv = ByteArray(12) { it.toByte() }
                iv to dek.mapIndexed { i, b -> (b.toInt() xor (i and 0xFF)).toByte() }.toByteArray()
            }
            store.unsealHook = { iv, ciphertext ->
                ciphertext.mapIndexed { i, b -> (b.toInt() xor (i and 0xFF)).toByte() }.toByteArray()
            }
        }
    }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        copyStore: KeyFileVaultCopyStore? = null
    ): DatabasePickerKeyFileDeliveryController = DatabasePickerKeyFileDeliveryController(
        vaultRepository = sessionLostRepository(),
        appContext = null,
        scope = scope,
        publish = {},
        vaultCopyStore = copyStore
    )

    @Test
    fun `会话失效时交付字节回落本库私有目录副本`() = runTest {
        val store = newCopyStore()
        val fakeBytes = ByteArray(32) { (it * 7 + 3).toByte() }
        assertTrue(store.save("demo.kdbx", fakeBytes, "demo.keyx"))
        val subject = controller(this, store)
        subject.requestSave("demo", dbId = "demo.kdbx")

        val bytes = subject.resolveDeliveryBytes()

        assertNotNull("会话取不到字节时必须回落本库副本（交付不因会话失效而必然失败）", bytes)
        assertArrayEquals(fakeBytes, bytes!!)
        bytes.fill(0)
    }

    @Test
    fun `会话与副本都取不到字节时判定密钥文件已丢失`() = runTest {
        val subject = controller(this, copyStore = null)
        subject.requestSave("demo", dbId = "demo.kdbx")

        assertNull("两级都取不到 ⇒ 确已丢失（调用方显式提示，不得静默）", subject.resolveDeliveryBytes())
    }

    @Test
    fun `未挂出交付提示时无库可回落同样判定丢失`() = runTest {
        val store = newCopyStore()
        val subject = controller(this, store)
        // 未 requestSave ⇒ pendingDbId 为空 ⇒ 副本通道不启用

        assertNull(subject.resolveDeliveryBytes())
    }

    @Test
    fun `会话导出可用时优先取会话字节`() = runTest {
        val store = newCopyStore()
        val sessionBytes = ByteArray(16) { (it + 1).toByte() }
        val recording = FakeVaultRepository()
        val subject = DatabasePickerKeyFileDeliveryController(
            vaultRepository = object : com.keepasskey.app.data.repository.VaultRepository by recording {
                override suspend fun exportKeyFileBytes(): KdbxResult<ByteArray> =
                    KdbxResult.Success(sessionBytes.copyOf())
            },
            appContext = null,
            scope = this,
            publish = {},
            vaultCopyStore = store
        )
        subject.requestSave("demo", dbId = "demo.kdbx")

        val bytes = subject.resolveDeliveryBytes()

        assertArrayEquals("会话可用时第一级导出通道优先", sessionBytes, bytes!!)
        bytes.fill(0)
    }
}
