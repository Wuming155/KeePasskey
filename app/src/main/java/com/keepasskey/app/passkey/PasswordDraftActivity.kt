package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore
import com.keepasskey.app.data.repository.VaultEntryWriteCoordinator
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.edit.EntryEditUiState
import com.keepasskey.app.ui.screens.edit.generatePasswordChars
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxUuid
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

/**
 * 「无匹配 → 就地新建」的口令草稿落地 Activity（`ISSUE-P3-345` / `PD-51`）。
 *
 * ## 两条入口，一种表单
 *
 * - **CM 通道**：候选装配侧在「含 `BeginGetPasswordOption` 且密码候选为 0」时挂一条
 *   [androidx.credentials.provider.Action]（`CredentialResponseAssembler`）；用户点选后系统以
 *   fillIn 方式注入原始 `BeginGetCredentialRequest`，本页经
 *   [PendingIntentHandler.retrieveBeginGetCredentialRequest] 取回；保存成功后
 *   `setGetCredentialResponse` **直接完成本次登录**（官方 `Action` 契约），用户放弃则
 *   `RESULT_CANCELED`（系统契约：重新弹出选择器，不把用户推出流程）。
 * - **传统 Autofill 通道**：由 [com.keepasskey.app.autofill.AutofillPickerActivity] 在空结果态
 *   经 [androidx.activity.result.ActivityResultLauncher] 拉起；保存成功后回传新条目 id
 *   （[EXTRA_DRAFT_ENTRY_ID]），选择器用**既有交付链路**（二次确认 → 数据集 →
 *   `EXTRA_AUTHENTICATION_RESULT`）完成本次填充。
 *
 * ## 安全口径（与 [PasswordSaveActivity] 同族）
 *
 * - `FLAG_SECURE` + 反 overlay 受保护窗口（继承自 [BaseCredentialActivity]）；
 * - 锁库时经 [CredentialUnlockPresenter] **同窗解锁**后继续；
 * - 输入只收**系统背书请求与本应用自身下发**的归属数据（组件 `exported="false"`，
 *   extras 由本应用写入）；CM 路径额外按 `CallingOriginResolver.systemAttestedPackageName`
 *   交叉核对，**不一致即 fail-closed**（P3-111 同模式）；
 * - 落库走 create-only（`PD-51` 裁决 2）：入口前提就是「候选装配判定无匹配」，
 *   不沿用 `saveAutofillCredential` 的静默更新语义；只读会话一律拒绝。
 * - `PD-51` 裁决 1：本页**只服务口令**，绝不由公钥请求拉起。
 */
