package com.keepasskey.app.data.repository

import com.keepasskey.app.testutil.stripCommentsOnly
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * TOTP 字段映射与默认参数的**接线闭环**守卫（**ISSUE-P3-273** AC④）。
 *
 * 整改前的两类缺陷：
 * 1. **展示选择未应用**——用户在「双重认证 (TOTP) 设置」页配的字段名与默认步长 / 位数
 *    解析侧一概不理（定位写死官方 `otp` 字段 + `TOTP` 前缀，缺省写死 `30` / `6`）；
 * 2. **改了不落盘**——`updateTotpFieldMapping` 只 `publish` 内存快照，重启即回默认值。
 *
 * 本用例分三层锁定闭环保真：
 * - **行为层**：设置值确实改变解析结果与取码参数，且**不削弱**官方 `otp` / `TOTP` 前缀回退
 *   （AC③ 的「官方 / 第三方兼容性不放松」）；
 * - **钳制层**：设置值本身越界时仍回落内置常量，**不得绕过** `TotpKeyUriParser` 的钳制语义；
 * - **静态层**：落盘通道与「三处读取共用单一真相源」由源码判据锁定，防后续回退成
 *   「又写一份优先序」或「又只改内存」。
 */
class TotpFieldMappingWiringTest {

    private val mapper = VaultEntryMapper(StringsProvider { id, _ -> "s$id" })

    private fun base32(text: String = SECRET): ProtectedString = ProtectedString(text)

    private fun configText(config: com.keepasskey.core.otp.ParsedTotpConfig): String =
        String(config.secret, StandardCharsets.US_ASCII)

    // ========== AC③ 行为层：设置值优先、官方键回退 ==========

