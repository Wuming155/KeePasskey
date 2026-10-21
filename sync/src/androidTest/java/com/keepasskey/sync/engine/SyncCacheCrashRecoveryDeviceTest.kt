package com.keepasskey.sync.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-636`：**真实进程死亡**后同步缓存判据的设备侧验收。
 *
 * ## 为什么必须两阶段 + 真实杀进程
 *
 * 问题形态是「`.cache` 已换成新字节、`.version` 还是旧值（或缺失），进程随即消失」。
 * JVM 侧用确定性写序注入覆盖了这个窗口（`SyncCacheCrashRecoveryTest` 10 例），
 * 但那不是**真实进程死亡**：真机上文件系统缓存、rename 原子性、冷启首读都参与其中。
 * 单次 instrumentation 无法「自杀后继续断言」，故由 `tools/device/run_p2_636_crash.py`
 * 分两段驱动：prepare 段制造窗口并写闸门，宿主 `am force-stop`，verify 段在新进程核对。
 *
 * ## 判据（与问题描述一一对应）
 *
 * 1. 崩溃窗口留下「新字节 + 旧 `.version`」时，新进程的 `hasLocalChanges` 必须为 **true**
 *    （判据以 `.cache` 实际字节摘要对照 `.baseversion`，不看 `.version`）；
 * 2. 崩溃窗口留下「新字节 + 缺失 `.version`」时同样必须为 **true**（缺失不得被当成「无修改」）；
 * 3. 字节与基线一致的负对照必须为 **false**（不能把所有情况都判成脏）；
 * 4. 崩溃窗口不得留下 `.tmp` 半成品，且 `.cache` 可整份读回、逐字节等于崩溃前写入的内容。
 */
@RunWith(AndroidJUnit4::class)
class SyncCacheCrashRecoveryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private val directory: File by lazy {
        File(context.filesDir, "p2-636-crash").apply { mkdirs() }
    }
    private val gate: File by lazy { File(directory, "GATE_REACHED") }

    /** 崩溃前写入的新字节（verify 段据此核对 `.cache` 未被半写污染）。 */
    private val crashedPayload: ByteArray by lazy { ByteArray(64 * 1024) { (it * 7 % 251).toByte() } }

    @Test
    fun prepareCrashWindow() {
        assertEquals("只允许宿主以 p2636Phase=prepare 启动", PHASE_PREPARE, arguments.getString(ARG_PHASE))
        gate.delete()
        directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }

        val cache = SyncCache(directory)
        val remotePath = "vault/p2-636.kdbx"
        // 1. 先建立一致基线：写入旧字节并把基线前移到它
        val oldPayload = ByteArray(4096) { it.toByte() }
        cache.writeCache(remotePath, oldPayload)
        cache.updateBase(remotePath, SyncCache.sha256Hex(oldPayload))

        // 2. 制造「新字节已落盘、`.version` 仍是旧值」的崩溃窗口：
        //    updateVersion=false 正是该窗口的等价形态（真实崩溃发生在 rename 与写 version 之间）。
        cache.writeCache(remotePath, crashedPayload, updateVersion = false)

        val versionFile = directory.listFiles { f -> f.name.endsWith(".version") }!!.single()
        val baseVersionFile = directory.listFiles { f -> f.name.endsWith(".baseversion") }!!.single()
        // 前提核对：`.version` 确实仍是旧值，否则本用例证明的不是那个窗口
        assertNotEquals(
            "崩溃窗口必须留下旧 .version（否则没有复现目标形态）",
            SyncCache.sha256Hex(crashedPayload),
            versionFile.readText().trim()
        )
        assertTrue("基线必须已建立", baseVersionFile.isFile)

        // 3. 闸门：宿主据此确认可以杀进程
        gate.writeText(android.os.Process.myPid().toString())
    }

    @Test
    fun verifyNewProcessStillSeesLocalChanges() {
        assertEquals("只允许宿主以 p2636Phase=verify 启动", PHASE_VERIFY, arguments.getString(ARG_PHASE))
        val oldPid = gate.readText().trim().toInt()
        assertNotEquals(
            "verify 段必须是**新进程**（同 PID 说明进程没被杀掉，验收无效）",
            oldPid,
            android.os.Process.myPid()
        )

        val cache = SyncCache(directory)
        val remotePath = "vault/p2-636.kdbx"

        // ① 新字节 + 旧 .version ⇒ 必须判为「有本地修改」
        assertTrue(
            "崩溃后 .cache 是新字节、.version 是旧值，hasLocalChanges 必须为 true（否则已保存的本地内容不会上传）",
            cache.hasLocalChanges(remotePath)
        )
        // ② `.cache` 必须可整份读回且逐字节等于崩溃前写入的内容（未被半写污染）
        val recovered = requireNotNull(cache.readCache(remotePath)) { "崩溃后 .cache 必须仍可读" }
        assertTrue("崩溃后 .cache 内容必须与崩溃前写入逐字节一致", recovered.contentEquals(crashedPayload))
        // ③ 崩溃窗口不得留下 .tmp 半成品
        val leftovers = directory.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty()
        assertEquals("崩溃窗口不得遗留 .tmp 半成品：${leftovers.map { it.name }}", 0, leftovers.size)

        // ④ 缺失 .version 同样必须判为「有本地修改」
        directory.listFiles { f -> f.name.endsWith(".version") }?.forEach { it.delete() }
        assertTrue(
            "缺失 .version 不得被当成「无修改」（原缺陷正是此分支直接 false）",
            cache.hasLocalChanges(remotePath)
        )

        // ⑤ 负对照：把基线前移到当前字节后必须判为「无本地修改」
        cache.updateBase(remotePath, SyncCache.sha256Hex(crashedPayload))
        assertFalse(
            "字节与基线一致时必须判为无本地修改（否则会把干净缓存当脏反复上传）",
            cache.hasLocalChanges(remotePath)
        )
        gate.delete()
    }

    private companion object {
        const val ARG_PHASE = "p2636Phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_VERIFY = "verify"
    }
}
