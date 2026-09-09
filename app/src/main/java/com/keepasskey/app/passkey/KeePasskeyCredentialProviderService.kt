package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialCustomException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.Action
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.model.PasskeyData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject

/**
 * Android 16+ 原生系统级凭据提供者服务 (CredentialProviderService)。
 * 深度集成 Jetpack androidx.credentials.provider 框架，对接 Android 系统级 Credential Manager：
 * 1. onBeginGetCredentialRequest: 接收应用/浏览器的登录请求，处理 Passkey (FIDO2) 与传统密码候选构建；
 *    - 响应超时预算：设置严格的 [TIMEOUT_MS] 5,000ms 预算，超时或取消将返回已完成的部分条目或安全空响应，杜绝阻塞系统身份验证弹窗；
 *    - 库锁定 UX v2（链式解锁）：当密码库处于锁定状态时，返回「解锁 KeePasskey 填充凭据」动作条目 (Action)，
 *      用户点选后由 [CredentialUnlockActivity] 承接解锁，成功后直接回传 BeginGetCredentialResponse，
 *      系统 Credential Manager 随即继续呈现凭据候选——一次解锁直达填充；
 *    - 严格域名隔离：采用严格标签边界判定，杜绝跨域钓鱼；
 * 2. onBeginCreateCredentialRequest: 响应新凭据创建请求，引导至独立的 Passkey 注册或密码保存流程；
 * 3. onClearCredentialStateRequest: 响应凭据状态清理；
 * 4. TASK-44 黑名单：命中黑名单的调用包名在查询前即 fail-closed 返回空响应（不产出解锁
 *    Action 与任何凭据候选），与传统 Autofill 服务共用同一份黑名单；
 * 5. ISSUE-P1-01：全部条目的 PendingIntent 统一使用 [CredentialPendingIntents.ENTRY_FLAGS]
 *    （`FLAG_MUTABLE`）——系统以 fillIn Intent 注入最终请求，误用 `FLAG_IMMUTABLE` 会让注入的
 *    extras 被静默丢弃，链式解锁与密码保存全链路握手失败（详见该常量 KDoc）。
 */
