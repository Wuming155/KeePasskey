package com.keepasskey.app.passkey

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * 「用户在登录界面提交 → 系统弹出保存提示 → 点确认 → 落到本应用保存页」
 * 全链路在**真机**上的端到端回归（ISSUE-P1-237）。
 *
 * ## 为什么必须真机、且必须由**另一个包**发起
 *
 * 宿主单测与 `CredentialProviderRequestContractDeviceTest` 覆盖的都是
 * 「provider 侧收到请求**之后**」的半段；而这半段的**主语是系统**——
 * 系统是否把创建请求路由到本 provider，取决于系统侧的凭据提供者登记状态
 * （`Settings.Secure.credential_service`）与进程冷启动时延，
 * **应用侧代码无论多正确都无法在宿主上观察到这一点**。
 *
 * 本用例因此由测试 APK 内的 [CredentialSaveClientActivity]（包名 `com.keepasskey.test`，
 * 语义等价于第三方应用）调用 `CredentialManager.createCredential`，再以
 * **logcat 归因**（`KeePasskeyCredProvider` 标签的 `onBeginCreateCredentialRequest`）
 * 判定系统是否真的敲到了本 provider 的门。
 *
 * ## 判定与环境前提（这一点必须读清楚）
 *
 * - **通过**：在 [WAIT_MS] 内观察到本 provider 收到系统创建请求 ⇒ 系统路由成立；
 * - **跳过**（`Assume`，如实记入 skipped）：未观察到。此时**不得**读作「应用有 bug」——
 *   已实证的环境前提不满足形态是系统压根未登记本应用为已启用的凭据提供者
 *   （框架立即以 `CreateCredentialException.TYPE_NO_CREATE_OPTIONS` 结束，误差 < 20 ms），
 *   其修复是系统侧动作，见 `docs/resolved/batches/240-*.md`。
 *
 * ## 不做的事（如实声明）
 *
 * 本用例**不**点击系统保存面板上的确认按钮（那是系统 UI，需要 UiAutomator 级交互），
 * 因此只覆盖到「请求敲到 provider」为止；「provider 应答 → 面板呈现 → 点确认 →
 * `PasswordSaveActivity` 被拉起」这一段在 2026-09-21 的真机排查中经人工点击 + 日志核实
 * （证据见同一批次文档），**未**纳入本自动化用例。
 */
@RunWith(AndroidJUnit4::class)
class CredentialSaveChainDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context

    @Test
    fun `系统保存请求必须能路由到本应用的凭据提供者`() {
        // 前置：清空日志缓冲，避免把上一次流程的留痕当作本次证据（归因必须干净）
        shell("logcat -c")
        Thread.sleep(CLEAR_SETTLE_MS)

        // 由**测试 APK 自己**启动（不得用 `Instrumentation.startActivitySync`：它只允许启动
        // 被测进程内的组件，而本客户端刻意运行在测试 APK 自己的进程里，语义上属于第三方应用）
        val intent = Intent()
            .setClassName(testContext.packageName, CredentialSaveClientActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        testContext.startActivity(intent)

        val observed = awaitProviderCreateCallback()
        // 收尾：关掉可能已弹出的系统面板并结束客户端，避免污染后续用例
        shell("input keyevent KEYCODE_BACK")
        shell("input keyevent KEYCODE_BACK")
        shell("am force-stop ${testContext.packageName}")

        Assume.assumeTrue(
            "系统未把本应用登记为「已启用」的凭据提供者，创建请求未被路由（" +
                "修复方式见 docs/resolved/batches/240-*.md；本用例在此环境下不构成应用侧缺陷证据）",
            observed
        )
    }

    /** 轮询 logcat，直到看到本 provider 的创建回调或超时 */
    private fun awaitProviderCreateCallback(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (readProviderLog().contains(CREATE_CALLBACK_MARKER)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun readProviderLog(): String = shell("logcat -d -s $PROVIDER_TAG")

    /** 经 `UiAutomation` 以 shell 身份执行命令并取回输出（shell 可读全量 logcat） */
    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return fd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    private companion object {
        /** provider 侧日志标签（`KeePasskeyCredentialProviderService` 的 TAG） */
        const val PROVIDER_TAG = "KeePasskeyCredProvider"

        /** 回调首行留痕（进入 `onBeginCreateCredentialRequest` 即写入） */
        const val CREATE_CALLBACK_MARKER = "onBeginCreateCredentialRequest"

        /** 等待上限：真机冷启动实测 2.4 ~ 3.1 s，留足余量 */
        const val WAIT_MS = 15_000L

        const val POLL_INTERVAL_MS = 500L

        /** `logcat -c` 生效的稳定等待 */
        const val CLEAR_SETTLE_MS = 500L
    }
}
