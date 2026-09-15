package com.keepasskey.app.autofill

import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.Presentations
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.AutofillAuthBindingPolicy
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricStatus
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统自动填充的「手动选择器」落地 Activity (ISSUE-P3-40)。
 *
 * 背景：自动匹配严格（严格域名 / 严格包名，无启发式），在弱域名、纯 App、内嵌 WebView 等
 * 场景下可能零候选，用户此前无任何补救路径。本 Activity 提供全库搜索与手动选择兜底。
 *
 * 回传机制（官方语义）：选择器以认证数据集方式挂入 `FillResponse`；用户选中后本 Activity
 * 构建真实 [Dataset] 并经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传，
 * 由框架完成填充——**数据集只在用户显式选中并确认后才携带明文**。
 *
 * 安全加固（对齐 AutofillConfirmActivity）：FLAG_SECURE 防截屏录屏 +
 * `setHideOverlayWindows` 反悬浮窗覆盖 + 遮挡触摸过滤（反点击劫持）。
 */
@AndroidEntryPoint
class AutofillPickerActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    // ISSUE-P3-43 ②：字段签名级屏蔽的**写入入口**——选择器是用户唯一能明确指认
    // 「就是这个表单的这个框别再填」的位置，故写入方落在此处而非设置页
    @Inject
    lateinit var autofillFieldBlocklistStore: AutofillFieldBlocklistStore

    // ISSUE-P2-70：请求方签名证书读取通道（与确认页归属展示复用同一实现）
    @Inject
    lateinit var autofillOriginResolver: AutofillOriginResolver

    // ISSUE-P2-46：调用方「包名 + 签名摘要」首次绑定存储。选择器是**唯一**由用户显式指认
    // 「把这条凭据填给这个调用方」的入口（受保护窗口 + 已展示包名 / 应用名 / 签名摘要），
    // 故首次绑定写入落在此处；写入后 `android://` 维度才对同签名调用方参与自动匹配。
    @Inject
    lateinit var callerTrustStore: AutofillCallerTrustStore

    private val viewModel: AutofillPickerViewModel by viewModels()

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true

        val requester = resolveRequester()

        setContent {
            var query by remember { mutableStateOf("") }
            val entries by viewModel.entries.collectAsStateWithLifecycle()
            val results = remember(query, entries) { AutofillEntrySearch.filter(entries, query) }

            ApplyObscuredTouchFilter()
            AutofillPickerScreen(
                query = query,
                onQueryChange = { query = it },
                results = results,
                onPick = ::confirmAndFill,
                onCancel = ::finish,
                // ISSUE-P2-70：强制展示请求方身份（包名 / 应用名 / 签名摘要 / 表单自报域）
                requester = requester,
                // 仅在本次请求确实识别到对应框时提供屏蔽入口（否则是无对象的假按钮）
                canBlockUsername = readAutofillId(EXTRA_USERNAME_ID) != null,
                canBlockPassword = readAutofillId(EXTRA_PASSWORD_ID) != null,
                onBlockField = ::blockFieldAndFinish
            )
        }
    }

    /**
     * ISSUE-P2-70：解析本次填充的请求方身份。
     *
     * - 包名取自 extra（系统结构树下发，**不可伪造锚点**）；缺失 → 返回 null（不展示归属块）；
     * - 应用名经 PackageManager 读取，**可被应用自声明**，仅作辅助识别，读取失败按无名称处理；
     * - 签名证书 SHA-256 经 [AutofillOriginResolver] 读取（与确认页同一通道），不可读时如实标注；
     * - 域为**表单自报且未经归属校验**（本页不据其放行，仅如实展示给用户）。
     */
    private fun resolveRequester(): AutofillPickerRequester? {
        val callingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE)
        if (callingPackage.isNullOrBlank()) return null
        val label = try {
            val appInfo = packageManager.getApplicationInfo(callingPackage, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (t: Throwable) {
            // 包可见性受限 / 应用已卸载：按「无名称」处理，绝不伪造名称
            AppLog.w(TAG, "读取请求方应用名称失败，按无名称处理: ${t.javaClass.simpleName}")
            null
        }
        return buildAutofillPickerRequester(
            packageName = callingPackage,
            appLabel = label,
            certSha256Hex = autofillOriginResolver.callingAppCertSha256Hex(callingPackage),
            reportedDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        )
    }

    /**
     * ISSUE-P3-43 ②：把「本表单该角色」写入字段级屏蔽，随后结束本次填充。
     *
     * 结束（而非继续填充）是有意的：用户刚刚表达了「这里别再填」，此时仍完成一次填充
     * 会与其意图直接冲突。写入失败（包名不可识别等）时同样结束，但不谎报成功。
     */
    private fun blockFieldAndFinish(role: AutofillFieldRole) {
        if (completed) return
        completed = true
        val callingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE).orEmpty()
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)?.takeIf { it.isNotBlank() }
        val blocked = autofillFieldBlocklistStore.block(callingPackage, webDomain, role)
        // 日志不携带包名/域等调用方标识（ISSUE-P1-10 语义）
        AppLog.i(TAG, "字段级屏蔽写入结果=$blocked role=${role.wireName}")
        setResult(RESULT_CANCELED)
        finish()
    }

    /** 用户选中条目：按需解密 → 二次确认（可用生物识别时）→ 回传数据集 */
    private fun confirmAndFill(entryId: String) {
        if (completed) return
        lifecycleScope.launch {
            val credentials = viewModel.resolveCredentials(entryId)
            if (credentials == null) {
                AppLog.w(TAG, "选中条目凭据不可用，放弃本次填充")
                finish()
                return@launch
            }
            // ISSUE-P3-52：可用认证器时以 Keystore 认证绑定密钥的 Cipher 发起（CryptoObject），
            // 本次放行与生物识别密码学绑定；无可用认证器 / 密钥不可用 / 结果未携带绑定 Cipher
            // 时退化为受保护窗口内的显式点选确认（既有退化语义，不回归）。
            val authStatus = biometricAuthManager.canAuthenticate(
                this@AutofillPickerActivity,
                BiometricAuthManager.UNLOCK_AUTHENTICATORS
            )
            val authCipher = if (authStatus == BiometricStatus.AVAILABLE) {
                biometricAuthManager.prepareAutofillAuthCipher()
            } else {
                null
            }
            if (authStatus == BiometricStatus.AVAILABLE && authCipher != null) {
                biometricAuthManager.authenticate(
                    activity = this@AutofillPickerActivity,
                    title = getString(R.string.autofill_confirm_title),
                    subtitle = getString(R.string.autofill_picker_confirm_sub),
                    authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
                    cipher = authCipher
                ) { result ->
                    if (AutofillAuthBindingPolicy.isBound(result)) deliver(credentials) else finish()
                }
            } else {
                // 无可用认证器 / 认证绑定不可用：用户在受保护窗口内的点选本身即一次显式确认
                // （与 AutofillConfirmActivity 的退化策略一致）
                deliver(credentials)
            }
        }
    }

    private fun deliver(credentials: AutofillPickerViewModel.Credentials) {
        completed = true
        // ISSUE-P2-46：用户已在受保护窗口内**显式指认**「把这条凭据填给该调用方」（该页展示
        // 包名 / 应用名 / 签名摘要，见 ISSUE-P2-70），故此处写入首次绑定——它是 `android://`
        // 维度后续自动命中的唯一前提，也是未绑定调用方唯一的补救路径。
        bindCallerForPackageDimension()
        val usernameId = readAutofillId(EXTRA_USERNAME_ID)
        val passwordId = readAutofillId(EXTRA_PASSWORD_ID)

        val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(
                R.id.tv_username,
                credentials.username.ifBlank { getString(R.string.autofill_picker_title) }
            )
            setTextViewText(R.id.tv_subtitle, getString(R.string.autofill_picker_title))
        }

        val dataset = Dataset.Builder(
            Presentations.Builder()
                .setMenuPresentation(views)
                .setDialogPresentation(views)
                .build()
        ).apply {
            if (usernameId != null && credentials.username.isNotEmpty()) {
                setField(
                    usernameId,
                    Field.Builder().setValue(AutofillValue.forText(credentials.username)).build()
                )
            }
            if (passwordId != null && credentials.password.isNotEmpty()) {
                setField(
                    passwordId,
                    Field.Builder().setValue(AutofillValue.forText(credentials.password)).build()
                )
            }
        }.build()

        setResult(RESULT_OK, intentOfResult(dataset))
        finish()
    }

    private fun intentOfResult(dataset: Dataset) =
        android.content.Intent()
            .putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)

    /**
     * ISSUE-P2-46：写入调用方「首次绑定」（包名 + 主签名摘要），供 `android://` 维度后续放行。
     *
     * **fail-closed**：签名摘要不可读（空集）时**不写入**降级键（`pkg|`）——「只认包名」正是
     * 本项要消灭的形态；包名非法时存储自身拒绝写入（返回 false）。两种情况一律保持「未绑定」，
     * 该调用方的 `android://` 候选继续不命中。
     */
    private fun bindCallerForPackageDimension() {
        val callingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE).orEmpty()
        if (callingPackage.isBlank()) return
        val digests = autofillOriginResolver.callingAppCertDigests(callingPackage)
        if (digests.isEmpty) {
            // 日志不携带包名 / 摘要等调用方标识（ISSUE-P1-10 语义）
            AppLog.w(TAG, "调用方签名摘要不可读，android:// 维度保持未绑定（fail-closed）")
            return
        }
        val written = callerTrustStore.trust(callingPackage, digests.primary)
        AppLog.i(TAG, "android:// 维度首次绑定写入结果=$written")
    }

    private fun readAutofillId(key: String): AutofillId? =
        intent.getParcelableExtra(key, AutofillId::class.java)

    companion object {
        private const val TAG = "AutofillPicker"

        /** 目标用户名框（可为 null：纯密码表单） */
        const val EXTRA_USERNAME_ID = "com.keepasskey.app.autofill.EXTRA_PICKER_USERNAME_ID"

        /** 目标密码框（可为 null） */
        const val EXTRA_PASSWORD_ID = "com.keepasskey.app.autofill.EXTRA_PICKER_PASSWORD_ID"

        /** ISSUE-P3-43：调用应用包名（字段签名输入之一，非敏感标识） */
        const val EXTRA_CALLING_PACKAGE = "com.keepasskey.app.autofill.EXTRA_PICKER_PACKAGE"

        /** ISSUE-P3-43：表单自报域（字段签名输入之一；空串表示纯 App 表单） */
        const val EXTRA_WEB_DOMAIN = "com.keepasskey.app.autofill.EXTRA_PICKER_WEB_DOMAIN"
    }
}
