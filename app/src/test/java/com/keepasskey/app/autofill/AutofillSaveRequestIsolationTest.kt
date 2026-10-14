package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-603 / ISSUE-P2-606：锁定态自动填充保存请求**身份隔离**与等待解锁期口令数组
 * **所有权**的接线守卫（Activity / Service 依赖 Android 宿主，宿主 JVM 无法直接驱动，
 * 故以源码结构断言锁定关键接线，与 `AutofillSaveRobustnessTest` 同法）。
 */
class AutofillSaveRequestIsolationTest {

    private val service = readSource(SERVICE_PATH)
    private val activity = readSource(ACTIVITY_PATH)
    private val pendingIntentFactory = readSource(PENDING_INTENT_FACTORY_PATH)

    @Test
    fun `保存请求使用不可预测令牌与数据身份`() {
        assertTrue(
            "服务侧必须为每个保存请求生成不可预测令牌",
            service.contains("java.util.UUID.randomUUID().toString()")
        )
        assertTrue(
            "令牌必须写入 Intent.data（参与 PendingIntent.filterEquals，构成请求身份）",
            pendingIntentFactory.contains("data = Uri.parse(\"keepasskey://autofill-save-request/\$requestToken\")")
        )
        assertTrue(
            "令牌必须随 extras 交给 Activity",
            pendingIntentFactory.contains("PasswordSaveActivity.EXTRA_REQUEST_TOKEN, requestToken")
        )
    }

    @Test
    fun `保存请求 requestCode 改由单调分配器给出而非常量`() {
        assertTrue(
            "必须存在进程级单调分配器（与认证 100 / CM 1000 段同构）",
            service.contains("AtomicInteger(REQUEST_CODE_SAVE)") &&
                service.contains("saveRequestCodeAllocator.getAndIncrement()")
        )
        assertFalse(
            "不得再把固定常量 requestCode 直接交给 PendingIntent.getActivity",
            service.contains("            REQUEST_CODE_SAVE,")
        )
    }

    @Test
    fun `Activity 校验请求令牌并单次消费`() {
        assertTrue(activity.contains("intent.getStringExtra(EXTRA_REQUEST_TOKEN)"))
        assertTrue(
            "缺令牌的自动填充保存请求必须 fail-closed",
            activity.contains("providerReq == null && requestToken.isNullOrBlank()")
        )
        assertTrue(
            "已消费令牌必须拒绝重复处理",
            activity.contains("saveRequestLedger.isConsumed(requestToken)")
        )
        assertTrue(
            "成功 / 明确失败 / 异常路径必须登记消费",
            activity.contains("saveRequestLedger.markConsumed(requestToken)")
        )
    }

    @Test
    fun `等待解锁期口令数组有独占所有权且销毁即释放`() {
        assertTrue(
            "数组须登记为本 Activity 在等待期独占的载体",
            activity.contains("private var pendingPasswordChars: CharArray?") &&
                activity.contains("pendingPasswordChars = passwordChars")
        )
        assertTrue(
            "销毁路径必须释放等待副本",
            activity.contains("override fun onDestroy()") &&
                activity.contains("releasePendingPassword()")
        )
        assertTrue(
            "所有权转移后须注销，避免与保存任务双擦",
            activity.contains("pendingPasswordChars = null")
        )
        assertFalse(
            "不得把敏感口令数组写入 SavedState",
            activity.contains("onSaveInstanceState")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在: $path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SERVICE_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt"
        const val ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/PasswordSaveActivity.kt"
        const val PENDING_INTENT_FACTORY_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/LockedSaveRequestPendingIntent.kt"

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根目录")
        }
    }
}
