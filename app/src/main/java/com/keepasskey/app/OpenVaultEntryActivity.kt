package com.keepasskey.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
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
 * 只接受 content/file 两类 scheme、只接受文件名以 `.kdbx` 结尾的对象——非法输入
 * 一律拒绝并回落主界面，绝不把任意 URI 注入库登记表。
 *
 * ## 安全面评估（如实声明）
 *
 * - 本页只把 URI 交给**既有** `importExternalDatabase` 链路（与页内 SAF 选择器同一入口，
 *   含持久化授权尝试与脱敏告警）；库内容是否可用仍由主密码 / 密钥文件的解密过程裁决，
 *   非 KDBX 文件在登记后无法解锁（fail-closed 于密码学层，不依赖本页判定）；
 * - SEND 的文字 extras（EXTRA_TEXT）一律忽略，只取 `EXTRA_STREAM` 的二进制对象。
 *
 * 转交主界面后落在解锁页（`importExternalDatabase` 已把新库置为活动库）；
 * 无法解析显示名的对象按非法输入拒绝。导入失败以 Toast 如实提示后仍回落主界面。
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
        val displayName = uri?.let { resolveDisplayName(it) }
        if (uri == null || displayName == null || !displayName.endsWith(".kdbx", ignoreCase = true)) {
            AppLog.i(TAG, "外部打开请求被拒绝：非 KDBX 库文件")
            toast(R.string.external_open_invalid)
            launchMainAndFinish()
            return
        }

        lifecycleScope.launch {
            val result = vaultRepository.importExternalDatabase(displayName, uri.toString())
            if (result is KdbxResult.Failure) {
                // 不透传异常 message（可能含路径形态）；仅记录类型
                AppLog.w(TAG, "外部打开登记失败: ${result.error.javaClass.simpleName}")
                toast(R.string.external_open_failed)
            }
            launchMainAndFinish()
        }
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
     * 解析显示名：content URI 经 OpenableColumns 查询；file URI 取路径末段。
     * 查询不到（Provider 异常 / 权限缺失）返回 null，按非法输入拒绝。
     */
    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let { path -> File(path).name }
        }
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

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "OpenVaultEntry"
    }
}
