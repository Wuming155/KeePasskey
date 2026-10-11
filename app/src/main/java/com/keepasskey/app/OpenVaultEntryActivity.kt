package com.keepasskey.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.VaultOpenUriPolicy
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 「打开方式」外部打开入口（ISSUE-P3-298 ①）：文件管理器 / 云盘应用对 `.kdbx` 文件选择
 * 「打开方式 → KeePasskey」时，系统经本页承接 `ACTION_VIEW` / `ACTION_SEND` 请求，
 * 校验后登记为应用内密码库并拉起主界面进入解锁流程。
 *
 * ## 为什么不是 MainActivity（ISSUE-P3-85 既有裁决的延续）
 *
 * 启动器入口受 `ExportedComponentHygieneTest` 约束：`MainActivity` 不得消费任何外部
 * intent 数据（`singleTask` + 空 taskAffinity 只把攻击面压到 DoS 级，消费数据即升级为
 * 输入注入面）。本页是有意新增的**受控数据消费入口**：只接受 VIEW/SEND 两种 action、
 * **只接受 `content://` 一类 scheme**（`file://` / `http(s)://` / 相对路径一律拒绝）、
 * 只接受**末段为纯文件名**且以 `.kdbx` 结尾的显示名——非法输入一律拒绝并回落主界面，
 * 绝不把任意 URI 注入库登记表。
 *
 * ## 安全面评估（如实声明）
 *
 * - **scheme 白名单与显示名形态**由纯函数 [VaultOpenUriPolicy] 判定（`ISSUE-P3-575` AC①/A③）：
 *   此前本页连 `file://` 与 `content://` 都不区分，任意应用可用无 `FLAG_GRANT` 的 `file://` 路径
 *   直达登记表；现收敛为仅 `content://`（AGENTS.md §3 防御性安全，`file://` 未证明存在可预期来源形态故直接拒绝）。
 * - **登记前用户确认**（`ISSUE-P3-575` AC②）：外部 Intent 由任意应用可发，登记会**覆写活动库指针**
 *   （`importExternalDatabase` 末端 `saveActiveDbId`），故一律先弹确认；取消 / 返回只回落主界面、
 *   **不改任何状态**，外部输入不得静默改状态。
 * - 本页只把 URI 交给**既有** `importExternalDatabase` 链路（与页内 SAF 选择器同一入口，
 *   含持久化授权尝试与脱敏告警）；库内容是否可用仍由主密码 / 密钥文件的解密过程裁决，
 *   非 KDBX 文件在登记后无法解锁（fail-closed 于密码学层，不依赖本页判定）；
 * - SEND 的文字 extras（EXTRA_TEXT）一律忽略，只取 `EXTRA_STREAM` 的二进制对象。
 *
 * 确认后转交主界面并落在解锁页（`importExternalDatabase` 已把新库置为活动库）；
 * 无法解析显示名的对象按非法输入拒绝。导入失败如实提示后仍回落主界面
 * （ISSUE-P3-446：提示改发全局 Snackbar 通道——本页随即拉起 `MainActivity`，
 * 外壳宿主组合后即消费缓冲中的事件；不再用 Toast）。
 */
@AndroidEntryPoint
class OpenVaultEntryActivity : FragmentActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 官方反 overlay 攻击加固（与其它敏感入口同口径）；本页无内容渲染，转瞬即逝
        window.setHideOverlayWindows(true)

        val uri = extractKdbxUri(intent)
        // ISSUE-P3-575 AC①：scheme 白名单——只接受 content://（受 FLAG_GRANT）；
        // file:// 与其它 scheme（http(s)/相对路径/空 scheme）一律拒绝，绝不交给登记链路
        if (uri == null || VaultOpenUriPolicy.acceptInboundUri(uri.toString()) !is VaultOpenUriPolicy.Decision.Accept) {
            rejectAndFallback()
            return
        }
        val displayName = resolveDisplayName(uri)
        if (displayName == null ||
            VaultOpenUriPolicy.acceptDisplayName(displayName) !is VaultOpenUriPolicy.Decision.Accept
        ) {
            rejectAndFallback()
            return
        }
        // ISSUE-P3-575 AC②：登记 / 覆写活动库指针前必须用户确认，外部输入不得静默改状态
        confirmThenImport(uri, displayName)
    }

    /** 只接受 VIEW（data URI）与 SEND（EXTRA_STREAM）；其余 action / 文字 extras 一律忽略 */
    private fun extractKdbxUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    /**
     * ISSUE-P3-575 AC②：登记前用户确认。外部 Intent 由任意应用可发，而登记会**改写活动库指针**，
     * 故不得静默执行——确认后登记并进入解锁流程；取消 / 返回只回落主界面、不改任何状态。
     */
    private fun confirmThenImport(uri: Uri, displayName: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.external_open_confirm_title)
            .setMessage(getString(R.string.external_open_confirm_message, displayName))
            .setPositiveButton(R.string.external_open_confirm_ok) { _, _ -> performImport(uri, displayName) }
            .setNegativeButton(R.string.external_open_confirm_cancel) { _, _ -> launchMainAndFinish() }
            .setOnCancelListener { launchMainAndFinish() }
            .show()
    }

    /** 用户已确认：登记外部库（既有链路）并回落主界面。 */
    private fun performImport(uri: Uri, displayName: String) {
        lifecycleScope.launch {
            val result = vaultRepository.importExternalDatabase(displayName, uri.toString())
            if (result is KdbxResult.Failure) {
                // 不透传异常 message（可能含路径形态）；仅记录类型
                AppLog.w(TAG, "外部打开登记失败: ${result.error.javaClass.simpleName}")
                notifyUi(R.string.external_open_failed)
            }
            launchMainAndFinish()
        }
    }

    /** 非法输入：仅落类型化日志 + 全局提示，绝不登记任何 URI（ISSUE-P3-575）。 */
    private fun rejectAndFallback() {
        AppLog.i(TAG, "外部打开请求被拒绝：非受支持的 KDBX 来源")
        notifyUi(R.string.external_open_invalid)
        launchMainAndFinish()
    }

    /**
     * 解析显示名：**仅** `content://` 经 OpenableColumns 查询（`file://` 已在准入时被拒，
     * 故不再有「取路径末段」分支）。查询不到（Provider 异常 / 权限缺失）返回 null，按非法输入拒绝。
     */
    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        } catch (t: Throwable) {
            AppLog.w(TAG, "外部打开显示名解析失败: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun launchMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * ISSUE-P3-446：反馈改发全局 Snackbar 通道（文案不变）。
     * 本页立即 `launchMainAndFinish()`：事件缓冲于 `AppSnackbarChannel`（容量 64 + DROP_OLDEST），
     * `MainActivity` 外壳组合后由全局宿主即时消费——主界面既存则当屏显示，冷启动则落解锁页后显示。
     */
    private fun notifyUi(resId: Int) {
        AppSnackbarChannel.trySend(AppSnackbarEvent(UiMessage(resId)))
    }

    private companion object {
        const val TAG = "OpenVaultEntry"
    }
}
