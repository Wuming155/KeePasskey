package com.keepasskey.core.log

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
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
 * ## 平台能力前提（`ISSUE-P2-517` 立规，改本用例前必读）
 *
 * 「v 级是否被保留」是 **ROM 的 logd 配置**，**不是** Android 契约：实测 MIUI 真机
 * （`M332BF` / Android 17）上 `Log.v` 写入被**整体丢弃**（受控探针：v 命中 0，d / i / w / e 各命中 1），
 * 故「debug 开启 ⇒ v 级落盘」这条断言曾在该设备上**确定性假红**（2/2），把 ROM 配置读成了产品缺陷。
 * 现按「前提自探 + 平台保留通道」重写：
 *
 * 1. 先用 `android.util.Log`（**不经** `AppLog`，故与门控状态无关）逐级写真探针并回读，
 *    得出本机日志级别能力**读数**；
 * 2. 门控的「开 → 落」方向取**平台确实保留**的级别（优先 `d`，回退 `v`）作为可观测通道
 *    ——`d` 与 `v` 在 `AppLog` 里受**同一个** `debugEnabled` 门控，故改通道不削弱判据；
 * 3. 两级皆不保留时，该方向在此平台**不可观测** ⇒ `Assume` 并写明条件（不得当作缺陷）；
 * 4. 「v 级是否被保留」一律**降为读数**（打印），**不作为断言**。
 *
 * ## 覆盖与不覆盖（如实声明）
 *
 * - **覆盖**：i 级别真实落 logcat；`debugEnabled` 门控（关 → 不落，开 → 落在平台保留的通道上）；
 *   `debugEnabled=false` 时 e 级别脱敏（异常 message / 堆栈不外传，只落全限定类名）。
 * - **不覆盖**：logcat 环形缓冲溢出后的历史行丢失（平台自身行为，与 AppLog 无关）；
 *   平台**整体丢弃**的级别（如 MIUI 上的 v）在该平台不可作为判据通道——这是本用例刻意降级的边界。
 * - **历史名（`ISSUE-P2-517` 前）**：`debugEnabled 为 true 时 v 级别真实落 logcat` →
 *   现为 `debugEnabled 为 true 时门控在平台保留的通道上真实落 logcat`，覆盖**不减反增**
 *   （新增平台能力读数与平台保留通道断言）；原 v 级「关 → 不落」判据保留并补强鉴别力。
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

    /**
     * 平台能力探针：本机 logcat 是否**保留**该级别（真实写入 + 回读，不用属性推断）。
     *
     * 刻意走 `android.util.Log` 而不经 `AppLog`：平台丢弃某级别时 `AppLog` 的写入会一并消失，
     * 那样就分不清「门控拦住了」与「平台丢了」——这正是 `ISSUE-P2-517` 的病灶。
     */
    private fun platformRetains(level: Int): Boolean {
        val nonce = "CAP-L$level-${UUID.randomUUID()}"
        when (level) {
            Log.VERBOSE -> Log.v(tag, nonce)
            Log.DEBUG -> Log.d(tag, nonce)
            else -> throw IllegalArgumentException("只探测 VERBOSE / DEBUG，收到 $level")
        }
        return nonce in waitUntilLogged(nonce)
    }

    /** 一次探测得的平台日志级别能力（`ISSUE-P2-517`：v 级可被 ROM 整体丢弃） */
    private class LevelCapability(val verbose: Boolean, val debug: Boolean) {
        /** 门控「开 → 落」方向的可观测通道；两级皆不保留时为 `null`（该平台不可观测） */
        val observableChannel: String? = when {
            debug -> "d"
            verbose -> "v"
            else -> null
        }
    }

    private fun probeLevelCapability(): LevelCapability =
        LevelCapability(platformRetains(Log.VERBOSE), platformRetains(Log.DEBUG))

    private fun printCapability(capability: LevelCapability) {
        println(
            "[AppLog 门控设备侧·平台能力读数] 本机 logcat 保留 v=${capability.verbose} " +
                "d=${capability.debug}；门控判据通道=${capability.observableChannel ?: "无（平台级丢弃）"}" +
                "（ISSUE-P2-517）"
        )
    }

    @Test
    fun `i 级别真实写入 logcat`() {
        val nonce = "I-LEVEL-${UUID.randomUUID()}"
        AppLog.i(tag, nonce)

        val dump = waitUntilLogged(nonce)

        assertTrue("AppLog.i 必须真实落 logcat（标记行未出现）", nonce in dump)
    }

    @Test
    fun `debugEnabled 为 false 时门控关闭态不落 logcat`() {
        val capability = probeLevelCapability()
        printCapability(capability)

        AppLog.debugEnabled = false
        val vNonce = "V-GATED-OFF-${UUID.randomUUID()}"
        AppLog.v(tag, vNonce)
        assertFalse(
            "debug 关闭时 v 级别不得输出（nonce=${vNonce.takeLast(8)}）" +
                "——如实标注：本机若不保留 v 级（v=${capability.verbose}），本条**恒真、无鉴别力**，" +
                "不得当作覆盖（ISSUE-P2-517）",
            vNonce in dumpLogcat()
        )

        // 鉴别力补强：v 级被平台丢弃时上面的断言只是噪声 ⇒ 在平台确实保留的通道上重做一次
        val channel = capability.observableChannel
        if (channel != null) {
            val nonce = "GATED-OFF-${UUID.randomUUID()}"
            if (channel == "d") AppLog.d(tag, nonce) else AppLog.v(tag, nonce)
            assertFalse(
                "debug 关闭时 $channel 级别不得输出（该通道平台确实保留，本断言有鉴别力）",
                nonce in dumpLogcat()
            )
        } else {
            println(
                "[AppLog 门控设备侧] 本机 v/d 两级皆被平台丢弃 ⇒ 关闭态仅剩恒真断言，" +
                    "已按 ISSUE-P2-517 如实标注（不冒充覆盖）"
            )
        }
    }

    @Test
    fun `debugEnabled 为 true 时门控在平台保留的通道上真实落 logcat`() {
        val capability = probeLevelCapability()
        printCapability(capability)

        val channel = capability.observableChannel
        if (channel == null) {
            Assume.assumeTrue(
                "本机 logcat 既不保留 v 也不保留 d（平台级丢弃）⇒ debugEnabled 门控的『开 → 落』方向" +
                    "在此平台**不可观测**，本用例在此设备上不构成判据（ISSUE-P2-517）",
                false
            )
            return
        }

        AppLog.debugEnabled = true
        val nonce = "GATED-ON-${UUID.randomUUID()}"
        if (channel == "d") AppLog.d(tag, nonce) else AppLog.v(tag, nonce)

        assertTrue(
            "debug 开启时门控必须在平台保留的通道（$channel）上真实落盘（标记行未出现；" +
                "平台保留 v=${capability.verbose} d=${capability.debug}）",
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
