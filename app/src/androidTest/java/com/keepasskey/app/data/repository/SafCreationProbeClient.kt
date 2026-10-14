package com.keepasskey.app.data.repository

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation

/** 594合成提供方客户端；目标测试包仅获control594精确URI授权，并在每例清理时撤销。 */
internal class SafCreationProbeClient {
    private val instrumentation = getInstrumentation()
    val testContext = instrumentation.context
    val targetContext = instrumentation.targetContext
    private val authority: String
    private var firstControlCall = true

    init {
        @Suppress("DEPRECATION")
        val packageInfo = testContext.packageManager.getPackageInfo(
            testContext.packageName,
            PackageManager.GET_PROVIDERS
        )
        val providers = requireNotNull(packageInfo.providers).filter {
            it.name == SafCreationProbeDocumentsProvider::class.java.name
        }
        check(providers.size == 1) { "测试 APK 必须且只能注册一个 594 DocumentsProvider" }
        check(providers.single().packageName == testContext.packageName) {
            "594 DocumentsProvider 必须归测试 APK 所有"
        }
        authority = requireNotNull(providers.single().authority)
        targetContext.startActivity(Intent().apply {
            setClassName(testContext.packageName, SafCreationProbeActivity::class.java.name)
            putExtra(SafCreationProbeActivity.EXTRA_BOOTSTRAP, true)
            putExtra("target", targetContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun create(name: String, persist: Boolean = true): Uri = Uri.parse(
        call("594:create", extras = Bundle().apply {
            putString("name", name)
            putBoolean("persist", persist)
            putString("target", targetContext.packageName)
        }).getString("uri") ?: error("594:create 未返回 URI")
    )

    fun setFault(uri: Uri, fault: String) {
        call("594:fault", documentId(uri), Bundle().apply { putString("fault", fault) })
    }

    fun seedThreeBytes(uri: Uri) {
        call("594:seed", documentId(uri))
    }

    fun state(uri: Uri): Bundle = call("594:state", documentId(uri))

    fun clearResult() {
        call("594:clearResult")
    }

    fun result(): Bundle = call("594:result")

    fun report(result: Bundle) {
        call("594:report", extras = result)
    }

    fun cleanupAndEnd() {
        var cleanupFailure: Throwable? = null
        try {
            call("594:cleanup")
        } catch (error: Throwable) {
            cleanupFailure = error
        }
        try {
            call("594:end")
        } catch (error: Throwable) {
            if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
        }
        cleanupFailure?.let { throw it }
    }

    fun providerUri(): Uri = Uri.parse("content://$authority/${SafCreationProbeActivity.CONTROL_PATH}")

    private fun documentId(uri: Uri): String = DocumentsContract.getDocumentId(uri)

    private fun call(method: String, arg: String? = null, extras: Bundle = Bundle()): Bundle {
        fun invoke(): Bundle = requireNotNull(targetContext.contentResolver.call(providerUri(), method, arg, extras)) {
            "$method 未返回控制回执"
        }
        if (!firstControlCall) return invoke()
        firstControlCall = false
        val deadline = SystemClock.elapsedRealtime() + CONTROL_GRANT_TIMEOUT_MS
        while (true) {
            try {
                return invoke()
            } catch (error: SecurityException) {
                val message = error.message
                val grantNotReady = message?.contains("Permission Denial: opening provider") == true ||
                    message?.contains("SAF 594 test control requires its APK, target control grant, or MANAGE_DOCUMENTS") == true
                if (!grantNotReady) throw error
                if (SystemClock.elapsedRealtime() >= deadline) throw error
                Thread.sleep(CONTROL_GRANT_RETRY_MS)
            }
        }
    }

    private companion object {
        const val CONTROL_GRANT_TIMEOUT_MS = 5_000L
        const val CONTROL_GRANT_RETRY_MS = 100L
    }
}
