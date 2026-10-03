package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeyFileVaultCopyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-25 结构拆分的敏感数据回归锁：密钥文件驻留字节的借用 / 克隆 / 清零路径
 * 已由 `UnlockViewModel` 外移至 [KeyFileSessionCoordinator]，本用例以可执行断言锁定
 * 「清零时机与清零点数量」不因拆分而丢失。
 *
 * 覆盖的清零点（与拆分前 `UnlockViewModel` 内的逐条一一对应）：
 * 1. `adoptKeyFile` 覆盖采纳前清零旧驻留副本；
 * 2. `clearKeyFile` 取消选择清零；
 * 3. `onKeyFileReadFailed` 读取失败清零；
 * 4. `wipe` 解锁成功后清零（原 `unlock()` 成功分支）与 ViewModel 销毁收尾（原 `onCleared()`）。
 *
 * 全部使用虚构假字节，绝不使用真实密钥文件内容。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyFileSessionCoordinatorTest {

    private fun coordinator(scope: CoroutineScope): KeyFileSessionCoordinator =
        KeyFileSessionCoordinator(
            scope = scope,
            uiState = MutableStateFlow(UnlockUiState()),
            keyFileAccess = null,
            debugLog = DebugLogBuffer()
        )

    @Test
    fun `采纳字节为独立副本且旧副本被清零`() = runTest {
        val subject = coordinator(this)

        val callerOwned = ByteArray(8) { 7 }
        subject.onKeyFileSelected(callerOwned, "a.keyx")

        assertArrayEquals("驻留副本内容必须与上行字节一致", ByteArray(8) { 7 }, subject.keyFileData!!)
        assertFalse(
            "驻留副本必须是克隆体（调用方数组用毕自行清零，不得与驻留副本共享实例）",
            callerOwned === subject.keyFileData
        )

        val previousResident = subject.keyFileData!!
        subject.onKeyFileSelected(ByteArray(4) { 9 }, "b.keyx")

        assertArrayEquals(
            "覆盖采纳新字节前必须清零旧驻留副本",
            ByteArray(8) { 0 },
            previousResident
        )
        assertArrayEquals("新驻留副本内容必须为新采纳字节", ByteArray(4) { 9 }, subject.keyFileData!!)
    }

    @Test
    fun `取消密钥文件清零驻留字节并复位引用`() = runTest {
        val subject = coordinator(this)
        subject.onKeyFileSelected(ByteArray(6) { 3 }, "a.keyx")
        val resident = subject.keyFileData!!

        subject.clearKeyFile()

        assertArrayEquals("取消选择必须清零驻留字节", ByteArray(6) { 0 }, resident)
        assertNull("取消选择后不得残留字节引用", subject.keyFileData)
    }

    @Test
    fun `读取失败路径同样清零驻留字节`() = runTest {
        val subject = coordinator(this)
        subject.onKeyFileSelected(ByteArray(5) { 1 }, "a.keyx")
        val resident = subject.keyFileData!!

        subject.onKeyFileReadFailed()

        assertArrayEquals("读取失败必须清零原驻留字节", ByteArray(5) { 0 }, resident)
        assertNull("读取失败后不得残留字节引用", subject.keyFileData)
    }

    @Test
    fun `解锁成功收尾与销毁收尾均清零驻留字节`() = runTest {
        val subject = coordinator(this)

        subject.onKeyFileSelected(ByteArray(6) { 4 }, "a.keyx")
        val afterUnlock = subject.keyFileData!!
        subject.wipe()
        assertArrayEquals("解锁成功后必须清零驻留字节", ByteArray(6) { 0 }, afterUnlock)
        assertNull("解锁成功后不得残留字节引用", subject.keyFileData)

        subject.onKeyFileSelected(ByteArray(3) { 5 }, "b.keyx")
        val onCleared = subject.keyFileData!!
        subject.wipe()
        assertArrayEquals("销毁收尾必须清零驻留字节", ByteArray(3) { 0 }, onCleared)
        assertNull("销毁收尾后不得残留字节引用", subject.keyFileData)
    }

    // ===== §411（ISSUE-P3-448）：私有目录收编副本通道 =====

    /** 带收编副本通道的协调器（假封印挂钩，见 KeyFileVaultCopyStoreTest 同范式） */
    private fun coordinatorWithCopy(
        scope: CoroutineScope,
        access: FakeKeyFileAccess,
        dbId: String = "db-1"
    ): Triple<KeyFileSessionCoordinator, KeyFileVaultCopyStore, MutableStateFlow<UnlockUiState>> {
        val dir = File.createTempFile("kfc", "").parentFile
            .resolve("kfc-${System.nanoTime()}")
        val store = KeyFileVaultCopyStore(
            context = null,
            keystoreManager = null
        ).also { it.baseDirOverride = dir }
        store.sealHook = { dek ->
            val iv = ByteArray(12) { it.toByte() }
            iv to dek.mapIndexed { i, b -> (b.toInt() xor (i and 0xFF)).toByte() }.toByteArray()
        }
        store.unsealHook = { iv, ciphertext ->
            ciphertext.mapIndexed { i, b -> (b.toInt() xor (i and 0xFF)).toByte() }.toByteArray()
        }
        val uiState = MutableStateFlow(UnlockUiState())
        val subject = KeyFileSessionCoordinator(
            scope = scope,
            uiState = uiState,
            keyFileAccess = access,
            debugLog = DebugLogBuffer(),
            vaultCopyStore = store,
            activeDbId = { dbId }
        )
        return Triple(subject, store, uiState)
    }

    @Test
    fun `解锁成功使用密钥文件即收编副本`() = runTest {
        val access = FakeKeyFileAccess()
        val (subject, store, _) = coordinatorWithCopy(this, access)
        subject.onKeyFileSelected(FakeKeyFileAccess.FAKE_KEY_FILE_BYTES, FakeKeyFileAccess.DISPLAY_NAME)

        subject.rememberKeyFileOnSuccess(usedKeyFile = true, displayName = FakeKeyFileAccess.DISPLAY_NAME)

        val copy = store.load("db-1")
        assertNotNull("解锁成功使用密钥文件必须收编副本", copy)
        assertArrayEquals(
            "副本字节必须与会话驻留字节一致",
            FakeKeyFileAccess.FAKE_KEY_FILE_BYTES,
            copy!!.bytes
        )
        assertEquals(FakeKeyFileAccess.DISPLAY_NAME, copy.displayName)
        copy.bytes.fill(0)
    }

    @Test
    fun `冷启动恢复优先消费私有目录副本（授权失效不再阻断）`() = runTest {
        val access = FakeKeyFileAccess(permissionValid = false, persistPermissionSucceeds = false)
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        access.remember("db-1", FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.DISPLAY_NAME)
        val (subject, store, uiState) = coordinatorWithCopy(this, access)
        assertTrue(store.save("db-1", FakeKeyFileAccess.FAKE_KEY_FILE_BYTES, FakeKeyFileAccess.DISPLAY_NAME))

        subject.restoreRememberedKeyFile()

        assertArrayEquals(
            "授权失效时副本必须仍能恢复第二因子",
            FakeKeyFileAccess.FAKE_KEY_FILE_BYTES,
            subject.keyFileData!!
        )
        assertTrue("恢复后必须呈现「已选择密钥文件」", uiState.value.hasKeyFile)
    }

    @Test
    fun `未使用密钥文件解锁成功清除旧副本`() = runTest {
        val access = FakeKeyFileAccess()
        val (subject, store, _) = coordinatorWithCopy(this, access)
        assertTrue(store.save("db-1", byteArrayOf(1, 2, 3), "old.keyx"))

        subject.rememberKeyFileOnSuccess(usedKeyFile = false, displayName = "")

        assertNull("标准解锁未携带密钥文件时旧副本必须清除", store.load("db-1"))
    }

    @Test
    fun `偏好关闭时副本仍自动载入且清 Uri 记忆`() = runTest {
        // §411 走查语义裁决：副本归「导入密钥文件」功能管——偏好关闭只停用 Uri 记忆，
        // 显式导入/改绑产生的副本仍须自动载入（导入承诺：此后解锁不再依赖授权）
        val access = FakeKeyFileAccess(rememberEnabled = false)
        access.remember("db-1", FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.DISPLAY_NAME)
        val (subject, store, _) = coordinatorWithCopy(this, access)
        assertTrue(store.save("db-1", FakeKeyFileAccess.FAKE_KEY_FILE_BYTES, FakeKeyFileAccess.DISPLAY_NAME))

        subject.restoreRememberedKeyFile()

        assertArrayEquals(
            "偏好关闭时显式导入的副本必须仍自动载入",
            FakeKeyFileAccess.FAKE_KEY_FILE_BYTES,
            subject.keyFileData!!
        )
        assertEquals("偏好关闭时 Uri 记忆仍须清除", 1, access.forgetCount)
    }

    // ===== ISSUE-P2-460：密钥文件记忆按库归属 =====

    @Test
    fun `本库未登记时其它库的按库记忆与旧全局槽都不得自动载入`() = runTest {
        val access = FakeKeyFileAccess()
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        // 记忆登记在另一个库名下 + 旧版全局槽残留（历史升级态）
        access.remember("db-other", FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.DISPLAY_NAME)
        access.putLegacyGlobal(FakeKeyFileAccess.KEY_FILE_URI)
        val (subject, _, uiState) = coordinatorWithCopy(this, access)

        subject.restoreRememberedKeyFile()

        assertNull("其它库的记忆绝不能套用到本库（跨库串因子）", subject.keyFileData)
        assertFalse("跨库记录不得呈现「已选择密钥文件」", uiState.value.hasKeyFile)
        assertEquals(
            "旧全局记录只允许一句话提示（不参与解锁裁决）",
            com.keepasskey.app.R.string.keyfile_legacy_memory_hint,
            uiState.value.infoMessage?.resId
        )
        assertEquals("提示通道不得清掉其它库的记忆", 0, access.forgetCount)
    }

    @Test
    fun `本库按库记忆在无副本时仍走SAF通道恢复`() = runTest {
        val access = FakeKeyFileAccess()
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        access.remember("db-1", FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.DISPLAY_NAME)
        val (subject, store, uiState) = coordinatorWithCopy(this, access)
        // db-1 无副本（独立临时目录，未 save 过任何副本）：回落按库 SAF 记忆通道
        assertTrue("前置：db-1 无副本", store.baseDirOverride!!.listFiles().isNullOrEmpty())

        subject.restoreRememberedKeyFile()

        assertArrayEquals(
            "本库按库记忆必须恢复第二因子",
            FakeKeyFileAccess.FAKE_KEY_FILE_BYTES,
            subject.keyFileData!!
        )
        assertEquals(
            com.keepasskey.app.R.string.keyfile_restored_from_memory,
            uiState.value.infoMessage?.resId
        )
    }

    @Test
    fun `按库记忆互相隔离——本库登记绝不覆盖其它库`() = runTest {
        val access = FakeKeyFileAccess()
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        access.remember("db-other", "content://test.docs/other.pem", "other.pem")
        val (subject, _, _) = coordinatorWithCopy(this, access)
        subject.onKeyFileSelected(FakeKeyFileAccess.KEY_FILE_URI)
        // 来源登记（trackKeyFileSource）经 scope.launch 异步推进
        testScheduler.runCurrent()

        subject.rememberKeyFileOnSuccess(usedKeyFile = true, displayName = FakeKeyFileAccess.DISPLAY_NAME)

        assertEquals(
            "本库登记落本库名下",
            FakeKeyFileAccess.KEY_FILE_URI,
            access.persistedKeyFileUri("db-1")
        )
        assertEquals(
            "其它库的按库记录不受影响",
            "content://test.docs/other.pem",
            access.persistedKeyFileUri("db-other")
        )
        assertEquals("按库登记绝不写旧全局槽", "", access.legacyGlobalUri())
    }
}