    @Test
    fun `设置字段名优先于官方 otp 字段`() {
        val entry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.OTP to base32(OTHER_SECRET)),
            customFields = listOf(KdbxCustomField("MyOtpauth", base32(SECRET)))
        )

        val config = mapper.parseTotpConfig(entry, TotpPreferences.DEFAULT.copy(seedFieldName = "MyOtpauth"))

        assertEquals("设置值命中时必须用它，而不是官方 otp 字段", SECRET, configText(config!!))
        config.secret.fill(0)
    }

    @Test
    fun `设置值未命中时回退官方 otp 字段`() {
        val entry = KdbxEntry(fields = mapOf(KdbxConstants.Fields.OTP to base32(SECRET)))

        val config = mapper.parseTotpConfig(entry, TotpPreferences.DEFAULT.copy(seedFieldName = "NoSuchField"))

        assertEquals("官方 otp 字段是兼容性底线，不得因设置值存在而移除", SECRET, configText(config!!))
        config.secret.fill(0)
    }

    @Test
    fun `设置值未命中时回退 TOTP 前缀自定义字段`() {
        val entry = KdbxEntry(
            customFields = listOf(KdbxCustomField("TOTP Seed", base32(SECRET)))
        )

        val config = mapper.parseTotpConfig(entry, TotpPreferences.DEFAULT.copy(seedFieldName = "NoSuchField"))

        assertEquals("TOTP 前缀自定义字段须保留为回退", SECRET, configText(config!!))
        config.secret.fill(0)
    }

    @Test
    fun `设置字段名本身参与定位且忽略大小写`() {
        val entry = KdbxEntry(
            customFields = listOf(KdbxCustomField("tOTP sEED", base32(SECRET)))
        )

        val config = mapper.parseTotpConfig(entry, TotpPreferences.DEFAULT)

        assertEquals(SECRET, configText(config!!))
        config.secret.fill(0)
    }

    @Test
    fun `无任何 TOTP 来源时返回 null`() {
        assertNull(mapper.parseTotpConfig(KdbxEntry(fields = emptyMap()), TotpPreferences.DEFAULT))
    }

    // ========== AC③ 参数层：缺省值生效但不得覆盖条目声明 ==========

    @Test
    fun `默认步长与位数在条目未声明时生效`() {
        // 纯 Base32 种子（无 otpauth 参数）⇒ 周期 / 位数只能来自设置值
        val entry = KdbxEntry(fields = mapOf(KdbxConstants.Fields.OTP to base32()))
        val preferences = TotpPreferences.DEFAULT.copy(defaultStepSeconds = 60, defaultDigits = 8)

        val config = mapper.parseTotpConfig(entry, preferences)

        assertEquals(60, config!!.period)
        assertEquals(8, config.digits)
        config.secret.fill(0)
    }

    @Test
    fun `条目已声明的周期与位数不被设置值覆盖`() {
        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.OTP to base32("otpauth://totp/L?secret=$SECRET&period=30&digits=6")
            )
        )
        val preferences = TotpPreferences.DEFAULT.copy(defaultStepSeconds = 60, defaultDigits = 8)

        val config = mapper.parseTotpConfig(entry, preferences)

        assertEquals("条目明示的 period 优先", 30, config!!.period)
        assertEquals("条目明示的 digits 优先", 6, config.digits)
        config.secret.fill(0)
    }

    @Test
    fun `越界的设置值仍受内置钳制不得绕过`() {
        val entry = KdbxEntry(fields = mapOf(KdbxConstants.Fields.OTP to base32()))
        // 周期非正 + 位数 9（**不在** 6..8 内，7 是合法值故不能用作越界样本）
        // ——两者都必须回落内置常量而非原样采用
        val preferences = TotpPreferences.DEFAULT.copy(defaultStepSeconds = 0, defaultDigits = 9)

        val config = mapper.parseTotpConfig(entry, preferences)

        assertEquals("非正周期回落内置 30", 30, config!!.period)
        assertEquals("越界位数回落内置 6", 6, config.digits)
        config.secret.fill(0)
    }

    // ========== AC④ 缓存层：解析参数变更即失效 ==========

    @Test
    fun `解析参数变更后验证码缓存立即失效并重算`() = runTest {
        val entry = KdbxEntry(fields = mapOf(KdbxConstants.Fields.OTP to base32()))
        var preferences = TotpPreferences.DEFAULT
        val reader = readerFor(listOf(entry), { preferences }, { BASE_MILLIS })
        val id = entry.id.toHexString()

        val first = reader.calculateEntryTotp(id)
        assertEquals("缺省 6 位", 6, first?.code?.length)
        assertSame("参数未变时同周期仍须命中缓存", first, reader.calculateEntryTotp(id))

        preferences = TotpPreferences.DEFAULT.copy(defaultDigits = 8)
        val next = reader.calculateEntryTotp(id)

        assertNotSame("设置值变更后不得继续下发按旧参数算出的码", first, next)
        assertEquals(8, next?.code?.length)
    }

    // ========== AC① 落盘层 + 单一真相源（源码判据） ==========

    @Test
    fun `TOTP 映射写入走统一变更通道且偏好键读写两侧齐备`() {
        val controller = stripCommentsOnly(readSource(PREFERENCES_CONTROLLER))
        val body = functionBody(controller, "fun updateTotpFieldMapping(")

        assertTrue(
            "必须走 updateExtended（内存 + 持久化原子完成），否则重启回默认值的缺陷复活",
            body.contains("updateExtended")
        )
        assertTrue("必须写入四个映射字段", body.contains("totpSeedFieldName") && body.contains("defaultTotpDigits"))
        assertFalse(
            "不得再退回「只 publish 内存快照、不落盘」的旧形态",
            body.contains("publish(") && !body.contains("updateExtended")
        )

        val store = readSource(STORE)
        listOf(
            "K_TOTP_SEED_FIELD_NAME",
            "K_TOTP_SETTINGS_FIELD_NAME",
            "K_DEFAULT_TOTP_STEP_SECONDS",
            "K_DEFAULT_TOTP_DIGITS"
        ).forEach { key ->
            assertTrue("$key 缺读侧", store.contains(key))
            assertTrue("$key 缺写侧", store.contains(".putString($key") || store.contains(".putInt($key"))
        }
    }

    @Test
    fun `字段定位在解析与回填三处共用同一实现`() {
        val reader = stripCommentsOnly(readSource(SECRET_READER))
        val mapper = stripCommentsOnly(readSource(ENTRY_MAPPER))

        assertEquals(
            "两处回填（编辑页 / 修订快照）必须同走 locateConfigSource，禁各自再写一份优先序",
            2,
            countInvocationsOf(reader, "VaultEntryTotpMapping.locateConfigSource")
        )
        assertTrue(
            "第三处（解析侧）经 mapper 委派到同一实现",
            mapper.contains("VaultEntryTotpMapping.parseTotpConfig(entry, preferences)")
        )
        assertTrue(
            "定位实现本体只有一份（VaultEntryTotpMapping）",
            stripCommentsOnly(readSource(TOTP_MAPPING)).contains("fun locateConfigSource(")
        )
        assertFalse(
            "回填路径不得再本地重写 TOTP 前缀匹配（与解析侧漂移即出错）",
            reader.contains("TOTP_CUSTOM_FIELD_PREFIX")
        )
        assertTrue(
            "解析参数必须先取快照再解析（参数变更须能被缓存失效判据看见）",
            reader.contains("val preferences = totpPreferences()")
        )
        assertTrue(
            "缓存命中判据须包含解析参数等值",
            reader.contains("cached.preferences != totpPreferences()")
        )
    }

    @Test
    fun `生产通道按内存权威快照取解析参数`() {
        val wiring = stripCommentsOnly(readSource(TOTP_PREFERENCES))
        assertTrue("生产绑定须读进程级内存快照（热路径逐条目读取）", wiring.contains("store.settings.value"))
        assertTrue(
            "缺省值须与 ExtendedSettings 同源（禁另立常量造成两处漂移）",
            wiring.contains("ExtendedSettings()")
        )
    }

    // ========== helpers ==========

    private fun readerFor(
        entries: List<KdbxEntry>,
        preferences: () -> TotpPreferences,
        clock: () -> Long
    ): VaultEntrySecretReader {
        val session = DatabaseSession()
        session.setDatabaseForTesting(
            KdbxDatabase(
                header = KdbxHeader.createDefault(useArgon2 = false),
                rootGroup = KdbxGroup(name = "Root", entries = entries)
            )
        )
        return VaultEntrySecretReader(
            session,
            VaultEntryMapper(StringsProvider { _, _ -> "" }),
            clock,
            preferences
        )
    }

    private fun countInvocationsOf(source: String, token: String): Int {
        var count = 0
        var at = source.indexOf(token)
        while (at >= 0) {
            count++
            at = source.indexOf(token, at + token.length)
        }
        return count
    }

    /** 按花括号配平提取函数体（调用前须先剔除注释） */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到签名：$signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("签名缺少函数体：$signature", open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("函数体未闭合：$signature")
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** RFC 6238 附录 B 测试种子（非机密常量） */
        const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        /** 另一条合法 Base32（用于断言「取的是哪一条」） */
        const val OTHER_SECRET = "JBSWY3DPEHPK3PXP"

        /** 固定基准时刻：`% 30 = 20`，距周期边界 10 秒，同周期断言可确定性成立 */
        const val BASE_MILLIS = 1_700_000_000_000L

        const val STORE =
            "app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt"
        const val SECRET_READER =
            "app/src/main/java/com/keepasskey/app/data/repository/VaultEntrySecretReader.kt"
        const val ENTRY_MAPPER =
            "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt"
        const val TOTP_MAPPING =
            "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt"
        const val TOTP_PREFERENCES =
            "app/src/main/java/com/keepasskey/app/data/repository/TotpPreferences.kt"
        const val PREFERENCES_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt"

        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
