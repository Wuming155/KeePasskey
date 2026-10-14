package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.passkey.PasswordSaveActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISSUE-P2-603：在 Android 系统 PendingIntent 服务中验证 A/B 保存请求身份与逆序点击交付。
 *
 * 两个请求故意使用相同 requestCode，仅由生产 Intent.data 中的 requestToken 区分；随后由系统
 * 先启动 B 再启动 A，并核对接收端看到的令牌、用户名与合成口令都仍属于各自请求。
 */
@RunWith(AndroidJUnit4::class)
class LockedSaveRequestPendingIntentDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val observedBuffers = ConcurrentHashMap<String, CharArray>()
    private val wipedTokens = CopyOnWriteArraySet<String>()
    private val capturedTokens = CopyOnWriteArraySet<String>()

    @After
    fun tearDown() {
        LockedSaveRequestProbeActivity.arrivals.clear()
        observedBuffers.values.forEach { it.fill('0') }
        observedBuffers.clear()
        wipedTokens.clear()
        capturedTokens.clear()
    }

    @Test
    fun 相同requestCode的A与B系统PendingIntent逆序点击仍各自交付() = runBlocking {
        withLockedSyntheticVault {
        val tokenA = UUID.randomUUID().toString()
        val tokenB = UUID.randomUUID().toString()
        val pendingA = createPendingIntent(tokenA, "user-A", "Synthetic-Password-A")
        val pendingB = createPendingIntent(tokenB, "user-B", "Synthetic-Password-B")
        val application = context.applicationContext as Application
        val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is com.keepasskey.app.passkey.PasswordSaveActivity) return
                val intent = activity.intent
                val requestToken = intent.getStringExtra(PasswordSaveActivity.EXTRA_REQUEST_TOKEN).orEmpty()
                LockedSaveRequestProbeActivity.arrivals += LockedSaveRequestProbeActivity.Arrival(
                    data = intent.data?.toString().orEmpty(),
                    requestToken = requestToken,
                    username = intent.getStringExtra(PasswordSaveActivity.EXTRA_USERNAME).orEmpty(),
                    password = intent.getStringExtra(PasswordSaveActivity.EXTRA_PASSWORD).orEmpty()
                )
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity !is com.keepasskey.app.passkey.PasswordSaveActivity) return
                val requestToken = activity.intent.getStringExtra(PasswordSaveActivity.EXTRA_REQUEST_TOKEN).orEmpty()
                PasswordSaveActivity::class.java.getDeclaredField("pendingPasswordChars").apply {
                    isAccessible = true
                    (get(activity) as? CharArray)?.let {
                        observedBuffers[requestToken] = it
                        capturedTokens += requestToken
                    }
                }
                activity.finish()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (activity !is com.keepasskey.app.passkey.PasswordSaveActivity) return
                val token = activity.intent.getStringExtra(PasswordSaveActivity.EXTRA_REQUEST_TOKEN).orEmpty()
                val passwordBuffer = observedBuffers.remove(token) ?: return
                if (passwordBuffer.all { it == '0' }) wipedTokens += token
                passwordBuffer.fill('0') // 清测试持有引用；保留擦除断言结果。
            }
        }

        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        try {
            assertFalse("请求身份不同必须得到不同系统 token", pendingA == pendingB)

            pendingB.send()
            awaitArrivals(1)
            awaitBufferWiped(tokenB, wipedTokens)
            pendingA.send()
            awaitArrivals(2)
            awaitBufferWiped(tokenA, wipedTokens)

            val delivered = LockedSaveRequestProbeActivity.arrivals.toList()
            assertEquals(listOf(tokenB, tokenA), delivered.map { it.requestToken })
            assertTrue(delivered[0].data.endsWith(tokenB))
            assertEquals("user-B", delivered[0].username)
            assertEquals("Synthetic-Password-B", delivered[0].password)
            assertTrue(delivered[1].data.endsWith(tokenA))
            assertEquals("user-A", delivered[1].username)
            assertEquals("Synthetic-Password-A", delivered[1].password)

            // 按同一身份重取句柄仍引用 A 的系统 token；不会取到 B 的更新 extras。
            val recoveredA = createPendingIntent(tokenA, "user-A", "Synthetic-Password-A")
            try {
                assertEquals(pendingA, recoveredA)
                recoveredA.send()
                awaitArrivals(3)
                val afterReissue = LockedSaveRequestProbeActivity.arrivals.last()
                assertEquals(tokenA, afterReissue.requestToken)
                assertEquals("user-A", afterReissue.username)
                assertEquals("Synthetic-Password-A", afterReissue.password)
            } finally {
                recoveredA.cancel()
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
            pendingA.cancel()
            pendingB.cancel()
        }
        }
    }

    private suspend fun withLockedSyntheticVault(block: suspend () -> Unit) {
        val vault = AutofillTestVaultSeeder.seed(context, context.packageName)
        val selection = AutofillVaultSelection(context)
        val snapshot = selection.select(vault.name)
        val graph = EntryPointAccessors.fromApplication(context, AutofillDeviceGraph::class.java)
        try {
            graph.databaseSession().lock()
            assertTrue("合成活动库必须处于锁定态，保存 Activity 才会等待解锁", graph.vaultRepository().isLocked())
            block()
        } finally {
            graph.databaseSession().lock()
            selection.restore(snapshot)
            AutofillTestVaultSeeder.deleteOwned(vault, AutofillSeedContract.MASTER_PASSWORD_TEXT)
        }
    }

    private fun createPendingIntent(token: String, username: String, password: String): PendingIntent =
        LockedSaveRequestPendingIntent.create(
            context = context,
            requestCode = REQUEST_CODE,
            requestToken = token,
            callingPackage = "com.synthetic.client",
            webDomain = "example.test",
            username = username,
            password = password
        )

    private fun awaitArrivals(count: Int) {
        val deadline = SystemClock.uptimeMillis() + ARRIVAL_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline && LockedSaveRequestProbeActivity.arrivals.size < count) {
            SystemClock.sleep(POLL_MS)
        }
        assertEquals("系统必须实际启动测试接收 Activity", count, LockedSaveRequestProbeActivity.arrivals.size)
    }

    private fun awaitBufferWiped(token: String, wipedTokens: Set<String>) {
        val deadline = SystemClock.uptimeMillis() + ARRIVAL_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline && token !in wipedTokens) SystemClock.sleep(POLL_MS)
        assertTrue(
            "保存 Activity 销毁时必须擦除等待解锁阶段的口令数组（已捕获=${token in capturedTokens}）",
            token in wipedTokens
        )
    }

    private companion object {
        const val REQUEST_CODE = 2407
        const val ARRIVAL_WAIT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
