package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.EntryTotpSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillSessionGrantStore] 与 [AutofillAuthenticationPolicy] 单元测试（ISSUE-P3-42）。
 *
 * 以注入的假时钟覆盖 TTL 语义（无需 Android `SystemClock`）。
 */
class AutofillSessionGrantStoreTest {

    private var now = 0L

    private fun newStore(ttlMillis: Long = 1_000L) =
        AutofillSessionGrantStore(ttlMillis = ttlMillis, elapsedRealtime = { now })

    @Test
    fun `授权后同上下文命中`() {
        val store = newStore()
        val context = AutofillGrantContext("com.example.app", "github.com")

        store.grant(context)

        assertTrue(store.isGranted(context))
    }

    @Test
    fun `TTL 到期后失效`() {
        val store = newStore(ttlMillis = 1_000L)
        val context = AutofillGrantContext("com.example.app", "github.com")
        store.grant(context)

        now = 999L
        assertTrue(store.isGranted(context))

        now = 1_000L
        assertFalse(store.isGranted(context))
    }

    @Test
    fun `包名或域不匹配即失效`() {
        val store = newStore()
        store.grant(AutofillGrantContext("com.example.app", "github.com"))

        assertFalse(store.isGranted(AutofillGrantContext("com.other.app", "github.com")))
        assertFalse(store.isGranted(AutofillGrantContext("com.example.app", "gitlab.com")))
        assertFalse(store.isGranted(AutofillGrantContext("com.example.app", null)))
    }

    @Test
    fun `上下文归一化后可命中`() {
        val store = newStore()
        store.grant(AutofillGrantContext("  COM.Example.App  ", "https://WWW.GitHub.com/login"))

        assertTrue(store.isGranted(AutofillGrantContext("com.example.app", "github.com")))
    }

    @Test
    fun `clear 后失效`() {
        val store = newStore()
        val context = AutofillGrantContext("com.example.app", "github.com")
        store.grant(context)

        store.clear()

        assertFalse(store.isGranted(context))
    }

    /**
     * ISSUE-P2-81（复核 NEW-N5）：域**不可归属**时不得形成授权，也不得命中授权。
     *
     * 缺陷形态：`normalized()` 把空 / 不可归属域归一为 `null`，而授权匹配用 `==` 比较整个
     * `AutofillGrantContext` → `null == null` 成立 ⇒ 开关开启后 30 秒 TTL 内，同包名的一切
     * 「域不可归属」表单（含攻击者伪造的不可归属域）都免二次确认。
     */
    @Test
    fun `域不可归属时不建立授权也不命中（伪造不可归属域仍需确认）`() {
        val store = newStore()

        // 确认页在无域场景写入的授权（EXTRA_GRANT_DOMAIN 为空 / 空白 / 仅 scheme）
        store.grant(AutofillGrantContext("com.example.app", null))
        assertFalse(
            "无域授权不得命中无域请求（否则伪造的不可归属域全程免确认）",
            store.isGranted(AutofillGrantContext("com.example.app", null))
        )

        store.grant(AutofillGrantContext("com.example.app", "   "))
        assertFalse(
            "空白域与 null 同属不可归属，同样不得命中",
            store.isGranted(AutofillGrantContext("com.example.app", "   "))
        )

        store.grant(AutofillGrantContext("com.example.app", "https://"))
        assertFalse(
            "仅 scheme、无主机的域属不可归属，不得命中",
            store.isGranted(AutofillGrantContext("com.example.app", "https://"))
        )
    }

    @Test
    fun `无域确认不建立授权且不得冒领可归属域授权`() {
        val store = newStore()
        val attributable = AutofillGrantContext("com.example.app", "github.com")
        store.grant(attributable)
        assertTrue(store.isGranted(attributable))

        // 同包名的一次「无域」确认：既不新增授权，也不能替无域请求冒领已有的域授权
        store.grant(AutofillGrantContext("com.example.app", null))

        assertFalse(
            "无域请求不得冒领可归属域授权",
            store.isGranted(AutofillGrantContext("com.example.app", null))
        )
        assertTrue(
            "既有可归属域授权不受无域确认影响（TTL 内仍命中）",
            store.isGranted(attributable)
        )
    }

    @Test
    fun `未授权时任何上下文都不命中`() {
        val store = newStore()
        assertFalse(store.isGranted(AutofillGrantContext("com.example.app", "github.com")))
    }

