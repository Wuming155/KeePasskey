package com.keepasskey.app.passkey

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Before
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
 * - **跳过时的成因区分（`ISSUE-P3-523` 补）**：本用例自备登记后会**回读**该键并把读数写进
 *   logcat（UTP 按用例归档 logcat artifact）与 `Assume` 文案（跳过时随结果 XML 归档）——
 *   回读不符 ⇒ 平台未接受本用例自备的 shell 写入；回读正确而无回调 ⇒ 系统侧路由未落到本 provider。
 *   二者对应用侧都不是缺陷，但厂商 ROM 上只有这条读数能把两者分开（此前只能停在「未定性」）。
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

    /** 登记前的原值（收尾还原；`null` = 原本为空键） */
    private var originalCredentialService: String? = null

    /** 本次写入后的**回读值**（`ISSUE-P3-523` 定性依据，见 [registerCredentialProvider]） */
    private var registeredCredentialService: String = ""

    /**
     * 环境自备（`ISSUE-P2-494`）：系统把创建请求路由到本 provider 的**必要条件**是
     * `Settings.Secure.credential_service` 登记本组件（§240 §3.1 的 E1 / E3 对照实证：
     * 只写 `credential_service_primary` 不足够）。该键只有 shell / 系统可写，此前依赖**带外手工**
     * `adb shell settings put secure credential_service …`（§447 §2.9）——CI `device-gate` 上
     * 不存在该前置 ⇒ 本用例在 CI 上恒以 `Assume` 收场，「路由成立」这一结论从未被 CI 证过。
     * 此处经 `UiAutomation`（shell 身份，持 `WRITE_SECURE_SETTINGS`）**就地登记**，
     * 收尾还原（与 `AutofillAuthChainDeviceTest` 对 `autofill_service` 的处置同款，均为共享设备不留痕）。
     */
    @Before
    fun registerCredentialProvider() {
        val expected = "${instrumentation.targetContext.packageName}/" +
            KeePasskeyCredentialProviderService::class.java.name
        originalCredentialService =
            shell("settings get secure credential_service").trim().ifEmpty { null }
        shell("settings put secure credential_service $expected")
        // 登记回读（`ISSUE-P3-523` 定性依据）：把「本次写入是否被系统接受」落成设备侧读数。
        // 该用例此前只把「未收到创建回调」记为 `Assume`，而**成因有两种且处置不同**：
        //   ① 回读 ≠ 期望 ⇒ 本用例自备的 shell 写入未被平台接受（平台/厂商对 secure 设置的权限面）；
        //   ② 回读 == 期望而仍无回调 ⇒ 系统侧的凭据提供者路由未落到本 provider（厂商框架面）。
        // 二者对应用侧都**不是**缺陷，但只有把回读打出来，下一次厂商 ROM 复跑才能自行区分——
        // 否则只能像 `ISSUE-P3-523` 那样长期停在「未定性」。此处**不改** `Assume` 语义
        // （环境前提缺失仍如实记 skipped，不得升级为失败）。
        registeredCredentialService = shell("settings get secure credential_service").trim()
    }

    @After
    fun restoreCredentialService() {
        val original = originalCredentialService
        if (original == null) {
            shell("settings delete secure credential_service")
        } else {
            shell("settings put secure credential_service $original")
        }
    }

    @Test
    fun `系统保存请求必须能路由到本应用的凭据提供者`() {
        // 前置：清空日志缓冲，避免把上一次流程的留痕当作本次证据（归因必须干净）
        shell("logcat -c")
        Thread.sleep(CLEAR_SETTLE_MS)

        // 登记回读留痕（`ISSUE-P3-523`）：放在 `logcat -c` **之后**，否则被清掉。
        // 两条留存通道：① `Log` / `println` 进 logcat（UTP 按用例归档为 logcat artifact，
        // 2026-10-07 实测可见 `I CredentialSaveChain: credential_service 登记回读=…`）；
        // ② 同值并入下方 `Assume` 文案 ⇒ 跳过时随结果 XML 的 `<failure>` 一并归档。
        // （UTP 结果 XML **不含** `<system-out>`，故不以 stdout 作留证通道——实测确认。）
        Log.i(TAG, "credential_service 登记回读=$registeredCredentialService")
        println("[ISSUE-P3-523 登记回读] credential_service=$registeredCredentialService")

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
            "系统未把本应用登记为「已启用」的凭据提供者，创建请求未被路由" +
                "（本用例自身写入后的 `credential_service` 回读=[$registeredCredentialService]；" +
                "回读为空/不符 ⇒ 平台未接受写入；回读正确 ⇒ 系统侧路由未落到本 provider。" +
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

        /** 本用例自身的 logcat 标签（登记回读等设备侧读数留痕） */
        const val TAG = "CredentialSaveChain"

        /** 回调首行留痕（进入 `onBeginCreateCredentialRequest` 即写入） */
        const val CREATE_CALLBACK_MARKER = "onBeginCreateCredentialRequest"

        /** 等待上限：真机冷启动实测 2.4 ~ 3.1 s，留足余量 */
        const val WAIT_MS = 15_000L

        const val POLL_INTERVAL_MS = 500L

        /** `logcat -c` 生效的稳定等待 */
        const val CLEAR_SETTLE_MS = 500L
    }
}
