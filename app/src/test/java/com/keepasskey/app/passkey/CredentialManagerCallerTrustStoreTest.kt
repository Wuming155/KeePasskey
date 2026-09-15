package com.keepasskey.app.passkey

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.security.CallerCertDigests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Credential Manager 通道调用方绑定存储单元测试（**ISSUE-P2-83**）。
 *
 * 覆盖 AC② 的两条负向判据（「同包名不同签名 → 不命中」）与 fail-closed 口径
 * （包名非法 / 摘要不可读一律不写入、不信任，且**不得**落「仅包名」降级键）。
 *
 * 测试策略：`SharedPreferences` 以 `java.lang.reflect.Proxy` 内存实现模拟
 * （体例同 `BiometricCredentialStorageTest`，不引入 Robolectric）。
 */
class CredentialManagerCallerTrustStoreTest {

    private val pkg = "com.example.bank"
    private val digestA = "AA".repeat(32)
    private val digestB = "BB".repeat(32)

    private val memoryStorage = mutableMapOf<String, Any?>()

    private val fakeEditor: SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "putBoolean" -> {
                memoryStorage[args[0] as String] = args[1]
                proxy
            }
            "remove" -> {
                memoryStorage.remove(args[0] as String)
                proxy
            }
            "clear" -> {
                memoryStorage.clear()
                proxy
            }
            "commit" -> true
            else -> proxy
        }
    } as SharedPreferences.Editor

    private val fakePrefs: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getBoolean" -> (memoryStorage[args[0] as String] as? Boolean) ?: (args[1] as Boolean)
            "contains" -> memoryStorage.containsKey(args[0] as String)
            "getAll" -> memoryStorage.toMap()
            "edit" -> fakeEditor
            else -> null
        }
    } as SharedPreferences

    private val fakeContext: Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
    }

    private lateinit var store: CredentialManagerCallerTrustStore

    @Before
    fun setUp() {
        memoryStorage.clear()
        store = CredentialManagerCallerTrustStore(fakeContext)
    }

    // ── AC② 负向判据 ─────────────────────────────────────────────────

    @Test
    fun `未绑定的调用方一律不信任`() {
        assertFalse(store.isTrusted(pkg, CallerCertDigests.ofSingle(digestA)))
    }

    @Test
    fun `同包名不同签名不命中`() {
        assertTrue("前置：绑定必须写入成功", store.trust(pkg, digestA))

        assertFalse(
            "以同 applicationId 侧载、签名不同的应用不得命中（ISSUE-P2-83 AC②）",
            store.isTrusted(pkg, CallerCertDigests.ofSingle(digestB))
        )
        assertTrue("真正被绑定的签名仍应命中", store.isTrusted(pkg, CallerCertDigests.ofSingle(digestA)))
    }

    @Test
    fun `不同包名不命中`() {
        store.trust(pkg, digestA)

        assertFalse(store.isTrusted("com.example.other", CallerCertDigests.ofSingle(digestA)))
    }

    // ── 多签名者（ISSUE-P3-93 口径） ─────────────────────────────────

    @Test
    fun `多签名者任一命中即通过`() {
        store.trust(pkg, digestA)

        // 签名轮换期系统同时下发当前与历史签名者：任一命中即视为同一应用
        assertTrue(
            store.isTrusted(pkg, CallerCertDigests.of(listOf(digestB, digestA)))
        )
    }

    // ── fail-closed ─────────────────────────────────────────────────

    @Test
    fun `包名非法时不写入也不信任`() {
        assertFalse("非法包名不得写入", store.trust("not a package", digestA))
        assertFalse(store.isTrusted("not a package", CallerCertDigests.ofSingle(digestA)))
        assertTrue("不得留下任何键", memoryStorage.isEmpty())
    }

    @Test
    fun `摘要不可读时不写入也不信任且不落仅包名降级键`() {
        assertFalse("空摘要不得写入", store.trust(pkg, null))
        assertFalse(store.trust(pkg, "   "))

        assertTrue("不得落『仅包名』降级键（pkg|）", memoryStorage.isEmpty())
        assertFalse(
            "摘要全不可读时必须按未授权处理",
            store.isTrusted(pkg, CallerCertDigests.EMPTY)
        )
    }

    @Test
    fun `查询摘要不可读时不因存在其他键而误判`() {
        store.trust(pkg, digestA)

        assertFalse(store.isTrusted(pkg, CallerCertDigests.EMPTY))
    }

    // ── 对称撤销与归一化 ─────────────────────────────────────────────

    @Test
    fun `撤销后不再信任`() {
        store.trust(pkg, digestA)
        assertTrue(store.untrust(pkg, digestA))

        assertFalse(store.isTrusted(pkg, CallerCertDigests.ofSingle(digestA)))
        assertFalse("重复撤销返回 false（本就未授权）", store.untrust(pkg, digestA))
    }

    @Test
    fun `摘要大小写与空白归一化后仍可命中`() {
        assertTrue(store.trust(pkg, "  ${digestA.lowercase()}  "))

        assertTrue(store.isTrusted(pkg, CallerCertDigests.ofSingle(digestA)))
    }

    @Test
    fun `单摘要重载与集合重载判定一致`() {
        store.trust(pkg, digestA)

        assertTrue(store.isTrusted(pkg, digestA))
        assertTrue(store.isTrusted(pkg, CallerCertDigests.ofSingle(digestA)))
        assertFalse(store.isTrusted(pkg, digestB))
        assertFalse(store.isTrusted(pkg, null))
    }

    // ── ISSUE-P2-83 AC②：绑定存在性判定（区分「已绑定不匹配」与「从未绑定」） ──

    @Test
    fun `hasAnyBindingFor 仅在确有绑定时为真`() {
        assertFalse(store.hasAnyBindingFor(pkg))

        store.trust(pkg, digestA)

        assertTrue(store.hasAnyBindingFor(pkg))
        assertTrue("换签名后仍须认为该包名『已绑定』——这正是要拦下的形态", store.hasAnyBindingFor(pkg))
    }

    @Test
    fun `hasAnyBindingFor 不受其他包名影响`() {
        store.trust(pkg, digestA)

        assertFalse(store.hasAnyBindingFor("com.example.other"))
    }

    @Test
    fun `hasAnyBindingFor 对非法包名一律为假`() {
        store.trust(pkg, digestA)

        assertFalse(store.hasAnyBindingFor("not a package"))
        assertFalse(store.hasAnyBindingFor(""))
    }

    @Test
    fun `存储与门控集成：已绑定包名的异签名调用方被拒`() {
        store.trust(pkg, digestA)

        fun decide(callingPackage: String, digests: CallerCertDigests) =
            CredentialManagerPackageBindingGate.decide(
                callingPackage = callingPackage,
                certDigests = digests,
                isTrusted = store::isTrusted,
                hasAnyBinding = store::hasAnyBindingFor
            )

        assertEquals(
            CredentialManagerPackageBindingGate.Decision.AUTHORIZED,
            decide(pkg, CallerCertDigests.ofSingle(digestA))
        )
        assertEquals(
            "AC②：同包名不同签名 ⇒ 不命中",
            CredentialManagerPackageBindingGate.Decision.REJECTED,
            decide(pkg, CallerCertDigests.ofSingle(digestB))
        )
        assertEquals(
            "摘要不可读且已绑定 ⇒ fail-closed",
            CredentialManagerPackageBindingGate.Decision.REJECTED,
            decide(pkg, CallerCertDigests.EMPTY)
        )
        assertEquals(
            "从未绑定 ⇒ 退回既有行为，不得拒绝",
            CredentialManagerPackageBindingGate.Decision.UNBOUND,
            decide("com.example.other", CallerCertDigests.ofSingle(digestA))
        )
    }

    /** 供断言「无降级键」的实现细节：键空间必须始终为 `pkg|digest` 形态 */
    @Test
    fun `写入的键始终为包名加摘要两段式`() {
        store.trust(pkg, digestA)

        val key = memoryStorage.keys.single()
        assertTrue("键形态异常：$key", key.startsWith("$pkg|"))
        assertTrue("摘要段不得为空", key.removePrefix("$pkg|").isNotEmpty())
        assertNull("降级键 pkg| 不得出现", memoryStorage["$pkg|"])
    }
}
