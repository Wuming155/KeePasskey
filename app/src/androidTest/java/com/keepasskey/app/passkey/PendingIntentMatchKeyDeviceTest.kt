package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **ISSUE-P2-199 的匹配键前提设备侧实测**：落地 Intent 的 `filterEquals` 判定面。
 *
 * ## 为什么要测这一层
 *
 * 条目正文的核心机制是「同 `(requestCode, filterEquals)` 的 PendingIntent 记录被就地覆写」，
 * 而它能否成立**完全取决于 `filterEquals` 怎么判**。本案中两处关键推论此前只有文档推理：
 *
 * 1. **extras 不参与匹配** ⇒ 同组件的两个落地 Intent（只有 extras 不同）必然 `filterEquals`，
 *    这才使「陈旧 rpId/origin 覆写」成为可能；
 * 2. **component 参与匹配** ⇒ 创建侧（`PasskeyCreateActivity` / `PasswordSaveActivity`）、
 *    断言侧（`PasskeyAssertionActivity`）、填充侧（`PasswordFillActivity`）**两两不碰撞**——
 *    这正是条目正文相对初版「落地 Intent 均为 `Activity::class.java` ⇒ filterEquals 相同」
 *    那一过度概括的**更正依据**。
 *
 * ## 关于「覆写」的验证方式（ISSUE-P2-199 更新）
 *
 * 「`FLAG_UPDATE_CURRENT` 改写**已交付**实例」属平台文档化语义（本仓批次 84 亦据其定论）。
 * 原先只用 `Intent.filterEquals` 锚定匹配键，理由是动态 receiver 的投递链路会引入噪声。
 * ISSUE-P2-199 的验收要求「extras 不被覆写」这一**结果面**证据，故本类新增一对
 * **互为对照**的用例：同 requestCode 的负对照（必须观察到覆写）+ 进程级单调 requestCode
 * 的正例（必须观察不到覆写）。负对照本身就是投递链路有效性的自证——若链路有噪声，
 * 负对照会失败，正例的通过也就不会被误读为「真没被覆写」。
 */
@RunWith(AndroidJUnit4::class)
class PendingIntentMatchKeyDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `同组件落地 Intent 因仅 extras 不同而必然 filterEquals`() {
        val stale = Intent(context, PasskeyCreateActivity::class.java)
            .putExtra(PasskeyCreateActivity.EXTRA_ORIGIN, "https://attacker.example")
            .putExtra(PasskeyCreateActivity.EXTRA_RP_ID, "attacker.example")
        val current = Intent(context, PasskeyCreateActivity::class.java)
            .putExtra(PasskeyCreateActivity.EXTRA_ORIGIN, "https://bank.example")
            .putExtra(PasskeyCreateActivity.EXTRA_RP_ID, "bank.example")

