package com.keepasskey.app.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「删键复位」旁路的**设备侧（instrumented）**回归（ISSUE-P2-45）。
 *
 * ## 为什么必须有这一层
 *
 * 本项整改的核心主张是「**Keystore 条目无法被文件级删除一并抹掉**」——这是一条关于
 * **AndroidKeyStore 真实行为**的主张，宿主 JVM 用内存假实现只能验证*代码路径*，
 * **不构成**该主张的证据（`AGENTS.md` §5：涉及平台 API 的逻辑不可只靠宿主单测）。
 * 故此处以真实 [AndroidKeystoreUnlockThrottleIntegrity] + 真实 SharedPreferences
 * 执行「写入 → 整组删除三键 → 观察判定」的端到端链路。
 *
 * ## 覆盖与不覆盖（如实声明）
 *
 * - **覆盖**：真实 Keystore 标记的建立与查询；三键被整组删除后 [SharedPrefsUnlockThrottleStore.read]
 *   返回 `integrityIntact = false`（即 fail-closed）；`reset` 之后三键仍在案；标记按库隔离。
 * - **不覆盖**：`UnlockThrottleManager.gate` 之上的 UI 呈现与真实退避等待墙钟——
 *   前者由 `app` 模块 JVM 用例覆盖，后者为时长策略（纯函数，已在
 *   `UnlockThrottleManagerTest` 用注入时钟断言）。
 */
@RunWith(AndroidJUnit4::class)
class UnlockThrottleDeletionBypassDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val integrity = AndroidKeystoreUnlockThrottleIntegrity()

    private val store = SharedPrefsUnlockThrottleStore(context, integrity)

    /** 与生产实现同名的节流 prefs 文件（键名不在此硬编码，删除以整文件清空模拟） */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val dbId = "device-test-throttle"

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `真实Keystore上MAC可往返校验`() {
        val record = UnlockThrottleRecord(failureCount = 3, lockoutUntilEpochMs = 1_700_000_000_000L)

        val mac = integrity.mac(dbId, record)

        assertNotNull(
            "生产实现必须能在真实 AndroidKeyStore 上算出 MAC。返回 null 表示 Keystore 路径整体失效，" +
                "此时 read() 会把每一条在案记录判为篡改、gate() 永久 fail-closed（ISSUE-P0-10）；" +
                "宿主 JVM 的假实现**不会**暴露这一失效，故本断言必须留在设备侧。",
            mac
        )
        assertTrue("同一实现算出的 MAC 必须能自校验通过", integrity.verify(dbId, record, mac!!))
        assertFalse("计数被篡改的记录必须被拒", integrity.verify(dbId, record.copy(failureCount = 4), mac))
        assertFalse("换库后同一 MAC 必须被拒", integrity.verify("device-test-other-bind", record, mac))
    }

    @Test
    fun `真实Keystore下三键被整组删除后判定为篡改而非复位`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 5, lockoutUntilEpochMs = 0L))
        assertTrue("前置：存在性标记必须真实落在 AndroidKeyStore", integrity.existenceMarkerPresent(dbId))
        assertTrue("前置：在案记录应通过 MAC 校验", store.read(dbId).integrityIntact)

        // 攻击面：具备节流 prefs 写权限者删除整组键（计数 / 锁定截止 / MAC）
        prefs.edit().clear().commit()
        assertTrue("前置：三键确实已消失", prefs.all.isEmpty())

        assertFalse(
            "整组删除后必须 fail-closed；判为『全新安装』即等于放行在线爆破（ISSUE-P2-45）",
            store.read(dbId).integrityIntact
        )
    }

    @Test
    fun `成功重置后三键仍在案且判定为完整`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 6, lockoutUntilEpochMs = 42L))

        store.reset(dbId)

        assertEquals("重置必须留下计数 / 锁定截止 / MAC 三个键", 3, prefs.all.size)
        val record = store.read(dbId)
        assertTrue("重置是合法操作，不得被误判为篡改", record.integrityIntact)
        assertEquals(0, record.failureCount)
        assertEquals(0L, record.lockoutUntilEpochMs)
    }

    @Test
    fun `重置之后整组删除同样被判定为篡改`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 2))
        store.reset(dbId)
        assertTrue(store.read(dbId).integrityIntact)

        prefs.edit().clear().commit()

        assertFalse("成功解锁不得成为清除『曾在案』证据的途径", store.read(dbId).integrityIntact)
    }

    @Test
    fun `存在性标记按库隔离不牵连从未写入的库`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 1))

        assertTrue(integrity.existenceMarkerPresent(dbId))
        assertFalse(integrity.existenceMarkerPresent("device-test-never-written"))
        assertTrue(
            "从未写入过的库必须按全新安装放行",
            store.read("device-test-never-written").integrityIntact
        )
    }

    private companion object {
        /** 与 [SharedPrefsUnlockThrottleStore] 一致的 prefs 文件名（回归锁：改名会改变删除面） */
        const val PREFS_NAME = "com.keepasskey.unlock_throttle"
    }
}