@AndroidEntryPoint
class KeePasskeyCredentialProviderService : CredentialProviderService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var responseAssembler: CredentialResponseAssembler

    // TASK-44：自动填充黑名单（命中即不返回任何凭据候选，fail-closed）
    @Inject
    lateinit var autofillBlocklistStore: com.keepasskey.app.data.repository.AutofillBlocklistStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return

        val job = serviceScope.launch {
            try {
                val response = withTimeoutOrNull(TIMEOUT_MS) {
                    buildBeginGetResponse(request)
                } ?: run {
                    Log.w(TAG, "onBeginGetCredential 超出超时预算 (${TIMEOUT_MS}ms)，返回空响应")
                    BeginGetCredentialResponse.Builder().build()
                }
                callback.onResult(response)
            } catch (c: CancellationException) {
                // 系统侧已取消请求：静默退出，不再回调
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "onBeginGetCredential 处理异常", t)
                callback.onError(
                    GetCredentialCustomException(
                        "com.keepasskey.GET_CREDENTIAL_ERROR",
                        t.message ?: getString(R.string.cred_error_unknown)
                    )
                )
            }
        }
        // 生命周期接线：系统取消请求即级联取消协程（无状态契约，杜绝解绑后空转）
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun buildBeginGetResponse(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val responseBuilder = BeginGetCredentialResponse.Builder()

        // 0. TASK-44 黑名单：命中即 fail-closed 返回空响应（不产出解锁引导，也不产出凭据候选）。
        //    Android 16+ 上 Credential Manager 是主通道，仅屏蔽传统 Autofill 服务等于形同虚设，
        //    故双通道统一消费同一黑名单。浏览器以自身包名发起请求，屏蔽浏览器即屏蔽其承载的
        //    全部站点填充——此为「按应用屏蔽」语义的固有结果（KDoc 与 STATUS §6 已注明）。
        val callingPackage = request.callingAppInfo?.packageName.orEmpty()
        if (autofillBlocklistStore.isBlocked(callingPackage)) {
            Log.i(TAG, "调用应用已列入自动填充黑名单，拒绝返回凭据候选: $callingPackage")
            return responseBuilder.build()
        }

        // 1. 密码库处于锁定状态：输出解锁 Action，链式引导至 CredentialUnlockActivity
        //    （解锁成功后由该 Activity 直接回传凭据候选，系统随即继续呈现，无需用户二次发起）
        if (vaultRepository.isLocked()) {
            val unlockIntent = Intent(this, CredentialUnlockActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE_UNLOCK,
                unlockIntent,
                // ISSUE-P1-01：必须 FLAG_MUTABLE，系统需注入原始 BeginGetCredentialRequest
                CredentialPendingIntents.ENTRY_FLAGS
            )
            val action = AuthenticationAction.Builder(
                getString(R.string.cred_unlock_action_title),
                pendingIntent
            ).build()

            responseBuilder.addAuthenticationAction(action)
            return responseBuilder.build()
        }

        // 2. 密码库已就绪：委派共享组装器检索并输出匹配凭据
        return responseAssembler.buildUnlockedGetResponse(request)
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return

        val job = serviceScope.launch {
            try {
                val response = withTimeoutOrNull(TIMEOUT_MS) {
                    buildBeginCreateResponse(request)
                } ?: run {
                    Log.w(TAG, "onBeginCreateCredential 超出超时预算 (${TIMEOUT_MS}ms)，返回空响应")
                    BeginCreateCredentialResponse.Builder().build()
                }
                callback.onResult(response)
            } catch (c: CancellationException) {
                // 系统侧已取消请求：静默退出，不再回调
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "onBeginCreateCredential 异常", t)
                callback.onError(
                    CreateCredentialCustomException(
                        "com.keepasskey.CREATE_CREDENTIAL_ERROR",
                        t.message ?: getString(R.string.cred_error_unknown)
                    )
                )
            }
        }
        // 生命周期接线：系统取消请求即级联取消协程（无状态契约，杜绝解绑后空转）
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun buildBeginCreateResponse(request: BeginCreateCredentialRequest): BeginCreateCredentialResponse {
        val responseBuilder = BeginCreateCredentialResponse.Builder()
        val callingAppInfo = request.callingAppInfo
        val callingPackage = callingAppInfo?.packageName.orEmpty()
        val callingOrigin = extractOrigin(callingAppInfo)

        when (request) {
            is BeginCreatePublicKeyCredentialRequest -> {
                var rpId = ""
                var userName = ""
                var userDisplayName = ""
                var challenge = ""

                try {
                    val json = JSONObject(request.requestJson)
                    val rpObj = json.optJSONObject("rp")
                    rpId = rpObj?.optString("id").orEmpty()
                    val userObj = json.optJSONObject("user")
                    userName = userObj?.optString("name").orEmpty()
                    userDisplayName = userObj?.optString("displayName").orEmpty()
                    challenge = json.optString("challenge")
                } catch (e: Exception) {
                    Log.w(TAG, "解析 BeginCreatePublicKeyCredentialRequest JSON 失败", e)
                }

                if (rpId.isBlank()) {
                    rpId = DomainMatcher.extractDomain(callingOrigin)
                }

                // Wave 12 授权收紧（对齐官方「rp.id 须为 origin 可注册后缀」）：
                // 创建分支与断言分支同等 fail-closed——rp.id 不可信时拒绝呈现创建入口
                if (!DomainMatcher.isRpIdTrustedForCreation(rpId, callingOrigin)) {
                    Log.w(TAG, "拒绝创建请求：rp.id 不可信（非调用方可注册后缀或为公共后缀） rpId=$rpId")
                    return responseBuilder.build()
                }

                val intent = Intent(this, PasskeyCreateActivity::class.java).apply {
                    putExtra(PasskeyCreateActivity.EXTRA_RP_ID, rpId)
                    putExtra(PasskeyCreateActivity.EXTRA_USER_NAME, userName)
                    putExtra(PasskeyCreateActivity.EXTRA_USER_DISPLAY_NAME, userDisplayName)
                    putExtra(PasskeyCreateActivity.EXTRA_CHALLENGE, challenge)
                    putExtra(PasskeyCreateActivity.EXTRA_ORIGIN, callingOrigin)
                }

                val pendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_CREATE_PASSKEY,
                    intent,
                    // ISSUE-P1-01：必须 FLAG_MUTABLE，系统需注入 ProviderCreateCredentialRequest
                    CredentialPendingIntents.ENTRY_FLAGS
                )

                val accountLabel = userName.ifBlank { getString(R.string.cred_create_entry_title) }
                val createEntry = CreateEntry.Builder(accountLabel, pendingIntent)
                    .setDescription(getString(R.string.cred_create_entry_subtitle))
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
                    .build()

                responseBuilder.addCreateEntry(createEntry)
            }

            is BeginCreatePasswordCredentialRequest -> {
                val intent = Intent(this, PasswordSaveActivity::class.java).apply {
                    putExtra(PasswordSaveActivity.EXTRA_PACKAGE_NAME, callingPackage)
                    putExtra(PasswordSaveActivity.EXTRA_WEB_DOMAIN, callingOrigin)
                }

                val pendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_CREATE_PASSWORD,
                    intent,
                    // ISSUE-P1-01：必须 FLAG_MUTABLE，系统需注入 ProviderCreateCredentialRequest
                    CredentialPendingIntents.ENTRY_FLAGS
                )

                val createEntry = CreateEntry.Builder(getString(R.string.cred_create_entry_title), pendingIntent)
                    .setDescription(getString(R.string.cred_create_entry_subtitle))
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
                    .build()

                responseBuilder.addCreateEntry(createEntry)
            }
        }

        return responseBuilder.build()
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        callback.onResult(null)
    }

    override fun onDestroy() {
        // 无状态服务契约（官方）：系统解绑即取消全部在途协程，杜绝解绑后空转与迟到回调
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 解析调用方可信 origin（H1 整改）：
     * 浏览器委派走官方 getOrigin + 特权白名单；普通应用固定颁发 apk-key-hash origin。
     * 绝不信任调用方可控的 candidateQueryData 字符串。
     */
    private fun extractOrigin(callingAppInfo: CallingAppInfo?): String {
        if (callingAppInfo == null) return ""
        return CallingOriginResolver.resolveTrustedOrigin(callingAppInfo)
    }

    /**
     * 根据调用来源 (Origin 或 Package) 严格安全匹配条目 (供既有单元测试与内部查询复用)。
     * L1 整改：包名匹配仅走 DomainMatcher 严格点号边界（含 android:// scheme 剥离），
     * 移除 title.contains 启发式，杜绝宽松包含导致的跨应用凭据泄露。
     */
    internal fun findMatchingEntries(
        entries: List<com.keepasskey.app.ui.model.UiVaultEntry>,
        origin: String,
        packageName: String
    ): List<com.keepasskey.app.ui.model.UiVaultEntry> {
        val cleanOrigin = DomainMatcher.extractDomain(origin)
        return entries.filter { entry ->
            val rpMatch = entry.isPasskey && entry.passkeyRpId != null &&
                    DomainMatcher.isDomainMatch(entry.passkeyRpId, cleanOrigin)
            val urlMatch = cleanOrigin.isNotEmpty() && entry.url.isNotBlank() &&
                    DomainMatcher.isDomainMatch(entry.url, cleanOrigin)
            val packageMatch = packageName.isNotEmpty() && entry.url.isNotBlank() &&
                    DomainMatcher.isPackageMatch(entry.url, packageName)
            rpMatch || urlMatch || packageMatch
        }
    }

    companion object {
        private const val TAG = "KeePasskeyCredProvider"
        private const val TIMEOUT_MS = 5_000L
        private const val REQUEST_CODE_UNLOCK = 100
        private const val REQUEST_CODE_ASSERT = 101
        private const val REQUEST_CODE_FILL = 102
        private const val REQUEST_CODE_CREATE_PASSKEY = 103
        private const val REQUEST_CODE_CREATE_PASSWORD = 104
    }
}
