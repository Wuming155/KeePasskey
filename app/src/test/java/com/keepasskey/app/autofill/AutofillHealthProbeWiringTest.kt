package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-391` 的**接线守卫**（源码文本断言），与 [AutofillHealthPolicyTest]
 * （双路合成判定穷举）配对，范式同 `CredentialProviderHealthWiringTest`。
 *
 * 本项要防的失效形态是「回退路修了但链条断在某一处」：
 * ① manager 通道被回退路挤掉或被复制多处 ⇒ 双路退化回单路 / 两处口径漂移；
 * ② 回退读数无人合成 ⇒ 探了白探；③ 合成口径漂移进探针 ⇒ 纯函数用例失去锁定力；
 * ④ 组件名比对只认一种落盘形态 ⇒ 部分 ROM 上回退路恒空转；
 * ⑤ 「待真机实测」口径被顺手抹掉 ⇒ AC③ 的 OEM 行为面留痕义务落空。
 */
class AutofillHealthProbeWiringTest {

    private val probeSource: String
        get() = readRepoFile(PROBE)

    private val policySource: String
        get() = readRepoFile(HEALTH_POLICY)

    // ---------- AC①：manager false 时回退复核 Settings.Secure ----------

    @Test
    fun `manager 通道仍在且只此一处`() {
        assertEquals(
            "[$PROBE] hasEnabledAutofillServices 调用点数须恰为 1（被挤掉=双路退化，" +
                "复制多处=两路口径漂移）",
            1,
            Regex("hasEnabledAutofillServices\\(").findAll(probeSource).count()
        )
    }

    @Test
    fun `回退通道读 Settings_Secure 且合成口径单点在策略`() {
        assertTrue(
            "[$PROBE] 缺 Settings.Secure 回退读取（Monica 同款的双路核对未接上）",
            probeSource.contains("Settings.Secure.getString(")
        )
        assertTrue(
            "[$PROBE] 回退键必须是命名常量（禁止裸字面量散落）",
            probeSource.contains("\"autofill_service\"")
        )
        assertTrue(
            "[$PROBE] 未走 AutofillHealthPolicy.resolveSystemEnabled 合成——" +
                "口径漂移进探针即令纯函数用例失去锁定力",
            probeSource.contains("AutofillHealthPolicy.resolveSystemEnabled(")
        )
        assertTrue(
            "[$HEALTH_POLICY] 合成口径定义缺失（探针在调用一个不存在的函数）",
            policySource.contains("fun resolveSystemEnabled(")
        )
    }

    @Test
    fun `组件名比对收录全名与短名两种落盘形态`() {
        listOf("flattenToString()", "flattenToShortString()").forEach { form ->
            assertTrue(
                "[$PROBE] 组件名未收录 $form 形态——只认一种落盘形态时部分 ROM 上回退路恒空转",
                probeSource.contains(form)
            )
        }
    }

    // ---------- AC②③：口径注明与真机实测留痕义务 ----------

    @Test
    fun `判定口径与待真机实测留痕必须注明在源码`() {
        listOf(PROBE to probeSource, HEALTH_POLICY to policySource).forEach { (name, source) ->
            assertTrue(
                "[$name] 缺 ISSUE-P3-391 口径注明（AC②：单源→双路的判定口径必须落文字）",
                source.contains("ISSUE-P3-391")
            )
        }
        assertTrue(
            "[$HEALTH_POLICY] 缺「待真机实测」留痕口径（AC③：OEM 行为面须实测后回填，" +
                "整改时无真机也不得抹掉该义务）",
            policySource.contains("待真机实测")
        )
    }

    @Test
    fun `回退键不得与内部键 credential_service 混淆`() {
        // 读取失败兜底语义一致（判「未启用」），但两键身份不同：credential_service 对普通
        // 应用不可读（真机 SecurityException），autofill_service 是 Monica 实证可读的回退路。
        // 混写会同时毁掉两路。
        assertFalse(
            "[$PROBE] 不得读取内部键 credential_service（不可读且语义是 CM 通道的键）",
            probeSource.contains("\"credential_service\"")
        )
    }

    private fun readRepoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            val target = File(candidate, relativePath)
            if (target.isFile) return target.readText()
            dir = candidate.parentFile
        }
        error("无法定位仓库文件：$relativePath（起始：${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 4

        const val PROBE = "app/src/main/java/com/keepasskey/app/autofill/AutofillHealthProbe.kt"
        const val HEALTH_POLICY = "app/src/main/java/com/keepasskey/app/autofill/AutofillHealthPolicy.kt"
    }
}
