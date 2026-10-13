package com.keepasskey.app.autofill

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.util.Log
import com.keepasskey.app.ui.localizedContextForAppLanguage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** ISSUE-P3-608：文字从被测 APK 的真实资源语言读取，绝不使用测试 APK 的资源。 */
internal class AutofillTestLabels(private val targetContext: Context) {
    /** Service 数据集使用其原始 Context，与生产 getString 保持同源。 */
    fun dataset(resourceId: Int): String = targetContext.getString(resourceId)

    /** 认证 Compose 另有应用内语言覆盖，读取当前 Activity 已注入的同一个设置仓库。 */
    fun activity(resourceId: Int, activity: Activity?): String {
        val settings = when (activity) {
            is AutofillUnlockActivity -> activity.settingsRepository
            is AutofillConfirmActivity -> activity.settingsRepository
            else -> error("定位认证按钮时没有对应的当前认证 Activity")
        }
        return runBlocking {
            withTimeout(SETTINGS_WAIT_MS) {
                val localized = localizedContextForAppLanguage(targetContext, settings)
                Log.i("AutofillAC3", "认证页面实际资源语言=${localized.resources.configuration.locales.toLanguageTags()}")
                localized.getString(resourceId)
            }
        }
    }

    fun environment(): String {
        val manager = requireNotNull(targetContext.getSystemService(LocaleManager::class.java)) {
            "无法读取系统与应用语言前提"
        }
        val testContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        return "系统语言=${manager.systemLocales.toLanguageTags()}；" +
            "Android 应用语言=${manager.applicationLocales.toLanguageTags()}；" +
            "targetContext 语言=${targetContext.resources.configuration.locales.toLanguageTags()}；" +
            "测试 APK 语言=${testContext.resources.configuration.locales.toLanguageTags()}；" +
            "未主动修改任何语言配置"
    }

    private companion object {
        const val SETTINGS_WAIT_MS = 5_000L
    }
}
