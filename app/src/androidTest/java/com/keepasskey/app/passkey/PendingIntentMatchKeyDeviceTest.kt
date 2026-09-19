package com.keepasskey.app.passkey

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
 * ## 为什么不用广播投递验证「覆写」
 *
 * 「`FLAG_UPDATE_CURRENT` 改写**已交付**实例」属平台文档化语义（本仓批次 84 亦据其定论），
 * 但设备侧验证它需要一条真实的投递链路（动态 receiver 的显式广播匹配语义易受实现细节影响），
 * 会使用例的**判别力被投递机制噪声污染**。故本用例只锚定**可静态判定、且正是匹配键本身**的
 * `Intent.filterEquals`——它是上述两处推论的充要前提。
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
}
