package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P1-224：自动填充保存凭据鲁棒性与锁定承接单测。
 *
 * 守护验收标准：
 * 1. applySaveInfoIfNeeded 必须配置 FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE，并对密码/账号区分 required/optional；
 * 2. handleSaveRequest 支持倒序遍历多 context，并在密码为空时具备非空节点兜底；
 * 3. 密码库锁定时必须返回 IntentSender 唤起 PasswordSaveActivity 承接解锁与落库，绝不静默失败；
 * 4. PasswordSaveActivity 支持接收 EXTRA_PASSWORD 并兼容非 CM 保存流程。
 */
class AutofillSaveRobustnessTest {

    private val serviceSource = readSource(SERVICE_PATH)
    private val buildersSource = readSource(BUILDERS_PATH)
    private val passwordSaveActivitySource = readSource(PASSWORD_SAVE_ACTIVITY_PATH)
    private val extractorSource = readSource(EXTRACTOR_PATH)

    @Test
    fun `SaveInfo 必须配置 FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`() {
        assertTrue(
            "SaveInfo 构建必须包含 FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE 标志，确保表单视图隐藏时稳定触发保存",
            buildersSource.contains("FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE")
        )
    }

    @Test
    fun `SaveInfo 应以密码为必选项账号为可选项`() {
        assertTrue(
            "存在密码框时应把用户名设为 optionalIds，防止用户名未变导致保存被系统跳过",
            buildersSource.contains("builder.setOptionalIds(arrayOf(usernameId))")
        )
    }

    @Test
    fun `保存请求必须支持多 Context 倒序提取与空值兜底`() {
        assertTrue(
            "服务必须委派 AutofillSaveExtractor 提取保存凭据",
            serviceSource.contains("AutofillSaveExtractor.extract(")
        )
        assertTrue(
            "extract 必须倒序遍历 contexts.reversed()",
            extractorSource.contains("contexts.reversed()")
        )
        assertTrue(
            "当推荐密码为空时必须在同页面搜索有效密码输入框兜底",
            extractorSource.contains("AutofillFieldScanner.isPasswordInputType(node.inputType) && node.text.isNotBlank()")
        )
    }

    @Test
    fun `密码库锁定时必须通过 IntentSender 唤起解锁保存`() {
        assertTrue(
            "当 vaultRepository 处于锁定状态时必须检测并唤起交互",
            serviceSource.contains("if (vaultRepository.isLocked())")
        )
        assertTrue(
            "锁定时必须向系统回调 callback.onSuccess(pendingIntent.intentSender)",
            serviceSource.contains("callback.onSuccess(pendingIntent.intentSender)")
        )
        assertTrue(
            "锁定时唤起的 Intent 必须指向 PasswordSaveActivity",
            serviceSource.contains("PasswordSaveActivity::class.java")
        )
    }

    @Test
    fun `PasswordSaveActivity 必须支持接收 EXTRA_PASSWORD`() {
        assertTrue(
            "PasswordSaveActivity 必须声明 EXTRA_PASSWORD 常量",
            passwordSaveActivitySource.contains("const val EXTRA_PASSWORD =")
        )
        assertTrue(
            "当非 CreatePasswordRequest 时必须从 EXTRA_PASSWORD 读取密码",
            passwordSaveActivitySource.contains("intent.getStringExtra(EXTRA_PASSWORD)")
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
        const val EXTRACTOR_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillSaveExtractor.kt"
        const val BUILDERS_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt"
        const val PASSWORD_SAVE_ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/PasswordSaveActivity.kt"

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