        assertTrue(
            "extras 不参与 filterEquals ⇒ 同组件的两路创建请求必然命中同一 PendingIntent 记录，" +
                "这是 ISSUE-P2-199「陈旧 rpId/origin 覆写」机制的地基",
            stale.filterEquals(current)
        )
    }

    @Test
    fun `跨组件落地 Intent 不 filterEquals`() {
        val create = Intent(context, PasskeyCreateActivity::class.java)
        val save = Intent(context, PasswordSaveActivity::class.java)

        assertFalse(
            "component 参与 filterEquals ⇒ 创建侧与保存侧不构成同一 PendingIntent 记录" +
                "（条目正文更正初版「跨工厂碰撞」的过度概括即据于此）",
            create.filterEquals(save)
        )
        assertTrue(
            "对照组：同组件同为空 extras 的两个 Intent 必须 filterEquals（排除「一切都不相等」的误读）",
            create.filterEquals(Intent(context, PasskeyCreateActivity::class.java))
        )
    }

    @Test
    fun `action 参与 filterEquals`() {
        val withoutAction = Intent(context, PasskeyCreateActivity::class.java)
        val withAction = Intent(context, PasskeyCreateActivity::class.java)
            .setAction("com.keepasskey.probe.ACTION")

        assertFalse(
            "action 参与 filterEquals：若将来给落地 Intent 加上 action，" +
                "requestCode 复用的碰撞面会随之改变——本用例作为该前提的回归锁",
            withoutAction.filterEquals(withAction)
        )
    }

    // ── ISSUE-P2-199：requestCode 进程级单调 ⇒ 两路响应各持独立记录、extras 互不覆写 ──

    @Test
    fun `进程级单调 requestCode 使两路响应各持独立记录且 extras 互不覆写`() {
        val codeA = CredentialPendingIntents.nextRequestCode()
        val codeB = CredentialPendingIntents.nextRequestCode()
        assertTrue(
            "分配器必须单调（并发两路响应拿不到同一值）：$codeA / $codeB",
            codeA != codeB
        )

        val record = RecordingBroadcastProbe()
        val piA = PendingIntent.getBroadcast(
            context, codeA, record.probeIntent("A"), CredentialPendingIntents.ENTRY_FLAGS
        )
        val piB = PendingIntent.getBroadcast(
            context, codeB, record.probeIntent("B"), CredentialPendingIntents.ENTRY_FLAGS
        )
        try {
            assertNotEquals(
                "不同 requestCode 必须对应两条独立 PendingIntent 记录（equals 比较底层 IntentSender）",
                piA,
                piB
            )
            // 先发后注册的 B，再发先注册的 A：若 B 的注册覆写了 A 的 extras，A 会投递出 "B"
            record.expect(EXPECTED_PROBE_DELIVERIES)
            piB.send()
            piA.send()
            val received = record.await()
            assertEquals(
                "ISSUE-P2-199：进程级单调 requestCode 下，后一路响应不得覆写前一路条目的 extras；实际收到=$received",
                setOf("A", "B"),
                received.toSet()
            )
        } finally {
            piA.cancel()
            piB.cancel()
            record.close()
        }
    }

    @Test
    fun `负对照_同 requestCode 复核次注册确会覆写既有记录的 extras`() {
        // 判别链路自证：若此处观察不到覆写，则上一个用例的「未覆写」结论不成立
        val code = CredentialPendingIntents.nextRequestCode()
        val record = RecordingBroadcastProbe()
        val piFirst = PendingIntent.getBroadcast(
            context, code, record.probeIntent("FIRST"), CredentialPendingIntents.ENTRY_FLAGS
        )
        val piSecond = PendingIntent.getBroadcast(
            context, code, record.probeIntent("SECOND"), CredentialPendingIntents.ENTRY_FLAGS
        )
        try {
            record.expect(1)
            piFirst.send()
            val received = record.await()
            assertEquals(
                "同 requestCode + 同组件的二次注册（FLAG_UPDATE_CURRENT）必须就地改写既有记录的 extras" +
                    "——这正是整改前「点旧候选、拉起新上下文」的机制；实际收到=$received",
                listOf("SECOND"),
                received
            )
        } finally {
            piFirst.cancel()
            piSecond.cancel()
            record.close()
        }
    }

    /**
     * 动态广播接收器 + 投递等待。
     *
     * 只用于**读出** PendingIntent 记录当前的 extras——`PendingIntent` 无公开 getter，
     * 「是否被覆写」只能经一次真实投递观察。`RECEIVER_NOT_EXPORTED` 使该探针仅接收
     * 本应用发出的广播，与生产 APK 的导出面无关。
     */
    private inner class RecordingBroadcastProbe {

        private val received = Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        private var latch = CountDownLatch(0)

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                intent?.getStringExtra(EXTRA_PROBE_VALUE)?.let { received.add(it) }
                latch.countDown()
            }
        }

        init {
            context.registerReceiver(receiver, IntentFilter(PROBE_ACTION), Context.RECEIVER_NOT_EXPORTED)
        }

        fun probeIntent(value: String): Intent = Intent(PROBE_ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_PROBE_VALUE, value)

        /** 设定本轮期望的投递次数；**必须在 `send()` 之前**调用 */
        fun expect(deliveries: Int) {
            latch = CountDownLatch(deliveries)
        }

        /** 阻塞至本轮投递完成，返回收到的值（顺序不保证） */
        fun await(): List<String> {
            val completed = latch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(
                "广播投递未在 ${PROBE_TIMEOUT_SECONDS}s 内完成，判别链路失效（已收到=$received）",
                completed
            )
            return received.toList()
        }

        fun close() {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val PROBE_ACTION = "com.keepasskey.probe.PENDING_INTENT_RECORD"
        const val EXTRA_PROBE_VALUE = "com.keepasskey.probe.VALUE"

        /** 两条记录各自投递一次 */
        const val EXPECTED_PROBE_DELIVERIES = 2
        const val PROBE_TIMEOUT_SECONDS = 5L
    }
}