@AndroidEntryPoint
class PasswordDraftActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var unlockPresenter: CredentialUnlockPresenter

    @Inject
    lateinit var privilegedBrowserStore: PasskeyPrivilegedBrowserStore

    /** ISSUE-P3-298 ⑥：新建后随即完成本次填充 = 真实使用点（与 PasswordFill 同口径） */
    @Inject
    lateinit var credentialLastUsedStore: CredentialLastUsedStore

    /** CM 通道原始请求（null = 选择器拉起路径） */
    private var beginRequest: BeginGetCredentialRequest? = null

    /** 归属绑定（onCreate 解析；解析失败即 fail-closed）——拆字段持有，避免跨包引用内部嵌套类型 */
    private var entryUrl: String = ""
    private var displayDomain: String = ""
    private var isWebBinding: Boolean = false
    private var hasBinding: Boolean = false

    private var callingPackage = ""
    private var settled = false

    private var userName by mutableStateOf("")
    private var isSaving by mutableStateOf(false)
    private var isSaveFailed by mutableStateOf(false)

    /** 密码明文驻留：仅本窗口生命周期，`onDestroy` 显式清零（§3 铁律） */
    private var draftPassword: CharArray = CharArray(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extrasPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty().trim()
        val extrasWebDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN).orEmpty().trim()
        beginRequest = try {
            PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }

        // CM 路径：系统背书调用方与本应用下发 extras 交叉核对（两者均可得且不一致 ⇒ 拒绝；
        // 取不到系统侧时保持既有判定面——组件非 exported，extras 只能来自本应用自身下发）
        val attestedPackage = CallingOriginResolver.systemAttestedPackageName(beginRequest?.callingAppInfo)
        if (attestedPackage != null && extrasPackage.isNotBlank() && attestedPackage != extrasPackage) {
            AppLog.e(TAG, "系统认证调用方与本页归属 extras 不一致，拒绝新建")
            failAndFinish()
            return
        }

        callingPackage = extrasPackage.ifBlank { attestedPackage.orEmpty() }
        val webDomain = extrasWebDomain.ifBlank {
            beginRequest?.callingAppInfo
                ?.let { CallingOriginResolver.resolveTrustedOrigin(it, privilegedBrowserStore.allowlistJson()) }
                .orEmpty()
        }
        if (callingPackage.isBlank() && webDomain.isBlank()) {
            AppLog.e(TAG, "新建入口缺少可用归属（包名与域均空白），拒绝")
            failAndFinish()
            return
        }
        val resolved = VaultEntryWriteCoordinator.resolveCredentialUrlBinding(
            webDomain = webDomain.ifBlank { null },
            packageName = callingPackage
        )
        entryUrl = resolved.entryUrl
        displayDomain = resolved.displayDomain
        isWebBinding = resolved.isWebBinding
        hasBinding = true

        // PD-51 裁决 4：只读会话不提供新建（fail-closed 复核；呈现侧已先行门控）
        if (vaultRepository.isSessionReadOnly()) {
            AppLog.w(TAG, "会话只读，拒绝口令新建")
            failAndFinish()
            return
        }

        // 系统背书请求携带的用户名提示（有则预填；`allowedUserIds` 首个即请求方提示的登录标识）
        userName = beginRequest?.beginGetCredentialOptions
            ?.filterIsInstance<BeginGetPasswordOption>()
            ?.firstOrNull()?.allowedUserIds?.firstOrNull().orEmpty()

        // 锁库：同窗解锁后继续（与 PasswordSaveActivity 同范式）
        unlockPresenter.requireUnlocked(this) { presentDraft() }
    }

    private fun presentDraft() {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            ApplyObscuredTouchFilter()
            PasswordDraftScreen(
                bindingLabel = displayDomain,
                userName = userName,
                onUserNameChange = { userName = it },
                onPasswordChange = { chars ->
                    draftPassword.fill('0')
                    draftPassword = chars
                },
                isSaving = isSaving,
                isSaveFailed = isSaveFailed,
                onSave = ::saveDraft,
                onCancel = ::failAndFinish,
                // ISSUE-P3-373 AC③：生成密码——复用编辑页生成器（generatePasswordChars），
                // 默认选项（20 位 / 大小写数符）。CharArray 所有权整体转移给 draftPassword
                // （与 SecurePasswordField 的 onPasswordChange 同契约），生成副本不再二次清零
                onGeneratePassword = {
                    val generated = generatePasswordChars(EntryEditUiState(), SecureRandom())
                    draftPassword.fill('0')
                    draftPassword = generated
                    isSaveFailed = false
                }
            )
        }
    }

    /** 强制新建（create-only）并按来源通道交付；任何失败路径都不得谎报成功 */
    private fun saveDraft() {
        if (settled || isSaving) return
        if (!hasBinding) {
            failAndFinish()
            return
        }
        lifecycleScope.launch {
            isSaving = true
            // [VaultRepository.saveEntry] 的实现方在保存收尾擦除传入数组，故此处传入**专用副本**
            val submission = draftPassword.copyOf()
            try {
                // 交付前双重复核（锁库可能在窗口存活期被自动锁定触发）
                if (vaultRepository.isLocked() || vaultRepository.isSessionReadOnly()) {
                    AppLog.w(TAG, "交付前复核未通过（锁定或只读），放弃新建")
                    failAndFinish()
                    return@launch
                }
                if (submission.isEmpty()) {
                    isSaving = false
                    isSaveFailed = true
                    return@launch
                }
                // create-only：预生成 uuid 并显式传入 ⇒ 走 `saveEntryInternal` 的新建分支
                val entryId = KdbxUuid.random().toHexString()
                val entry = UiVaultEntry(
                    id = entryId,
                    title = userName.trim().ifBlank { displayDomain },
                    username = userName.trim(),
                    url = entryUrl,
                    notes = if (isWebBinding) "" else "Package: $callingPackage",
                    groupId = null
                )
                when (val result = vaultRepository.saveEntry(entry, passwordChars = submission)) {
                    is com.keepasskey.core.result.KdbxResult.Success -> deliver(entryId)
                    is com.keepasskey.core.result.KdbxResult.Failure -> {
                        AppLog.e(TAG, "口令新建落库失败", result.error)
                        isSaving = false
                        isSaveFailed = true
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "口令新建异常", t)
                isSaving = false
                isSaveFailed = true
            } finally {
                submission.fill('0')
            }
        }
    }

    /** 按来源通道交付：CM 回凭据本身（完成本次登录）；选择器回条目 id（走既有数据集链路） */
    private fun deliver(entryId: String) {
        if (settled) return
        settled = true
        credentialLastUsedStore.record(entryId)
        if (beginRequest != null) {
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialResponse(
                resultIntent,
                GetCredentialResponse(
                    // CM 边界的唯一明文物化点（与 PasswordFillActivity.deliverPassword 同口径）；
                    // PasswordCredential 只收 String，无法以 CharArray 过桥
                    PasswordCredential(userName.trim(), String(draftPassword))
                )
            )
            setResult(RESULT_OK, resultIntent)
        } else {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_DRAFT_ENTRY_ID, entryId))
        }
        finish()
    }

    override fun onDestroy() {
        draftPassword.fill('0')
        draftPassword = CharArray(0)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PasswordDraft"

        /** 预期调用包名（本应用下发；CM 路径与系统认证值交叉核对） */
        const val EXTRA_PACKAGE_NAME = "com.keepasskey.extra.DRAFT_PACKAGE_NAME"

        /** web origin / 域（本应用下发；空白＝纯应用绑定） */
        const val EXTRA_WEB_DOMAIN = "com.keepasskey.extra.DRAFT_WEB_DOMAIN"

        /** 选择器路径的保存结果：新建条目 id（hex） */
        const val EXTRA_DRAFT_ENTRY_ID = "com.keepasskey.extra.DRAFT_ENTRY_ID"

        /** 应用内调用方（选择器等）的统一入口 Intent：归属 extras 集中在此，防多处构造漂移 */
        fun createIntent(context: android.content.Context, callingPackage: String, webDomain: String): Intent =
            Intent(context, PasswordDraftActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, callingPackage)
                putExtra(EXTRA_WEB_DOMAIN, webDomain)
            }

        /** 回程解析：非 RESULT_OK 或缺条目 id 一律返回 null（调用方按取消处理） */
        fun draftEntryIdFrom(result: androidx.activity.result.ActivityResult): String? =
            result.data?.getStringExtra(EXTRA_DRAFT_ENTRY_ID)
                ?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
    }
}
