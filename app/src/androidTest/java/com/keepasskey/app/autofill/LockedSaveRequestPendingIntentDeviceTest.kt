package com.keepasskey.app.autofill

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.passkey.SaveRequestLedger
import com.keepasskey.app.security.KeystoreManager
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ENG-20261028-21`：锁定态保存**系统句柄**的设备侧契约（落地流程见
 * [LockedSaveRequestProcessRestartDeviceTest]，那里用真实 UI 解锁驱动落库）。
 *
 * 本类只回答两个问题：
 * 1. **系统句柄内容边界**：Android 系统实际保留的 `PendingIntent` 只携带一次性令牌与调用方
 *    归属，用户输入（用户名 / 口令）不在 extras 里；A/B 两个请求在系统中是不同 token，
 *    逆序点击各自交付（`ISSUE-P2-603` 的保存串单回归）。
 * 2. **令牌解析 fail-closed**：非规范 UUID、data 与 extras 不一致、缺少任一侧都不得放行。
 *
 * 库锁定分支的「未落盘即销毁」在这里顺带覆盖（落地页停在解锁窗口即被结束）：
 * 载体必须丢弃、台账必须登记消费。成功 / 取消 / 超时 / 进程重建四类终态由另一用例覆盖。
 */
@RunWith(AndroidJUnit4::class)
class LockedSaveRequestPendingIntentDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 与被测应用 Hilt 单例共享同一 prefs 文件与 Keystore 别名，故可观察其终态。 */
    private val store = LockedSavePayloadStore(context, KeystoreManager(context))
    private val ledger = SaveRequestLedger(context)
    private val arrivals = CopyOnWriteArrayList<Arrival>()

    private data class Arrival(
        val data: String,
        val token: String,
        val packageName: String,
        val webDomain: String?,
        val extras: Map<String, String>
    )

    @Before
    fun setUp() {
        store.discardAll()
        arrivals.clear()
    }

    @After
    fun tearDown() {
        store.discardAll()
        arrivals.clear()
    }

    @Test
    fun `系统句柄只含一次性令牌与调用方归属且A与B逆序各自交付`() = runBlocking {
        withLockedSyntheticVault {
            val tokenA = UUID.randomUUID().toString()
            val tokenB = UUID.randomUUID().toString()
            val charsA = "Synthetic-Password-A".toCharArray()
            val charsB = "Synthetic-Password-B".toCharArray()
            try {
                assertTrue(store.seal(tokenA, "user-A", charsA))
                assertTrue(store.seal(tokenB, "user-B", charsB))
            } finally {
                charsA.fill('0')
                charsB.fill('0')
            }
            val pendingA = create(tokenA)
            val pendingB = create(tokenB)
            val callbacks = recordCallbacks(finishOnResume = true)
            val application = context.applicationContext as Application
            application.registerActivityLifecycleCallbacks(callbacks)
            try {
                assertFalse("请求身份不同必须得到不同系统 token", pendingA == pendingB)
                // 句柄交给独立 holder 进程再由前台测试 Activity 发送：instrumented 进程无可见窗口，
                // 直接 send() 会撞上后台启动限制（既有 ISSUE-P2-603 用例同一通道）。
                holdBoth(pendingA, pendingB)

                sendViaForegroundHolder("B")
                awaitArrivals(1)
                sendViaForegroundHolder("A")
                awaitArrivals(2)

                assertEquals(listOf(tokenB, tokenA), arrivals.map { it.token })
                arrivals.forEachIndexed { index, arrival ->
                    val expected = if (index == 0) tokenB else tokenA
                    assertTrue("data 必须承载该请求的随机身份", arrival.data.endsWith(expected))
                    assertEquals("com.synthetic.client", arrival.packageName)
                    assertEquals("example.test", arrival.webDomain)
                    assertTrue(
                        "系统句柄不得出现口令原文：${arrival.extras}",
                        arrival.extras.values.none { it.contains("Synthetic-Password") }
                    )
                    assertTrue(
                        "系统句柄不得出现用户名：${arrival.extras}",
                        arrival.extras.values.none { it.contains("user-A") || it.contains("user-B") }
                    )
                    assertTrue(
                        "系统句柄不得出现口令类 extra：${arrival.extras.keys}",
                        arrival.extras.keys.none { it.contains("PASSWORD", ignoreCase = true) }
                    )
                }

                // 库锁定分支：落地页停在解锁窗口；未落盘即销毁 ⇒ 载体丢弃 + 台账登记消费
                awaitTrue("取消路径必须丢弃载体") { !store.hasPayload(tokenA) && !store.hasPayload(tokenB) }
                assertTrue(ledger.isConsumed(tokenA))
                assertTrue(ledger.isConsumed(tokenB))

                // 已消费令牌被系统再次送出：落地页拒绝，且不会重新出现可解封载荷
                sendViaForegroundHolder("A")
                awaitArrivals(3)
                assertEquals(tokenA, arrivals.last().token)
                assertFalse(store.hasPayload(tokenA))
            } finally {
                application.unregisterActivityLifecycleCallbacks(callbacks)
                holderCall(LockedSaveRequestHolderProvider.RELEASE)
            }
        }
    }

    /**
     * 落地页的令牌解析必须 fail-closed：只有 `Intent.data` 与 extras 两侧**同时存在、内容一致
     * 且为规范 UUID** 才放行；缺少任一侧、两侧不一致、非规范 UUID、带 query / 多段路径或错误
     * scheme 一律拒绝——否则畸形身份会被带进台账与载体键。
     */
    @Test
    fun `保存请求令牌解析只放行两侧一致的规范UUID`() {
        val token = UUID.randomUUID().toString()

        assertEquals(token, LockedSaveRequestPendingIntent.requestTokenFromIntent(canonical(token)))

        assertNull(
            "缺少 extras 侧身份必须拒绝",
            LockedSaveRequestPendingIntent.requestTokenFromIntent(Intent().apply { data = requestUri(token) })
        )
        assertNull(
            "data 与 extras 不一致必须拒绝",
            LockedSaveRequestPendingIntent.requestTokenFromIntent(
                canonical(token).apply {
                    putExtra(
                        LockedSaveRequestPendingIntent.EXTRA_REQUEST_TOKEN,
                        UUID.randomUUID().toString()
                    )
                }
            )
        )
        assertNull(
            "非规范 UUID 形态必须拒绝",
            LockedSaveRequestPendingIntent.requestTokenFromIntent(canonical("1-1-1-1-1", token))
        )
        assertNull(
            "带 query 的身份必须拒绝",
            LockedSaveRequestPendingIntent.requestTokenFromIntent(canonical(token, token, query = "x=1"))
        )
        assertNull(
            "多段路径的身份必须拒绝",
            LockedSaveRequestPendingIntent.requestTokenFromIntent(canonical("$token/extra", token))
        )
        assertNull(
            "错误 scheme 的身份必须拒绝",
            LockedSaveRequestPendingIntent.requestTokenFromIntent(
                canonical(token).apply { data = Uri.parse("https://other-host/$token") }
            )
        )
    }

    // ---------------------------------------------------------------- 编排与断言

    private fun create(token: String): PendingIntent = LockedSaveRequestPendingIntent.create(
        context = context,
        requestCode = REQUEST_CODE,
        requestToken = token,
        callingPackage = "com.synthetic.client",
        webDomain = "example.test"
    )

    /** 与生产同形的请求 Intent：[dataToken] 入 data 路径，[extraToken] 入 extras。 */
    private fun canonical(
        dataToken: String,
        extraToken: String = dataToken,
        query: String? = null
    ): Intent = Intent().apply {
        data = if (query == null) requestUri(dataToken)
        else Uri.parse("keepasskey://autofill-save-request/$dataToken?$query")
        putExtra(LockedSaveRequestPendingIntent.EXTRA_REQUEST_TOKEN, extraToken)
    }

    private fun requestUri(token: String): Uri = Uri.parse("keepasskey://autofill-save-request/$token")

    private fun recordCallbacks(finishOnResume: Boolean): Application.ActivityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is AutofillLockedSaveActivity) return
                val intent = activity.intent
                val extras = intent.extras
                arrivals += Arrival(
                    data = intent.data?.toString().orEmpty(),
                    token = intent.getStringExtra(LockedSaveRequestPendingIntent.EXTRA_REQUEST_TOKEN).orEmpty(),
                    packageName = intent.getStringExtra(LockedSaveRequestPendingIntent.EXTRA_PACKAGE_NAME).orEmpty(),
                    webDomain = intent.getStringExtra(LockedSaveRequestPendingIntent.EXTRA_WEB_DOMAIN),
                    extras = extras?.keySet().orEmpty().associateWith { key ->
                        // 句柄里的 extras 全是字符串（令牌 / 包名 / 域）；用非弃用读口取字符串值
                        extras?.getString(key).orEmpty()
                    }
                )
            }

            override fun onActivityResumed(activity: Activity) {
                if (finishOnResume && activity is AutofillLockedSaveActivity) activity.finish()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    private fun graph(): AutofillDeviceGraph =
        EntryPointAccessors.fromApplication(context.applicationContext, AutofillDeviceGraph::class.java)

    private fun testContext(): Context = InstrumentationRegistry.getInstrumentation().context

    private fun holderCall(method: String, extras: Bundle = Bundle()): Bundle = requireNotNull(
        testContext().contentResolver.call(
            Uri.parse("content://${testContext().packageName}.p2_603_holder"),
            method,
            null,
            extras
        )
    ) { "独立 PendingIntent holder 未返回回执" }

    private fun holdBoth(pendingA: PendingIntent, pendingB: PendingIntent) {
        val held = holderCall(LockedSaveRequestHolderProvider.HOLD, Bundle().apply {
            putParcelable(LockedSaveRequestHolderProvider.EXTRA_A, pendingA)
            putParcelable(LockedSaveRequestHolderProvider.EXTRA_B, pendingB)
        })
        assertTrue("独立 holder 进程必须接住 A/B 系统句柄", held.getBoolean("ready"))
    }

    /** 由 test APK 的可见 Activity 发送 holder 进程保留的系统句柄（后台启动限制下的唯一通道）。 */
    private fun sendViaForegroundHolder(request: String) {
        holderCall(LockedSaveRequestHolderProvider.CLEAR_RESULT)
        val component =
            "${testContext().packageName}/com.keepasskey.app.autofill.LockedSaveRequestHolderActivity"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am start -n $component --es ${LockedSaveRequestHolderActivity.EXTRA_REQUEST} $request"
        )
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        val expected = "$request:OK"
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var result = holderCall(LockedSaveRequestHolderProvider.READ_RESULT).getString("result")
        while (SystemClock.uptimeMillis() < deadline && result != expected) {
            SystemClock.sleep(POLL_MS)
            result = holderCall(LockedSaveRequestHolderProvider.READ_RESULT).getString("result")
        }
        assertEquals("前台 test Activity 应从独立 holder 取得并发送 $request token", expected, result)
    }

    /** 合成活动库处于**锁定**态（锁定态保存的唯一合法前提）；用例结束恢复选择并删除合成库。 */
    private suspend fun withLockedSyntheticVault(block: suspend (VaultRepository) -> Unit) {
        val vault = AutofillTestVaultSeeder.seed(context, context.packageName)
        val selection = AutofillVaultSelection(context)
        val snapshot = selection.select(vault.name)
        val repository = graph().vaultRepository()
        try {
            graph().databaseSession().lock()
            assertTrue("合成活动库必须处于锁定态", repository.isLocked())
            block(repository)
        } finally {
            graph().databaseSession().lock()
            selection.restore(snapshot)
            AutofillTestVaultSeeder.deleteOwned(vault, AutofillSeedContract.MASTER_PASSWORD_TEXT)
        }
    }

    private fun awaitArrivals(count: Int) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline && arrivals.size < count) SystemClock.sleep(POLL_MS)
        assertEquals("系统必须实际拉起保存落地页", count, arrivals.size)
    }

    private fun awaitTrue(description: String, timeoutMs: Long = WAIT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline && !condition()) SystemClock.sleep(POLL_MS)
        assertTrue(description, condition())
    }

    private companion object {
        const val REQUEST_CODE = 2407
        const val WAIT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
