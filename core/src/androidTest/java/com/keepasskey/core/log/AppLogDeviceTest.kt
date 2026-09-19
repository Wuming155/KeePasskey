package com.keepasskey.core.log

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * ISSUE-P2-192 余量第 6 项：`AppLog` 的**设备侧**平台行为用例（core 模块首个 androidTest）。
 *
 * ## 为什么宿主单测不够
 *
 * `AppLog` 的 fail-safe（`safe` 吞 `android.util.Log` 未 mock 异常）在宿主上「通过」的方式
 * 是**输出从未发生**——「日志真的进了 logcat」「debugEnabled 门控在真实 `Log.v/d` 上生效」
 * 「release 脱敏输出只落异常类名」均为 `android.util.Log` 真实存在时的平台行为，只有
 * androidTest 源集可证（AGENTS.md §5）。本用例经 instrumentation 的 UiAutomation（shell 特权）
 * 读回真实 logcat 缓冲，以一次性标记（nonce）验证写入结果。
 *
 * ## 覆盖与不覆盖（如实声明）
 *
 * - **覆盖**：i 级别真实落 logcat；v/d 的 `debugEnabled` 门控（关 → 不落，开 → 落）；
 *   `debugEnabled=false` 时 e 级别脱敏（异常 message / 堆栈不外传，只落全限定类名）。
 * - **不覆盖**：logcat 环形缓冲溢出后的历史行丢失（平台自身行为，与 AppLog 无关）。
 *
 * `debugEnabled` 是进程级单例状态：本用例在 `@After` 恢复为 false，避免污染同进程的其他用例。
 */
@RunWith(AndroidJUnit4::class)
class AppLogDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tag = "AppLogDeviceTest"

    @After
    fun tearDown() {
        AppLog.debugEnabled = false
    }

    /** 读回 logcat 中本 tag 的最近输出（UiAutomation 以 shell 特权执行，测试进程自身无 READ_LOGS） */
    private fun dumpLogcat(): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("logcat -d -s $tag:v")
        return descriptor.use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes().toString(Charsets.UTF_8)
        }
    }

    /** 写入后有毫秒级缓冲延迟：轮询至标记出现或超时（实测一轮 dump 数十毫秒级） */
    private fun waitUntilLogged(nonce: String, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var dump = ""
        while (System.currentTimeMillis() < deadline) {
            dump = dumpLogcat()
            if (nonce in dump) return dump
            Thread.sleep(200)
        }
        return dump
    }

    @Test
    fun `i 级别真实写入 logcat`() {
        val nonce = "I-LEVEL-${UUID.randomUUID()}"
        AppLog.i(tag, nonce)

        val dump = waitUntilLogged(nonce)

        assertTrue("AppLog.i 必须真实落 logcat（标记行未出现）", nonce in dump)
    }

    @Test
    fun `debugEnabled 为 false 时 v 级别不落 logcat`() {
        val nonce = "V-GATED-OFF-${UUID.randomUUID()}"
        AppLog.debugEnabled = false

        AppLog.v(tag, nonce)

        assertFalse(
            "debug 关闭时 v 级别不得输出（nonce=${nonce.takeLast(8)}）",
            nonce in dumpLogcat()
        )
    }

    @Test
    fun `debugEnabled 为 true 时 v 级别真实落 logcat`() {
        val nonce = "V-DEBUG-ON-${UUID.randomUUID()}"
        AppLog.debugEnabled = true

        AppLog.v(tag, nonce)

        assertTrue(
            "debug 开启时 v 级别必须输出（标记行未出现）",
            nonce in waitUntilLogged(nonce)
        )
    }

    @Test
    fun `debug 关闭时 e 级别脱敏为异常类名不含 message 与堆栈`() {
        val marker = "SENSITIVE-DETAIL-${UUID.randomUUID()}"
        val nonce = "E-SANITIZED-${UUID.randomUUID()}"
        AppLog.debugEnabled = false

        AppLog.e(tag, "$nonce $marker", IllegalStateException("raw-$marker"))

        val dump = waitUntilLogged(nonce)
        assertTrue("脱敏输出必须包含标记行", nonce in dump)
        assertFalse(
            "脱敏输出不得包含异常 message 明文",
            "raw-$marker" in dump
        )
        assertFalse(
            "脱敏输出不得包含堆栈帧（\\tat <fqcn> 形态；tag 行头同名不算堆栈）",
            "\tat com.keepasskey" in dump
        )
        assertTrue(
            "脱敏输出必须包含异常全限定类名",
            "java.lang.IllegalStateException" in dump
        )
    }
}
