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
import com.keepasskey.app.passkey.PasswordSaveActivity
import com.keepasskey.app.passkey.SaveRequestLedger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P2-603/606：单次套件覆盖请求契约；显式 prepare/consume 阶段供宿主在两次运行间重启 app。 */
@RunWith(AndroidJUnit4::class)
class LockedSaveRequestProcessRestartDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val testContext: Context = instrumentation.context
    private val arrivals = CopyOnWriteArrayList<LockedArrival>()
    private val buffers = ConcurrentHashMap<String, CharArray>()
    private val wiped = ConcurrentHashMap.newKeySet<String>()
    private val capturedTokens = CopyOnWriteArrayList<String>()

    @After
    fun cleanup() {
        LockedSaveRequestProbeActivity.arrivals.clear()
        buffers.values.forEach { it.fill('0') }
        buffers.clear()
        arrivals.clear()
        wiped.clear()
        capturedTokens.clear()
    }

    @Test
    fun `A与B系统PendingIntent在目标进程被杀后仍可逆序交付`() = runBlocking {
        when (InstrumentationRegistry.getArguments().getString(ARG_PHASE)) {
            PHASE_PREPARE -> prepareRestartPhase()
            PHASE_CONSUME -> consumeRestartPhase()
            else -> singleProcessContract()
        }
    }

    /** 第一阶段：系统 token 的密码 extras 留在 PendingIntent；磁盘标记只保存令牌和 pid。 */
    private fun prepareRestartPhase() {
        val tokenA = UUID.randomUUID().toString()
        val tokenB = UUID.randomUUID().toString()
        val pendingA = create(tokenA, "user-A", "Process-Password-A")
        val pendingB = create(tokenB, "user-B", "Process-Password-B")
        try {
            assertFalse("A/B 必须是不同的系统 PendingIntent", pendingA == pendingB)
            val held = holderCall(LockedSaveRequestHolderProvider.HOLD, Bundle().apply {
                putParcelable(LockedSaveRequestHolderProvider.EXTRA_A, pendingA)
                putParcelable(LockedSaveRequestHolderProvider.EXTRA_B, pendingB)
            })
            assertTrue("独立 test APK 进程必须持有 A/B 系统 token", held.getBoolean("ready"))
            SaveRequestLedger(context).markConsumed(tokenA)
            context.getSharedPreferences(SaveRequestLedger.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong("p2_603_test_flush", System.currentTimeMillis())
                .commit()
            assertTrue(
                "prepare 阶段令牌状态必须同步写入 app 私有偏好",
                restartPrefs().edit()
                    .putString(KEY_TOKEN_A, tokenA)
                    .putString(KEY_TOKEN_B, tokenB)
                    .putInt(KEY_PREPARE_PID, android.os.Process.myPid())
                    .commit()
            )
        } finally {
            // 不取消 token：宿主等待本次 app 进程自然退出后启动新 instrumentation。
            // 不 force-stop / uninstall 包——系统会在这些操作时主动取消该包的 PendingIntent。
        }
    }

    /** 第二阶段：在新 app 进程按 Intent 身份找回系统 token，不更新 extras。 */
    private suspend fun consumeRestartPhase() {
        val prefs = restartPrefs()
        val tokenA = requireNotNull(prefs.getString(KEY_TOKEN_A, null))
        val tokenB = requireNotNull(prefs.getString(KEY_TOKEN_B, null))
        val preparePid = prefs.getInt(KEY_PREPARE_PID, -1)
        val consumePid = android.os.Process.myPid()
        assertNotEquals("consume 阶段必须运行于新进程", preparePid, consumePid)
        println("P2_PROCESS_RESTART preparePid=$preparePid consumePid=$consumePid")
        val application = context.applicationContext as Application
        val callbacks = lifecycleCallbacks()
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            sendViaForegroundHolder("B")
            awaitArrivals(1)
            awaitWiped(tokenB)
            sendViaForegroundHolder("A")
            awaitArrivals(2)

            assertEquals(listOf(tokenB, tokenA), arrivals.map { it.token })
            assertEquals("user-B", arrivals[0].username)
            assertEquals("Process-Password-B", arrivals[0].password)
            assertEquals("user-A", arrivals[1].username)
            assertEquals("Process-Password-A", arrivals[1].password)
            assertEquals("上一进程已消费的 A 在新进程必须拒绝且不复制口令数组", 0, capturedTokens.count { it == tokenA })

            SaveRequestLedger(context).markConsumed(tokenB)
            SystemClock.sleep(250L)
            sendViaForegroundHolder("B")
            awaitArrivals(3)
            assertEquals("已消费 B 重放不得再次复制口令数组", 1, capturedTokens.count { it == tokenB })
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            holderCall(LockedSaveRequestHolderProvider.RELEASE)
            prefs.edit().remove(KEY_TOKEN_A).remove(KEY_TOKEN_B).remove(KEY_PREPARE_PID).commit()
        }
    }

    /** 常规 Gradle 设备套件未经过宿主两阶段 orchestration 时，仍执行真实系统句柄/数组契约。 */
    private suspend fun singleProcessContract() {
        val tokenA = UUID.randomUUID().toString()
        val tokenB = UUID.randomUUID().toString()
        val pendingA = create(tokenA, "user-A", "Process-Password-A")
        val pendingB = create(tokenB, "user-B", "Process-Password-B")
        val application = context.applicationContext as Application
        val callbacks = lifecycleCallbacks()
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            assertFalse("A/B 必须是不同的系统 PendingIntent", pendingA == pendingB)
            pendingB.send()
            awaitArrivals(1)
            awaitWiped(tokenB)
            pendingA.send()
            awaitArrivals(2)
            awaitWiped(tokenA)
            assertEquals(listOf(tokenB, tokenA), arrivals.map { it.token })
            assertEquals("user-B", arrivals[0].username)
            assertEquals("Process-Password-B", arrivals[0].password)
            assertEquals("user-A", arrivals[1].username)
            assertEquals("Process-Password-A", arrivals[1].password)
            SaveRequestLedger(context).markConsumed(tokenA)
            pendingA.send()
            awaitArrivals(3)
            assertEquals("已消费 A 重放不得再次创建口令数组", 1, capturedTokens.count { it == tokenA })
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            pendingA.cancel()
            pendingB.cancel()
        }
    }

    private fun create(token: String, username: String, password: String): PendingIntent =
        LockedSaveRequestPendingIntent.create(
            context = context,
            requestCode = REQUEST_CODE,
            requestToken = token,
            callingPackage = "com.synthetic.client",
            webDomain = "example.test",
            username = username,
            password = password
        )

    private fun restartPrefs() = context.getSharedPreferences(RESTART_PREFS, Context.MODE_PRIVATE)

    private fun holderCall(method: String, extras: Bundle = Bundle()): Bundle = requireNotNull(
        testContext.contentResolver.call(
            Uri.parse("content://${testContext.packageName}.p2_603_holder"),
            method,
            null,
            extras
        )
    ) { "独立 PendingIntent holder 未返回回执" }

    /** 由 test APK 的可见 Activity 发送系统句柄，满足 Android 的 background-activity-start 规则。 */
    private fun sendViaForegroundHolder(request: String) {
        holderCall(LockedSaveRequestHolderProvider.CLEAR_RESULT)
        val component = "${testContext.packageName}/com.keepasskey.app.autofill.LockedSaveRequestHolderActivity"
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
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
        assertEquals(
            "前台 test Activity 应从独立 holder 取得并发送 $request token",
            expected,
            result
        )
    }

    private fun lifecycleCallbacks() = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is PasswordSaveActivity) return
            arrivals += LockedArrival(
                data = activity.intent.data?.toString().orEmpty(),
                token = activity.intent.getStringExtra(PasswordSaveActivity.EXTRA_REQUEST_TOKEN).orEmpty(),
                username = activity.intent.getStringExtra(PasswordSaveActivity.EXTRA_USERNAME).orEmpty(),
                password = activity.intent.getStringExtra(PasswordSaveActivity.EXTRA_PASSWORD).orEmpty()
            )
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity !is PasswordSaveActivity) return
            val token = activity.intent.getStringExtra(PasswordSaveActivity.EXTRA_REQUEST_TOKEN).orEmpty()
            PasswordSaveActivity::class.java.getDeclaredField("pendingPasswordChars").apply {
                isAccessible = true
                (get(activity) as? CharArray)?.let {
                    buffers[token] = it
                    capturedTokens += token
                }
            }
            activity.finish()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity !is PasswordSaveActivity) return
            val token = activity.intent.getStringExtra(PasswordSaveActivity.EXTRA_REQUEST_TOKEN).orEmpty()
            val buffer = buffers.remove(token) ?: return
            if (buffer.all { it == '0' }) wiped.add(token)
            buffer.fill('0')
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private fun awaitArrivals(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline && arrivals.size < expected) SystemClock.sleep(POLL_MS)
        assertEquals("目标进程重启后系统必须实际启动 PasswordSaveActivity", expected, arrivals.size)
    }

    private fun awaitWiped(token: String) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline && token !in wiped) SystemClock.sleep(POLL_MS)
        assertTrue("新进程 Activity 销毁必须清零等待解锁口令数组", token in wiped)
    }

    private data class LockedArrival(val data: String, val token: String, val username: String, val password: String)

    private companion object {
        const val REQUEST_CODE = 2411
        const val RESTART_PREFS = "p2_603_process_restart"
        const val KEY_TOKEN_A = "token_a"
        const val KEY_TOKEN_B = "token_b"
        const val KEY_PREPARE_PID = "prepare_pid"
        const val ARG_PHASE = "p2_603_phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_CONSUME = "consume"
        const val WAIT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
