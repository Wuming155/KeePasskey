package com.keepasskey.app.autofill

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.UUID

/** ISSUE-P3-609：只冷停当前 instrumentation 清单证明的独立合成客户端，不清数据。 */
internal class AutofillClientRunIsolation(
    private val probe: DeviceAutofillProbe,
    private val writeEvidence: (String) -> Unit
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val clientPackage = instrumentation.context.packageName
    val runId: String = UUID.randomUUID().toString()
    var formRunId: String = ""
        private set
    private var validated = false
    private var formNumber = 0
    private var trustPreferences: android.content.SharedPreferences? = null
    private var savedClientTrust: Map<String, Boolean> = emptyMap()

    fun prepare() {
        val packages = instrumentation.context.packageManager
        val info = packages.getInstrumentationInfo(instrumentation.componentName, 0)
        val activity = packages.getActivityInfo(ComponentName(clientPackage, CLIENT_CLASS_NAME), 0)
        assertEquals("客户端必须属于当前测试 APK", clientPackage, info.packageName)
        assertEquals("测试 APK 必须仅指向当前被测包", targetContext.packageName, info.targetPackage)
        assertEquals("只允许当前被测包的专属 .test 客户端", "${targetContext.packageName}.test", clientPackage)
        assertTrue("包名不得包含 shell 元字符", clientPackage.matches(Regex("[a-zA-Z0-9_.]+")))
        assertEquals("客户端 Activity 必须来自测试 APK", clientPackage, activity.packageName)
        assertTrue("客户端必须具有独立 UID", activity.applicationInfo.uid != targetContext.applicationInfo.uid)
        validated = true
        writeEvidence("合成客户端身份确认 runId=$runId package=$clientPackage uid=${activity.applicationInfo.uid}")
        // 旧轮次显式授权会令 PD-05 直接去确认页；只恢复合成调用方的首次绑定前提。
        val preferences = targetContext.getSharedPreferences(TRUST_PREFERENCES, android.content.Context.MODE_PRIVATE)
        trustPreferences = preferences
        savedClientTrust = preferences.all.filterKeys { it.startsWith("$clientPackage|") }
            .mapValues { (_, value) -> value as? Boolean ?: error("合成客户端信任记录类型异常") }
        check(preferences.edit().also { editor -> savedClientTrust.keys.forEach { editor.remove(it) } }.commit())
        coldStop("前置")
    }

    /** 窗口出现不足以准入：本实例必须有一次成功 requestAutofill 返回及全新实例身份。 */
    fun startFresh(stage: String) {
        check(validated) { "客户端身份未验证，禁止启动或冷停" }
        if (formRunId.isNotEmpty()) finishCurrent()
        coldStop("启动前-$stage")
        formRunId = "$runId-${++formNumber}"
        val username = AutofillSeedContract.USERNAME.toCharArray()
        val password = AutofillSeedContract.PASSWORD.toCharArray()
        try {
            targetContext.startActivity(clientIntent().apply {
                putExtra(AutofillClientActivity.EXTRA_RUN_ID, formRunId)
                putExtra(AutofillClientActivity.EXTRA_EXPECTED_USERNAME, username)
                putExtra(AutofillClientActivity.EXTRA_EXPECTED_PASSWORD, password)
            })
        } finally {
            username.fill('\u0000')
            password.fill('\u0000')
        }
        val deadline = SystemClock.uptimeMillis() + REQUEST_WAIT_MS
        var request = ""
        while (SystemClock.uptimeMillis() < deadline) {
            request = probe.shell("logcat -d -v threadtime").lineSequence().firstOrNull {
                it.contains(" ${AutofillClientActivity.TAG}:") && it.contains("requestAutofill(") &&
                    it.contains(" runId=$formRunId ") && it.contains(" requestCount=1") &&
                    it.contains(" instanceId=") && it.contains(" pid=")
            }.orEmpty()
            if (request.isNotEmpty()) break
            SystemClock.sleep(POLL_MS)
        }
        writeEvidence("独立新请求 stage=$stage formRunId=$formRunId evidence=$request")
        assertTrue("客户端窗口不能代替本轮独立新请求证据: $formRunId", request.isNotEmpty())
    }

    fun cleanup() {
        if (!validated) return // 身份校验失败时不对任何包执行命令。
        try {
            if (formRunId.isNotEmpty()) finishCurrent()
        } finally {
            try {
                coldStop("后置") // 即使协议取消失败仍结束测试进程，且不 clear data。
            } finally {
                trustPreferences?.let { preferences ->
                    val editor = preferences.edit()
                    preferences.all.keys.filter { it.startsWith("$clientPackage|") }.forEach { editor.remove(it) }
                    savedClientTrust.forEach { (key, value) -> editor.putBoolean(key, value) }
                    check(editor.commit()) { "合成客户端信任前提恢复失败" }
                }
            }
        }
    }

    private fun finishCurrent() {
        targetContext.startActivity(clientIntent().apply {
            action = AutofillClientActivity.ACTION_FINISH_RUN
            putExtra(AutofillClientActivity.EXTRA_RUN_ID, formRunId)
        })
        val deadline = SystemClock.uptimeMillis() + CLOSE_WAIT_MS
        var cancelled = false
        while (SystemClock.uptimeMillis() < deadline) {
            cancelled = probe.shell("logcat -d -v threadtime").lineSequence().any {
                it.contains(" ${AutofillClientActivity.TAG}:") && it.contains("cancelled runId=$formRunId ")
            }
            if (cancelled) break
            SystemClock.sleep(POLL_MS)
        }
        writeEvidence("客户端取消 Autofill context/Handler 并结束实例 formRunId=$formRunId acknowledged=$cancelled")
        check(cancelled) { "客户端本轮取消协议未确认: $formRunId" }
    }

    private fun coldStop(reason: String) {
        val before = probe.shell("pidof $clientPackage").trim()
        val result = probe.shell("am force-stop $clientPackage")
        val after = probe.shell("pidof $clientPackage").trim()
        writeEvidence("仅冷停合成客户端 reason=$reason beforePid=$before afterPid=$after result=$result")
        check(after.isEmpty()) { "合成客户端冷停失败，禁止沿用旧实例: $clientPackage" }
    }

    private fun clientIntent(): Intent = Intent().apply {
        component = ComponentName(clientPackage, CLIENT_CLASS_NAME)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private companion object {
        const val TRUST_PREFERENCES = "keepasskey_autofill_caller_trust"
        const val CLIENT_CLASS_NAME = "com.keepasskey.app.autofill.AutofillClientActivity"
        const val REQUEST_WAIT_MS = 15_000L
        const val CLOSE_WAIT_MS = 3_000L
        const val POLL_MS = 200L
    }
}