    @Test
    fun `仅当开关开启且库已解锁且有授权时才跳过重复确认`() {
        assertTrue(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = true,
                datasetCarriesSensitiveValue = false
            )
        )
        // 开关关闭：不跳过（保持每次强制确认）
        assertFalse(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = false, vaultLocked = false, grantActive = true,
                datasetCarriesSensitiveValue = false
            )
        )
        // 库锁定：授权一律不适用（必须先解锁）
        assertFalse(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = true, grantActive = true,
                datasetCarriesSensitiveValue = false
            )
        )
        // 无有效授权：不跳过
        assertFalse(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = false,
                datasetCarriesSensitiveValue = false
            )
        )
    }

    @Test
    fun `ISSUE-P1-24 AC3 数据集携带口令值时授权宽限不生效`() {
        // 口令字段不得经无 UI 的自动途径下发：即便授权宽限全部命中，携带口令值的数据集
        // 仍必须走显式确认；用户名字段可例外（不携带口令值时宽限照常生效）
        assertTrue(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = true,
                datasetCarriesSensitiveValue = false
            )
        )
        assertFalse(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = true,
                datasetCarriesSensitiveValue = true
            )
        )
    }

    /**
     * ISSUE-P3-577：**当前 TOTP 直填值计入敏感值**——授权宽限内「仅用户名 + OTP 框、
     * 无口令框」的数据集仍不得跳过二次确认。
     *
     * 缺陷形态（整改前）：豁免判据只统计口令字段 ⇒ 交付不含口令但含 `calculateEntryTotp`
     * 当前码的数据集时，框架把用户名 + 当前验证码**直填表单且无确认 UI**；「仅用户名 +
     * OTP 框、无口令框」的表单可独立成立，攻击者（已过归属校验的调用方）在 30 秒宽限窗口内
     * 即可静默取得当前验证码（30~90 秒有效）。故 TOTP 与口令同属敏感值，任一命中即不豁免。
     */
    @Test
    fun `ISSUE-P3-577 数据集仅携带当前 TOTP 直填值时授权宽限不生效`() {
        // 交付面：无口令框、有 OTP 框、候选将直填当前 TOTP 码 ⇒ 携带敏感值 ⇒ 不跳过确认
        assertFalse(
            "含当前 TOTP 直填值的数据集在宽限期内仍必须挂 setAuthentication",
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = true,
                datasetCarriesSensitiveValue = AutofillAuthenticationPolicy.carriesSensitiveValue(
                    hasDeliverablePassword = false,
                    hasDeliverableTotp = true
                )
            )
        )
        // 纯用户名通道（口令 / TOTP 均无）：既有「用户名字段可例外」意图逐字保留，宽限照常生效
        assertTrue(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = true,
                datasetCarriesSensitiveValue = AutofillAuthenticationPolicy.carriesSensitiveValue(
                    hasDeliverablePassword = false,
                    hasDeliverableTotp = false
                )
            )
        )
    }

    /**
     * ISSUE-P3-577：TOTP 直填口径为「**非 HOTP 且当前码非空**」，与交付面
     * `AutofillDatasetBuilders.buildCandidateDataset` 共用同一谓词（单一事实源）。
     *
     * HOTP 排除理由（ISSUE-P3-298 ⑤）：HOTP 的当前码不推进计数器，直填会给出与服务端
     * 不同步的旧值，故 HOTP 条目根本不参与直填 ⇒ 也不计入敏感值。
     */
    @Test
    fun `ISSUE-P3-577 TOTP 直填口径为非 HOTP 且当前码非空`() {
        val totp = EntryTotpSnapshot(
            code = "123456", periodSeconds = 30, digits = 6, algorithm = "SHA1"
        )
        assertTrue("非 HOTP 且码非空 ⇒ 构成直填值", AutofillAuthenticationPolicy.totpIsDeliverable(totp))
        assertFalse(
            "无 OTP / 计算失败（快照为 null）不判携带",
            AutofillAuthenticationPolicy.totpIsDeliverable(null)
        )
        assertFalse(
            "HOTP 条目不参与直填 ⇒ 不计入敏感值",
            AutofillAuthenticationPolicy.totpIsDeliverable(totp.copy(isHotp = true))
        )
        assertFalse(
            "空码不构成直填值",
            AutofillAuthenticationPolicy.totpIsDeliverable(totp.copy(code = ""))
        )
    }

    /**
     * ISSUE-P3-185：手动选择器路径的宽限判定。
     *
     * 与数据集路径的差异：选择器每次填充都由用户在受保护窗口内检索并**显式点选**条目
     * （本身即一次显式指认），不存在「无 UI 自动途径下发口令」的形态，故不设
     * 「携带口令值」闸门——宽限豁免的仅是重复的生物识别；其余判定与数据集路径一致
     * （开关开启 + 库已解锁 + 有效授权，三者缺一不可）。
     */
    @Test
    fun `ISSUE-P3-185 选择器路径 开关开启且库已解锁且有授权时跳过生物识别`() {
        assertTrue(
            AutofillAuthenticationPolicy.skipPickerRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = true
            )
        )
        // 开关关闭：不跳过（保持每次生物识别确认）
        assertFalse(
            AutofillAuthenticationPolicy.skipPickerRepeatConfirmation(
                sessionGrantEnabled = false, vaultLocked = false, grantActive = true
            )
        )
        // 库锁定：授权一律不适用（必须先解锁）
        assertFalse(
            AutofillAuthenticationPolicy.skipPickerRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = true, grantActive = true
            )
        )
        // 无有效授权：不跳过
        assertFalse(
            AutofillAuthenticationPolicy.skipPickerRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = false
            )
        )
    }
}
