package com.keepasskey.app.ui.screens.settings

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.FakeRuntimeIntegrityGate
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import javax.crypto.Cipher

/**
 * ISSUE-P2-212：设置页「开启生物识别开关必须当场验证」单测。
 *
 * 覆盖验收标准：
 * 1. 打开开关 → 发起一次强生物识别验证，**通过后才写入偏好**；
 * 2. 用户取消 / 系统错误 / 设备无可用强生物识别 → **不写偏好**（开关保持关闭）并给出提示；
 * 3. 关闭开关无需验证，即时落偏好，**并撤销全部生物识别数据**（ISSUE-P2-253「关闭 = 删除」）；
 * 4. 验证进行中的重复点击不并发发起第二次验证（防抖）；
 * 5. 单次比对未通过（`Failed`）不是终态——系统弹窗驻留重试，最终成功仍须启用；
 * 6. 封印凭据的解密 Cipher 准备失败（密钥被吊销）→ 清除陈旧凭据并退化为纯身份验证；
 * 7. 源码守卫：登记协调器的「拒绝降级关闭」路径须撤销全部封印数据，且封印落库前须复核开关。
 *
 * 测试边界如实说明：Android `BiometricPrompt` 与 `FragmentActivity` 无法在 JVM 中构造，
 * 故两处 Android 触点经 `*Override` 替身注入；`setEnabled` 的 activity 参数传 null，
 * 真实探测/发起路径（生产实现）在无宿主时 fail-closed，本用例不覆盖该分支的 Android 实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricEnableCoordinatorTest {

    private class Fixture(
        val settings: FakeSettingsRepository,
        val state: MutableStateFlow<BiometricToggleUiState>,
        val coordinator: BiometricEnableCoordinator
    )

    private fun TestScope.createFixture(
        storage: BiometricCredentialStorage? = null,
        authManager: BiometricAuthManager? = null,
        databaseId: String? = ACTIVE_DB_ID
    ): Fixture {
        val settings = FakeSettingsRepository()
        val state = MutableStateFlow(BiometricToggleUiState())
        val coordinator = BiometricEnableCoordinator(
            scope = this,
            settingsRepository = settings,
            activeDbId = { databaseId },
            biometricAuthManager = authManager,
            biometricCredentialStorage = storage,
            strings = StringsProvider { _, _ -> "" },
            debugLog = DebugLogBuffer(),
            state = state
        )
        return Fixture(settings, state, coordinator)
    }

    private suspend fun FakeSettingsRepository.current(): UserSettings = getSettings().first()

    // ── 1. 开启：验证通过才启用 ─────────────────────────────────────────────

    @Test
    fun `开启开关验证通过后才写入偏好并提示凭据待登记`() = runTest {
        val f = createFixture()
        f.coordinator.strongBiometricAvailableOverride = { true }
        f.coordinator.promptOverride = { _, _, onResult -> onResult(BiometricResult.Success(null)) }

        f.coordinator.setEnabled(true, null)
        advanceUntilIdle()

        assertTrue("验证通过后必须写入偏好", f.settings.current().biometricEnabled)
        assertEquals(
            "无封印凭据时应提示「下次主密码解锁后完成登记」",
            UiMessage(R.string.sec_biometric_enable_pending_seal),
            f.state.value.notice
        )
        assertFalse("流程结束必须复位验证中状态", f.state.value.verifying)
    }

    @Test
    fun `用户取消验证时开关保持关闭且不写偏好`() = runTest {
        val f = createFixture()
        f.coordinator.strongBiometricAvailableOverride = { true }
        f.coordinator.promptOverride = { _, _, onResult -> onResult(BiometricResult.Cancelled) }

        f.coordinator.setEnabled(true, null)
        advanceUntilIdle()

        assertFalse("取消后开关必须保持关闭", f.settings.current().biometricEnabled)
        assertEquals(UiMessage(R.string.sec_biometric_auth_failed), f.state.value.notice)
        assertFalse(f.state.value.verifying)
    }

    @Test
    fun `系统错误时开关保持关闭并映射失败文案`() = runTest {
        val f = createFixture()
        f.coordinator.strongBiometricAvailableOverride = { true }
        f.coordinator.promptOverride = { _, _, onResult ->
            onResult(BiometricResult.Error(BiometricAuthManager.ERROR_AUTH_TIMEOUT, "AUTH_TIMEOUT"))
        }

        f.coordinator.setEnabled(true, null)
        advanceUntilIdle()

        assertFalse(f.settings.current().biometricEnabled)
        assertEquals(UiMessage(R.string.sec_biometric_auth_failed), f.state.value.notice)
    }

    // ── 2. 设备能力闸门（fail-closed）────────────────────────────────────

    @Test
    fun `设备无可用强生物识别时不发起验证且不写偏好`() = runTest {
        val f = createFixture()
        var promptCalls = 0
        f.coordinator.strongBiometricAvailableOverride = { false }
        f.coordinator.promptOverride = { _, _, _ -> promptCalls++ }

        f.coordinator.setEnabled(true, null)
        advanceUntilIdle()

        assertEquals("能力校验不过时不得发起 BiometricPrompt", 0, promptCalls)
        assertFalse(f.settings.current().biometricEnabled)
        assertEquals(UiMessage(R.string.sec_biometric_enable_unavailable), f.state.value.notice)
    }

    // ── 3. 关闭：无需验证 + 撤销全部数据（ISSUE-P2-253）─────────────────

    @Test
    fun `关闭开关无需验证即时落偏好并清除提示`() = runTest {
        val f = createFixture()
        f.settings.setBiometricEnabled(true)

        f.coordinator.setEnabled(false, null)
        advanceUntilIdle()

        assertFalse(f.settings.current().biometricEnabled)
        assertNull("关闭动作不应残留任何提示", f.state.value.notice)
        assertFalse(f.state.value.verifying)
    }

    @Test
    fun `关闭开关撤销全部封印凭据与断言登记记录`() = runTest {
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential("db_a", IV, CIPHERTEXT)
        storage.saveEncryptedCredential("db_b", IV, CIPHERTEXT)
        storage.saveUnlockPasskey("db_a", "PUB_A", "CRED_A", 1)
        storage.saveUnlockPasskey("db_b", "PUB_B", "CRED_B", 2)
        val f = createFixture(storage = storage)
        f.settings.setBiometricEnabled(true)

        f.coordinator.setEnabled(false, null)
        advanceUntilIdle()

        assertFalse(f.settings.current().biometricEnabled)
        assertFalse("关闭必须删除库 A 封印凭据（关闭 = 删除）", storage.hasEncryptedCredential("db_a"))
        assertFalse("关闭必须删除库 B 封印凭据（关闭 = 删除）", storage.hasEncryptedCredential("db_b"))
        assertNull("关闭必须删除断言登记记录", storage.getUnlockPasskey("db_a"))
        assertNull(storage.getUnlockPasskey("db_b"))
    }

    // ── 3b. 登记协调器的关闭撤销接线（源码守卫）─────────────────────────

    @Test
    fun `登记协调器拒绝降级关闭路径必须撤销全部封印数据`() {
        val source = readSource(ENROLLMENT_COORDINATOR_PATH)
        val refusal = source
            .substringAfter("用户拒绝软件级快速解锁")
            .substringBefore("true ->")
        assertTrue("拒绝关闭路径必须落偏好关闸", refusal.contains("setBiometricEnabled(false)"))
        assertTrue(
            "拒绝关闭路径必须撤销全部封印数据（关闭 = 删除，ISSUE-P2-253）",
            refusal.contains("revokeAllBiometricData()")
        )
        val prefIndex = refusal.indexOf("setBiometricEnabled(false)")
        val revokeIndex = refusal.indexOf("revokeAllBiometricData()")
        assertTrue("必须先落偏好关闸、再删数据（防并发登记写回）", prefIndex in 0 until revokeIndex)
    }

    @Test
    fun `封印落库前必须复核开关未在弹窗挂起期间被关闭`() {
        val source = readSource(ENROLLMENT_COORDINATOR_PATH)
        val afterSeal = source.substringAfter("sealCompositePayload(authManager")
        assertTrue(
            "封印弹窗返回后、落库前必须复核 biometricEnabled（防撤销后陈旧封印写回）",
            afterSeal.substringBefore("persistSealedCredential").contains("biometricEnabled")
        )
    }

    // ── 4. 防抖 ─────────────────────────────────────────────────────────

    @Test
    fun `验证进行中的重复开启不并发发起第二次验证`() = runTest {
        val f = createFixture()
        var promptCalls = 0
        f.coordinator.strongBiometricAvailableOverride = { true }
        // 不回调 ⇒ 流程挂起在验证中，用以观察防抖行为
        f.coordinator.promptOverride = { _, _, _ -> promptCalls++ }

        f.coordinator.setEnabled(true, null)
        runCurrent()
        assertEquals(1, promptCalls)

        f.coordinator.setEnabled(true, null)
        runCurrent()
        assertEquals("验证进行中不得重复发起", 1, promptCalls)
        assertTrue(f.state.value.verifying)

        advanceUntilIdle() // 收尾：超时兜底结束挂起
        assertFalse(f.settings.current().biometricEnabled)
    }

    // ── 5. Failed 不是终态 ───────────────────────────────────────────────

    @Test
    fun `单次比对未通过不结束流程最终成功仍启用`() = runTest {
        val f = createFixture()
        var callback: ((BiometricResult) -> Unit)? = null
        f.coordinator.strongBiometricAvailableOverride = { true }
        f.coordinator.promptOverride = { _, _, onResult -> callback = onResult }

        f.coordinator.setEnabled(true, null)
        runCurrent()

        val promptCallback = requireNotNull(callback) { "必须发起一次验证" }
        promptCallback(BiometricResult.Failed)
        runCurrent()
        assertFalse("单次比对未通过不得写入偏好", f.settings.current().biometricEnabled)
        assertTrue("系统弹窗驻留重试期间应保持验证中", f.state.value.verifying)

        promptCallback(BiometricResult.Success(null))
        advanceUntilIdle()
        assertTrue("重试最终成功后必须启用", f.settings.current().biometricEnabled)
    }

    // ── 6. 陈旧凭据（Cipher 准备失败）────────────────────────────────────

    @Test
    fun `封印凭据Cipher准备失败时清除陈旧凭据并退化为纯身份验证`() = runTest {
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential(ACTIVE_DB_ID, IV, CIPHERTEXT)
        assertTrue(storage.hasEncryptedCredential(ACTIVE_DB_ID))

        // KeystoreManager(null)：JVM 下无 AndroidKeyStore ⇒ 解密 Cipher 准备必然失败
        val f = createFixture(
            storage = storage,
            authManager = BiometricAuthManager(KeystoreManager(null), FakeRuntimeIntegrityGate())
        )
        f.coordinator.strongBiometricAvailableOverride = { true }
        var promptInvoked = false
        var observedCipher: Cipher? = null
        f.coordinator.promptOverride = { _, cipher, onResult ->
            promptInvoked = true
            observedCipher = cipher
            onResult(BiometricResult.Success(null))
        }

        f.coordinator.setEnabled(true, null)
        advanceUntilIdle()

        assertTrue("必须发起一次验证", promptInvoked)
        assertNull("Cipher 不可用时必须以纯身份验证发起（不携带 CryptoObject）", observedCipher)
        assertTrue(f.settings.current().biometricEnabled)
        assertFalse("陈旧凭据必须清除，否则开关开着却注定解封失败", storage.hasEncryptedCredential(ACTIVE_DB_ID))
        assertEquals(
            UiMessage(R.string.sec_biometric_enable_pending_seal),
            f.state.value.notice
        )
    }

    /**
     * JVM 内存版封印凭据存储：`SharedPreferences` 以 `java.lang.reflect.Proxy` 替换，
     * Base64 编解码与按库存在性判定全部走生产实现（与 `UnlockViewModelBiometricAutoPromptTest` 同源做法）。
     */
    private fun inMemoryCredentialStorage(): BiometricCredentialStorage {
        val entries = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    entries[args[0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    entries.remove(args[0] as String)
                    proxy
                }
                "clear" -> {
                    entries.clear()
                    proxy
                }
                "apply", "commit" -> null
                else -> proxy
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> (entries[args[0] as String] as? String) ?: args[1] as? String
                "getAll" -> HashMap(entries)
                "contains" -> entries.containsKey(args[0] as String)
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
        return BiometricCredentialStorage(
            object : ContextWrapper(null) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            },
            keystoreManager = null
        )
    }

    private fun readSource(path: String): String {
        val file = java.io.File(repositoryRoot, path)
        assertTrue("源文件不存在: $path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ACTIVE_DB_ID = "db_personal"
        val IV = ByteArray(12) { (it + 1).toByte() }
        val CIPHERTEXT = ByteArray(16) { (it + 1).toByte() }
        const val ENROLLMENT_COORDINATOR_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/BiometricEnrollmentCoordinator.kt"

        val repositoryRoot: java.io.File by lazy {
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (java.io.File(candidate, "app/src/main/java").isDirectory &&
                    java.io.File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根目录")
        }
    }
}
