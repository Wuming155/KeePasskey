package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.logger.DebugLogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

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
}
