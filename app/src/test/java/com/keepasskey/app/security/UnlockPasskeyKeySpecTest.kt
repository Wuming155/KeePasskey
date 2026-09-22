package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 解锁断言私钥**规格守卫**（`ISSUE-P1-09` 撤回项 / 本批回归防线）。
 *
 * 断言对象分两层：
 * 1. **策略取值**（[UnlockPasskeyKeyPolicy]）——纯常量，宿主可直接断言；
 * 2. **生产接线**（源码文本）——规格取值是否真的喂给了 `KeyGenParameterSpec`、
 *    断言读路径是否真的不轮换密钥。这两条都是结构性约束：运行时用例覆盖不到
 *    （少写/多写一个认证参数不会有任何宿主用例变红，只会在真机上让**每一次**
 *    快速解锁 fail-closed，宿主 JVM 无 AndroidKeyStore 无法复现）。
 *
 * 为什么必须钉死：断言私钥一旦重新绑定用户认证（`setUserAuthenticationParameters`），
 * 其授权路径与快速解锁的「一次 `BiometricPrompt` + 单 `CryptoObject`」结构互斥
 * （官方指南：带时间窗的密钥不得传 `CryptoObject` 且须允许回退到非生物识别凭据），
 * 断言签名会恒抛 `UserNotAuthenticatedException` → 用户可见「快速解锁凭据校验未通过」。
 * 详见 [UnlockPasskeyKeyPolicy] KDoc。
 */
class UnlockPasskeyKeySpecTest {

    private val keyMaterialSource: String
        get() = readSource(KEY_MATERIAL)

    private val passkeyManagerSource: String
        get() = readSource(PASSKEY_MANAGER)

    // ── 1. 策略取值 ────────────────────────────────────────────────

    @Test
    fun `断言私钥不得要求用户认证`() {
        assertFalse(
            "断言私钥必须不绑定用户认证：绑定后在快速解锁流程下恒不可授权（详见 UnlockPasskeyKeyPolicy KDoc）",
            UnlockPasskeyKeyPolicy.REQUIRES_USER_AUTHENTICATION
        )
    }

    @Test
    fun `断言私钥保留设备解锁态约束`() {
        assertTrue(
            "断言私钥应保留 setUnlockedDeviceRequired(true)（只约束私钥操作，无可用性代价）",
            UnlockPasskeyKeyPolicy.UNLOCKED_DEVICE_REQUIRED
        )
    }

    // ── 2. 生产接线 ────────────────────────────────────────────────

    @Test
    fun `断言私钥规格由策略单点声明`() {
        val spec = functionBody(keyMaterialSource, "private fun buildUnlockPasskeySpec(")

        assertTrue(
            "断言私钥规格必须消费 UnlockPasskeyKeyPolicy.REQUIRES_USER_AUTHENTICATION（不得另写位或表达式）",
            spec.contains("setUserAuthenticationRequired(UnlockPasskeyKeyPolicy.REQUIRES_USER_AUTHENTICATION)")
        )
        assertTrue(
            "断言私钥规格必须消费 UnlockPasskeyKeyPolicy.UNLOCKED_DEVICE_REQUIRED",
            spec.contains("setUnlockedDeviceRequired(UnlockPasskeyKeyPolicy.UNLOCKED_DEVICE_REQUIRED)")
        )
    }

    @Test
    fun `断言私钥规格不得出现任何认证器集合声明`() {
        val spec = functionBody(keyMaterialSource, "private fun buildUnlockPasskeySpec(")

        assertFalse(
            "断言私钥规格不得出现 setUserAuthenticationParameters(...)——该形态在快速解锁流程下恒不可授权",
            spec.contains("setUserAuthenticationParameters")
        )
        assertFalse(
            "断言私钥规格不得声明认证器集合（AUTH_BIOMETRIC_STRONG / AUTH_DEVICE_CREDENTIAL）",
            spec.contains("AUTH_BIOMETRIC_STRONG") || spec.contains("AUTH_DEVICE_CREDENTIAL")
        )
        assertFalse(
            "断言私钥规格不得声明认证时间窗（setUserAuthenticationValidityDurationSeconds）",
            spec.contains("ValidityDurationSeconds")
        )
    }

    @Test
    fun `断言读路径不得轮换密钥`() {
        val readPath = functionBody(keyMaterialSource, "fun getOrCreateUnlockPasskeyPair(")

        assertFalse(
            "断言读路径不得删除既有别名（轮换会让新公钥与既有登记记录脱钩 → 每次快速解锁都失败）",
            readPath.contains("deleteEntry") || readPath.contains("deleteKey")
        )
        assertFalse(
            "断言读路径不得做规格探测（KeyInfo / KeyFactory），探测失败即重建即「永久失败」形态",
            readPath.contains("KeyInfo") || readPath.contains("KeyFactory")
        )
        assertTrue(
            "断言读路径必须仍能按需生成（别名不存在时）",
            readPath.contains("generateUnlockPasskeyPair(")
        )
    }

    @Test
    fun `密钥轮换只发生在登记序列内`() {
        val enroll = functionBody(passkeyManagerSource, "fun enroll(")

        assertTrue(
            "登记必须删除旧别名后重建（轮换的唯一入口）",
            enroll.contains("keystoreManager.deleteKey(alias)")
        )
        assertTrue(
            "登记必须重建密钥对并写回登记记录（公钥与记录同步刷新，不存在中间态）",
            enroll.contains("keystoreManager.getOrCreateUnlockPasskeyPair(alias)") &&
                enroll.contains("saveUnlockPasskey(")
        )
    }

    // ── 源码读取 ───────────────────────────────────────────────────

    /** 取函数签名之后的第一个函数体（至 4 空格缩进的收尾花括号），用于把断言限定在函数范围内 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源文件中找不到函数：$signature", start >= 0)
        val body = source.substring(start)
        val end = body.indexOf("\n    }")
        assertTrue("函数体未正常闭合：$signature", end > 0)
        return body.substring(0, end)
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val KEY_MATERIAL = "app/src/main/java/com/keepasskey/app/security/KeystoreKeyMaterial.kt"
        const val PASSKEY_MANAGER = "app/src/main/java/com/keepasskey/app/security/UnlockPasskeyManager.kt"

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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
