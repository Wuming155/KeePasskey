package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-372 AC③④⑦ 的接线守卫（静态源码比对，体例沿用 `AutofillChannelSwitchWiringTest`）。
 *
 * 兜底识别三段与记忆仓库都是「加性召回」——接错位置（如绕过字段级屏蔽、忘记注册锁定观察者）
 * 不会让编译失败，只会在真机上表现为该回补时没回补、或锁定后记忆残留。
 * 故断言全部指向接线形态：
 * 1. 解析编排：弱解析 → 聚焦补齐 → 记忆回补三段齐备，且回补在字段级屏蔽**之前**、
 *    记忆写入在屏蔽判定**之后**（先过闸门再记忆「确实可用」的结构）；
 * 2. 生命周期：记忆体由服务注入 + `DatabaseModule` 注册为会话终止观察者；
 * 3. 应用名维度：两处 `AutofillCandidateRanker.rank` 调用点都必须下传 `callingAppLabel`。
 */
class AutofillRecoveryWiringTest {

    private fun readSource(path: String): String {
        // 测试 JVM 工作目录不保证在仓库根（体例同 AutofillChannelSwitchWiringTest：向上找仓库根）
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            val file = File(candidate, path)
            if (file.isFile) return file.readText()
            dir = candidate.parentFile
        }
        error("源码缺失：$path（自 ${System.getProperty("user.dir")} 向上 $ROOT_SEARCH_DEPTH 层未定位仓库根）")
    }

    private val resolverSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillTargetFieldResolver.kt")

    private val serviceSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt")

    private val databaseModuleSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/di/DatabaseModule.kt")

    // ===== 编排三段齐备且次序正确 =====

    @Test
    fun `解析编排包含弱解析聚焦补齐与记忆回补三段`() {
        assertTrue("AC① 弱目标二次解析未接入", resolverSource.contains("AutofillFieldFallback.runWeakReparse"))
        assertTrue("AC② 聚焦字段补齐未接入", resolverSource.contains("AutofillFieldFallback.synthesizeFocusedUsername"))
        assertTrue("AC③ 记忆回补未接入", resolverSource.contains("recoverLoginFieldsFromMemory"))
        assertTrue("AC③ 记忆写入未接入", resolverSource.contains("rememberLoginFields"))
    }

    @Test
    fun `记忆回补先于字段级屏蔽而记忆写入晚于屏蔽判定`() {
        val recoverIndex = resolverSource.indexOf("recoverLoginFieldsFromMemory")
        val decisionIndex = resolverSource.indexOf("AutofillFieldBlockPolicy.decide")
        val rememberIndex = resolverSource.indexOf("rememberLoginFields(")
        assertTrue("编排顺序锚点缺失", recoverIndex >= 0 && decisionIndex >= 0 && rememberIndex >= 0)
        assertTrue(
            "回补目标必须参与字段级屏蔽判定（回补在 decide 之前）",
            recoverIndex < decisionIndex
        )
        assertTrue(
            "记忆写入必须发生在屏蔽判定通过之后（只记确实可用的结构）",
            rememberIndex > decisionIndex
        )
    }

    @Test
    fun `弱解析有登录上下文门且在首轮零目标后触发`() {
        val weakIndex = resolverSource.indexOf("AutofillFieldFallback.runWeakReparse")
        val zeroGuard = resolverSource.indexOf("scanResult.usernameId == null && scanResult.passwordId == null")
        assertTrue("弱解析入口锚点缺失", weakIndex >= 0)
        assertTrue(
            "弱解析必须由『首轮零登录目标』条件守门",
            zeroGuard in 0 until weakIndex
        )
        // 兜底本体的登录上下文门（源码级锚）
        val fallbackSource = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillFieldFallback.kt")
        assertTrue(
            "弱解析必须保留登录上下文门（防非登录页误弹）",
            fallbackSource.contains("isPasswordLabel(haystackOf(it))")
        )
    }

    // ===== 生命周期接线 =====

    @Test
    fun `填充服务注入记忆体`() {
        assertTrue(
            "KeePasskeyAutofillService 必须注入 AutofillLoginFieldMemory",
            serviceSource.contains("lateinit var loginFieldMemory: AutofillLoginFieldMemory")
        )
    }

    @Test
    fun `记忆体注册为会话终止观察者`() {
        assertTrue(
            "DatabaseModule 必须把记忆体注册为锁定观察者（锁定即清，AC③ 生命周期红线）",
            databaseModuleSource.contains("addLockObserver(loginFieldMemory)")
        )
    }

    // ===== 应用名维度两处调用点 =====

    @Test
    fun `两处打分调用点都下传调用方应用名`() {
        // ISSUE-P3-528：服务端装配处的调用点已自 AutofillDatasetBuilders.kt 拆至
        // AutofillUnlockedCandidates.kt（行数闸门）；判据不变，只更新被读文件
        val buildersSource = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockedCandidates.kt")
        val unlockSource = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockActivity.kt")
        assertTrue("服务端装配未下传应用名", buildersSource.contains("callingAppLabel = callerAppLabelOrNull("))
        assertTrue("解锁路由未下传应用名", unlockSource.contains("callingAppLabel = callerAppLabelOrNull("))
    }

    @Test
    fun `应用名取数失败回落空且不阻断填充`() {
        val labelSource = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillCallerAppLabel.kt")
        assertTrue("取数必须 catch 包不存在异常并回落 null", labelSource.contains("NameNotFoundException"))
        assertTrue("空白包名直接回落 null", labelSource.contains("callingPackage.isBlank()"))
    }

    // ===== Scanner 诊断标记 =====

    @Test
    fun `扫描结果携带弱解析标记供诊断断言`() {
        val scannerSource = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt")
        assertTrue("ScanResult 应携带 usedWeakReparse 诊断标记", scannerSource.contains("usedWeakReparse"))
        assertEquals(
            "标记默认值必须为 false（首轮语义不变）",
            1,
            Regex("""val usedWeakReparse: Boolean = false""").findAll(scannerSource).count()
        )
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 6
    }
}
