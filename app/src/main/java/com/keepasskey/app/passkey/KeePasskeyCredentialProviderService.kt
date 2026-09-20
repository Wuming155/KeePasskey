package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
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
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.PasskeyData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * 6. ISSUE-P2-53（审计 F-24）：**运行完整性门控在两条入口统一收口**——get / create 均在最先
 *    裁决 [com.keepasskey.app.security.RuntimeIntegrityGate.awaitEnforcement]，风险态返回空响应；
 *    此前 CM 主通道零命中完整性门控（自动填充 fail-closed 而 CM fail-open，构成策略绕过）。
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

    /**
     * ISSUE-P2-53（审计 F-24）：运行完整性门控——CM 主通道与自动填充通道统一收口。
     *
     * 此前完整性裁决只在自动填充 fail-closed，CM 通道（本服务）**零命中**：
     * 风险态下系统凭据弹窗仍可取得候选，等于绕过策略。现于两条入口（get / create）
     * 各做一次 await 裁决，风险态一律返回**空响应**（不下发任何数据集 / 解锁引导 / 保存入口）。
     */
    @Inject
    lateinit var runtimeIntegrityGate: com.keepasskey.app.security.RuntimeIntegrityGate

    /**
     * 特权浏览器白名单（内置已取证指纹 + 用户显式启用的浏览器）。
     * 缺省白名单只有 Chrome，会让 Firefox / Brave / Edge 等浏览器上**通行密钥完全不可用**
     * （origin 退化为 `apk-key-hash`，与 `https://<rpId>` 绑定条目不匹配）。
     */
    @Inject
    lateinit var privilegedBrowserStore: com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore

    /**
     * ISSUE-P2-228：设置页三条通道开关的持久化来源。
     *
     * 关闭 `credentialProviderEnabled` 后本服务对 `get` / `create` 两条入口一律返回**空响应**。
     * 边界须如实认知：本应用**仍会被系统列出**为凭据提供方（组件注册由 Manifest 决定，
     * 应用内无法动态摘除），只是不再交付任何凭据——设置页文案据此表述，不得写「已从系统移除」。
     */
    @Inject
    lateinit var settingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore

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
                    AppLog.w(TAG, "onBeginGetCredential 超出超时预算 (${TIMEOUT_MS}ms)，返回空响应")
                    BeginGetCredentialResponse.Builder().build()
                }
                callback.onResult(response)
            } catch (c: CancellationException) {
                // 系统侧已取消请求：静默退出，不再回调
                throw c
            } catch (t: Throwable) {
                // ISSUE-P1-10：对外异常一律使用预定义用户文案，禁止透传 t.message
                AppLog.e(TAG, "onBeginGetCredential 处理异常", t)
                callback.onError(
                    GetCredentialCustomException(
                        "com.keepasskey.GET_CREDENTIAL_ERROR",
                        getString(R.string.cred_error_unknown)
                    )
                )
            }
        }
        // 生命周期接线：系统取消请求即级联取消协程（无状态契约，杜绝解绑后空转）
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun buildBeginGetResponse(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val responseBuilder = BeginGetCredentialResponse.Builder()

        // ISSUE-P2-228：凭据管理器通道总开关（最先裁决，代价最低）——关闭即空响应，
        // 不产出候选、解锁引导或保存入口。
        if (!settingsStore.isCredentialProviderEnabled()) {
            AppLog.i(TAG, "凭据管理器通道已在设置中关闭，返回空响应")
            return responseBuilder.build()
        }

        // ISSUE-P2-53：完整性门控收口——风险态一律不下发任何数据集 / 解锁引导。
        // 与自动填充通道（KeePasskeyAutofillService.awaitEnforcement）同一判据，消除通道不对称。
        if (runtimeIntegrityGate.awaitEnforcement().disableAutofill) {
            AppLog.i(TAG, "运行环境完整性风险态，拒绝返回凭据候选")
            return responseBuilder.build()
        }

        // 0. TASK-44 黑名单：命中即 fail-closed 返回空响应（不产出解锁引导，也不产出凭据候选）。
        //    Android 16+ 上 Credential Manager 是主通道，仅屏蔽传统 Autofill 服务等于形同虚设，
        //    故双通道统一消费同一黑名单。浏览器以自身包名发起请求，屏蔽浏览器即屏蔽其承载的
        //    全部站点填充——此为「按应用屏蔽」语义的固有结果（KDoc 与 STATUS §6 已注明）。
        val callingPackage = request.callingAppInfo?.packageName.orEmpty()
        if (autofillBlocklistStore.isBlocked(callingPackage)) {
            // ISSUE-P1-10：日志不得携带调用包名等敏感标识（会暴露用户安装应用清单）
            AppLog.i(TAG, "调用应用已列入自动填充黑名单，拒绝返回凭据候选")
            return responseBuilder.build()
        }

        // 1. 密码库处于锁定状态：输出解锁 Action，链式引导至 CredentialUnlockActivity
        //    （解锁成功后由该 Activity 直接回传凭据候选，系统随即继续呈现，无需用户二次发起）
        if (vaultRepository.isLocked()) {
            val unlockIntent = Intent(this, CredentialUnlockActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                // ISSUE-P2-199：requestCode 进程级单调（原为常量 100，会与后续响应的候选条目记录互相覆写）
                CredentialPendingIntents.nextRequestCode(),
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
        // ISSUE-P1-10：只记录请求类别（类型名），不记录调用包名 / rpId 等敏感标识
        AppLog.i(TAG, "onBeginCreateCredentialRequest 收到系统创建请求: ${request.javaClass.simpleName}")
        if (cancellationSignal.isCanceled) return

        val job = serviceScope.launch {
            try {
                val response = withTimeoutOrNull(TIMEOUT_MS) {
                    buildBeginCreateResponse(request)
                } ?: run {
                    AppLog.w(TAG, "onBeginCreateCredential 超出超时预算 (${TIMEOUT_MS}ms)，返回空响应")
                    BeginCreateCredentialResponse.Builder().build()
                }
                callback.onResult(response)
            } catch (c: CancellationException) {
                // 系统侧已取消请求：静默退出，不再回调
                throw c
            } catch (t: Throwable) {
                // ISSUE-P1-10：对外异常一律使用预定义用户文案，禁止透传 t.message
                AppLog.e(TAG, "onBeginCreateCredential 异常", t)
                callback.onError(
                    CreateCredentialCustomException(
                        "com.keepasskey.CREATE_CREDENTIAL_ERROR",
                        getString(R.string.cred_error_unknown)
                    )
                )
            }
        }
        // 生命周期接线：系统取消请求即级联取消协程（无状态契约，杜绝解绑后空转）
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun buildBeginCreateResponse(request: BeginCreateCredentialRequest): BeginCreateCredentialResponse {
        val responseBuilder = BeginCreateCredentialResponse.Builder()

        // ISSUE-P2-228：通道总开关关闭 ⇒ 不产出任何保存入口（与 get 通道同口径）
        if (!settingsStore.isCredentialProviderEnabled()) {
            AppLog.i(TAG, "凭据管理器通道已在设置中关闭，不产出保存入口")
            return responseBuilder.build()
        }

        // ISSUE-P2-53：完整性门控收口——风险态不下发保存入口（与 get 通道同判据）。
        if (runtimeIntegrityGate.awaitEnforcement().disableAutofill) {
            AppLog.i(TAG, "运行环境完整性风险态，拒绝返回凭据保存入口")
            return responseBuilder.build()
        }

        // ISSUE-P1-10：不记录调用包名 / origin（会暴露用户安装应用清单与注册站点）
        val callingAppInfo = request.callingAppInfo
        val callingOrigin = extractOrigin(callingAppInfo)
        // ISSUE-P3-188：入口装配下沉同包协作对象，本函数只保留「门控 → 分派 → 收口」编排；
        // 分派不命中任何已知请求类型时不追加条目（与拆分前的空 `when` 完全一致）
        val createEntry = when (request) {
            // ISSUE-P2-228：「通行密钥支持」关闭时公钥类创建请求不产出条目（与 `else -> null`
            // 同一收敛语义：系统侧等价于「本提供方不处理该请求」）；密码类创建不受影响。
            is BeginCreatePublicKeyCredentialRequest ->
                if (settingsStore.isPasskeySupportEnabled()) {
                    CredentialCreateEntries.passkeyEntry(this, request, callingOrigin)
                } else {
                    null
                }

            is BeginCreatePasswordCredentialRequest ->
                CredentialCreateEntries.passwordEntry(this, callingAppInfo?.packageName.orEmpty(), callingOrigin)

            else -> null
        }
        createEntry?.let { responseBuilder.addCreateEntry(it) }
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
        return CallingOriginResolver.resolveTrustedOrigin(
            callingAppInfo,
            privilegedBrowserStore.allowlistJson()
        )
    }

    /**
     * 根据调用来源 (Origin 或 Package) 严格安全匹配条目 (供既有单元测试与内部查询复用)。
     * L1 整改：包名匹配仅走 DomainMatcher 严格点号边界（含 android:// scheme 剥离），
     * 移除 title.contains 启发式，杜绝宽松包含导致的跨应用凭据泄露。
     * P2-40 整改：包名维度改用 `android://` 硬约束（[DomainMatcher.isAndroidPackageMatch]）——
     * `https://<host>` 等 Web 绑定条目不得再被同形包名命中，Web 绑定只经域匹配路径放行。
     * ISSUE-P2-83：包名维度追加**调用方签名绑定门控**（[CredentialManagerPackageBindingGate]）——
     * 严格 `android://` 匹配只解决「scheme 形态」，不解决「同 `applicationId` 侧载顶替」。
     */
    internal fun findMatchingEntries(
        entries: List<com.keepasskey.app.ui.model.UiVaultEntry>,
        origin: String,
        packageName: String,
        /**
         * `android://` 包名维度是否已通过签名绑定门控。
         *
         * **刻意不设默认值**：包名维度是越权面，给默认值等于留一个「忘记传参 = 放行」的
         * fail-open 口子；调用方必须显式给出判定结果。
         */
        packageDimensionAuthorized: Boolean
    ): List<com.keepasskey.app.ui.model.UiVaultEntry> {
        val cleanOrigin = DomainMatcher.extractDomain(origin)
        return entries.filter { entry ->
            val rpMatch = entry.isPasskey && entry.passkeyRpId != null &&
                    DomainMatcher.isDomainMatch(entry.passkeyRpId, cleanOrigin)
            val urlMatch = cleanOrigin.isNotEmpty() && entry.url.isNotBlank() &&
                    DomainMatcher.isDomainMatch(entry.url, cleanOrigin)
            val packageMatch = packageDimensionAuthorized && packageName.isNotEmpty() && entry.url.isNotBlank() &&
                    DomainMatcher.isAndroidPackageMatch(entry.url, packageName)
            rpMatch || urlMatch || packageMatch
        }
    }

    companion object {
        private const val TAG = "KeePasskeyCredProvider"
        private const val TIMEOUT_MS = 5_000L
    }
}
