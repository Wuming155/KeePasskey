package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject

/**
 * 凭据候选条目组装器。
 *
 * 从 [KeePasskeyCredentialProviderService] 中拆分出的「密码库已解锁」路径凭据构建逻辑，
 * 供服务端查询与 [CredentialUnlockActivity] 链式解锁完成后复用（保证两端候选列表完全一致）。
 * 严格域名隔离（[DomainMatcher]）与超时预算外的轻量约束均与本类无关——调用方负责会话状态判断。
 *
 * ISSUE-P0-02 (ZT-02) / ISSUE-P0-03 (ZT-03) 相关取舍：
 * - **黑名单 fail-closed**：本类是全部候选的唯一出口（直查 + 链式解锁两条路径都经此），
 *   故在此统一复核黑名单，避免新增入口时遗漏（服务侧仍保留一次前置拦截，纵深防御）；
 * - **不挂 `BiometricPromptData` 假门控**：`androidx.credentials:1.6.0` 未向提供方暴露
 *   `BiometricPromptResult` 读取入口，挂在 entry 上无法判定系统门控是否真的通过，只会形成
 *   「看起来已验证」的假门控（且会造成与窗口内验证重复弹窗）。Passkey 候选同样遵循
 *   「不下发即不验证」：真实门控一律在受保护窗口内闭环执行——
 *   [PasswordFillActivity]（密码）、[PasskeyAssertionActivity]（断言）与
 *   [PasskeyCreateActivity]（注册）联合 [CredentialFillVerifier] 强制执行，并由
 *   [PasskeyAuthFlags] 将验证结果如实投影为 WebAuthn UV 位，未验证绝不产出断言 / 注册材料。
 */
class CredentialResponseAssembler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
    private val autofillBlocklistStore: AutofillBlocklistStore,
    /** ISSUE-P2-83：CM 通道 `android://` 包名维度的调用方「包名 + 签名摘要」绑定存储 */
    private val callerTrustStore: CredentialManagerCallerTrustStore,
    /** 特权浏览器白名单（内置已取证 + 用户显式启用的浏览器） */
    private val privilegedBrowserStore: com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore,
    /** ISSUE-P2-228：「通行密钥支持」开关的持久化来源（关闭后本类不产出任何公钥候选） */
    private val settingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore
) {

    /**
     * 组装「密码库已解锁」状态的 BeginGetCredentialResponse：遍历请求选项，
     * 按 Passkey rpId / 密码域名或包名严格匹配输出候选条目。
     */
    suspend fun buildUnlockedGetResponse(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val callingAppInfo = request.callingAppInfo
        val callingPackage = callingAppInfo?.packageName.orEmpty()
        val callingOrigin = extractOrigin(callingAppInfo)
        val responseBuilder = BeginGetCredentialResponse.Builder()

        // ISSUE-P2-83：`android://` 包名维度的**签名绑定门控**（本类是全部候选的唯一出口，
        // 直查与链式解锁两条路径都经此，故门控只需在此一处收口）。
        // 三值判定的依据与 `UNBOUND` 的边界见 [CredentialManagerPackageBindingGate] KDoc。
        val callingCertDigests = callingAppInfo
            ?.let { CallingOriginResolver.certDigests(it) }
            ?: CallerCertDigests.EMPTY
        val packageDimensionAllowed = CredentialManagerPackageBindingGate.allowsPackageDimension(
            callingPackage = callingPackage,
            certDigests = callingCertDigests,
            isTrusted = callerTrustStore::isTrusted,
            hasAnyBinding = callerTrustStore::hasAnyBindingFor
        )
        if (!packageDimensionAllowed) {
            // P1-10：日志不得携带包名 / 摘要等调用方标识，仅记录事实
            AppLog.i(TAG, "android:// 维度未放行（该包名已绑定但本次签名不匹配），本次不提供包名维度候选")
        }

        // ISSUE-P0-02：候选出口处的 fail-closed 黑名单复核（命中即不产出任何候选，
        // 语义等价于本应用从未注册过凭据服务）。包名空白时交由下游严格匹配兜底为「无候选」。
        if (autofillBlocklistStore.isBlocked(callingPackage)) {
            return responseBuilder.build()
        }

        val allEntries = vaultRepository.getKdbxEntries()

        // ISSUE-P2-199：requestCode 一律取自 [CredentialPendingIntents.nextRequestCode]（进程级单调），
        // 不再在本响应内新建分配器——「每响应复位」会与后续响应碰撞同一 PendingIntent 记录。
        for (option in request.beginGetCredentialOptions) {
            when (option) {
                is BeginGetPublicKeyCredentialOption -> {
                    buildPasskeyEntries(
                        option,
                        callingOrigin,
                        callingPackage,
                        packageDimensionAllowed,
                        allEntries,
                        responseBuilder
                    )
                }

                is BeginGetPasswordOption -> {
                    buildPasswordEntries(
                        option,
                        callingPackage,
                        callingOrigin,
                        packageDimensionAllowed,
                        allEntries,
                        responseBuilder
                    )
                }
            }
        }

        return responseBuilder.build()
    }

    private fun buildPasskeyEntries(
        option: BeginGetPublicKeyCredentialOption,
        callingOrigin: String,
        callingPackage: String,
        packageDimensionAllowed: Boolean,
        allEntries: List<KdbxEntry>,
        responseBuilder: BeginGetCredentialResponse.Builder
    ) {
        // ISSUE-P2-228：设置中关闭「通行密钥 (Passkey) 支持」⇒ 本函数不产出任何公钥候选。
        // 密码候选（`buildPasswordEntries`）**不受该开关影响**——两条通道在设置页各自独立成行。
        if (!settingsStore.isPasskeySupportEnabled()) return
        val browserFlow = CallingOriginResolver.isBrowserOrigin(callingOrigin)
        // ISSUE-P3-188：本函数收敛为「RP-ID 归属判定 → 候选收敛 → 逐条呈现」编排，
        // 判定口径与 fail-closed 收口点（null / 空集）与拆分前逐字一致
        val targetRpId = resolvePasskeyTargetRpId(option, callingOrigin, browserFlow) ?: return

        for (entry in passkeyCandidates(
            option = option,
            allEntries = allEntries,
            browserFlow = browserFlow,
            targetRpId = targetRpId,
            callingPackage = callingPackage,
            packageDimensionAllowed = packageDimensionAllowed
        )) {
            val passkey = PasskeyData.fromCustomFields(entry.customFields) ?: continue
            appendPasskeyEntry(
                responseBuilder = responseBuilder,
                option = option,
                entry = entry,
                passkey = passkey,
                browserFlow = browserFlow,
                callingOrigin = callingOrigin,
                callingPackage = callingPackage
            )
        }
    }

    /**
     * H1 整改：origin 与 RP-ID 强绑定。
     * - 浏览器委派（可信 web origin）：requestJson 的 rp.id 必须等于浏览器 origin 域
     *   或为其可注册后缀（WebAuthn 规范），杜绝伪造 rp.id 骗取任意站点凭据；
     * - 普通应用（apk-key-hash origin）：不信任 requestJson 中的 web rp.id，仅返回调用包名
     *   已绑定的凭据（P2-40：条目 url 必须是 `android://<包名>`，严格精确，无启发式），
     *   故此处以空串占位并由 [passkeyCandidates] 的包名维度把关。
     *
     * 返回 null 表示按规范拒绝本次候选呈现（等价于拆分前的提前 `return`）。
     */
    private fun resolvePasskeyTargetRpId(
        option: BeginGetPublicKeyCredentialOption,
        callingOrigin: String,
        browserFlow: Boolean
    ): String? {
        if (!browserFlow) return ""
        val rpIdFromOption = try {
            JSONObject(option.requestJson).optJSONObject(WebAuthnJson.RP)?.optString(WebAuthnJson.ID).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val targetRpId = rpIdFromOption.ifBlank { DomainMatcher.extractDomain(callingOrigin) }
        if (targetRpId.isBlank()) return null
        if (!DomainMatcher.isDomainMatch(targetRpId, DomainMatcher.extractDomain(callingOrigin))) return null
        return targetRpId
    }

    /**
     * 按请求的 `allowCredentials` 收敛候选（空集＝无用户名 / discoverable 流程，不收敛）。
     * 此前对白名单外的凭据同样下发候选，用户选中后 RP 必然拒绝，表现为「点了没反应 / 登录失败」。
     */
    private fun passkeyCandidates(
        option: BeginGetPublicKeyCredentialOption,
        allEntries: List<KdbxEntry>,
        browserFlow: Boolean,
        targetRpId: String,
        callingPackage: String,
        packageDimensionAllowed: Boolean
    ): List<KdbxEntry> {
        val allowedCredentialIds = WebAuthnRequest.parse(option.requestJson)?.allowCredentialIds.orEmpty()
        return allEntries.filter { entry ->
            CredentialCandidateMatcher.matchesPasskey(
                entry = entry,
                browserFlow = browserFlow,
                targetRpId = targetRpId,
                callingPackage = callingPackage,
                packageDimensionAllowed = packageDimensionAllowed,
                allowedCredentialIds = allowedCredentialIds
            )
        }
    }

    /** 追加单条 Passkey 候选条目（点选后由断言 Activity 在受保护窗口内闭环验证并签发） */
    private fun appendPasskeyEntry(
        responseBuilder: BeginGetCredentialResponse.Builder,
        option: BeginGetPublicKeyCredentialOption,
        entry: KdbxEntry,
        passkey: PasskeyData,
        browserFlow: Boolean,
        callingOrigin: String,
        callingPackage: String
    ) {
        val intent = passkeyAssertionIntent(option, entry, browserFlow, callingOrigin, callingPackage)
        val pendingIntent = PendingIntent.getActivity(
            context,
            CredentialPendingIntents.nextRequestCode(),
            intent,
            // ISSUE-P1-01：必须 FLAG_MUTABLE，系统需注入 ProviderGetCredentialRequest
            CredentialPendingIntents.ENTRY_FLAGS
        )

        val entryBuilder = PublicKeyCredentialEntry.Builder(
            context,
            passkey.userName.ifBlank { entry.title },
            pendingIntent,
            option
        ).setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))

        if (passkey.userDisplayName.isNotBlank()) {
            entryBuilder.setDisplayName(passkey.userDisplayName)
        }

        // ISSUE-P0-03 (ZT-03)：不再在候选条目上挂 BiometricPromptData——它只是「看起来已验证」
        // 的假门控，无法判定系统门控是否真的通过，且会与 PasskeyAssertionActivity 窗口内验证
        // 重复弹窗。真实验证门控在受保护窗口内闭环执行：用户点选候选拉起断言 Activity 后，
        // 由其在签名前强制执行生物识别 / 锁屏凭据验证（无可用认证器则手动确认并如实 UV=0）。
        responseBuilder.addCredentialEntry(entryBuilder.build())
    }

    /**
     * 断言落地页的基 Intent：下传条目标识、原始请求 JSON 与**预期归属**
     * （F4 整改：签发前由断言 Activity 二次校验 origin / 包名绑定）。
     */
    private fun passkeyAssertionIntent(
        option: BeginGetPublicKeyCredentialOption,
        entry: KdbxEntry,
        browserFlow: Boolean,
        callingOrigin: String,
        callingPackage: String
    ): Intent {
        val challenge = try {
            JSONObject(option.requestJson).optString(WebAuthnJson.CHALLENGE)
        } catch (_: Exception) {
            ""
        }
        return Intent(context, PasskeyAssertionActivity::class.java).apply {
            putExtra(PasskeyAssertionActivity.EXTRA_ENTRY_ID, entry.id.toHexString())
            putExtra(PasskeyAssertionActivity.EXTRA_REQUEST_JSON, option.requestJson)
            putExtra(PasskeyAssertionActivity.EXTRA_CHALLENGE, challenge)
            putExtra(
                PasskeyAssertionActivity.EXTRA_ORIGIN,
                when {
                    browserFlow -> callingOrigin
                    else -> callingOrigin.ifBlank { "android:apk-key-hash:unknown" }
                }
            )
            // F4 整改：传入预期调用包名，供断言 Activity 签发前二次校验（与密码填充同模式）
            putExtra(PasskeyAssertionActivity.EXTRA_EXPECTED_PACKAGE, callingPackage)
        }
    }

    private fun buildPasswordEntries(
        option: BeginGetPasswordOption,
        callingPackage: String,
        callingOrigin: String,
        packageDimensionAllowed: Boolean,
        allEntries: List<KdbxEntry>,
        responseBuilder: BeginGetCredentialResponse.Builder
    ) {
        // H1/L1 整改：仅浏览器委派信任 web origin 域匹配；普通应用仅按严格包名边界匹配，
        // 且条目 url 必须显式为 `android://<包名>`（P2-40：`https://<host>` 条目不得再被同形包名命中，
        // 否则域名形态包名可冒领 Web 绑定条目），移除 title/notes.contains 启发式。
        val browserFlow = CallingOriginResolver.isBrowserOrigin(callingOrigin)
        val targetDomain = if (browserFlow) DomainMatcher.extractDomain(callingOrigin) else ""
        val matchedPasswords = allEntries.filter { entry ->
            CredentialCandidateMatcher.matchesPassword(
                entry = entry,
                targetDomain = targetDomain,
                callingPackage = callingPackage,
                packageDimensionAllowed = packageDimensionAllowed
            )
        }

        for (entry in matchedPasswords) {
            val intent = Intent(context, PasswordFillActivity::class.java).apply {
                putExtra(PasswordFillActivity.EXTRA_ENTRY_ID, entry.id.toHexString())
                // 传递预期匹配因子，供 Activity 内二次校验，杜绝候选组装与回传之间的窗口被利用
                putExtra(PasswordFillActivity.EXTRA_EXPECTED_DOMAIN, targetDomain)
                putExtra(PasswordFillActivity.EXTRA_EXPECTED_PACKAGE, callingPackage)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                CredentialPendingIntents.nextRequestCode(),
                intent,
                // ISSUE-P1-01：必须 FLAG_MUTABLE，系统需注入 ProviderGetCredentialRequest
                CredentialPendingIntents.ENTRY_FLAGS
            )

            val entryBuilder = PasswordCredentialEntry.Builder(
                context,
                entry.userName.ifBlank { entry.title },
                pendingIntent,
                option
            ).setDisplayName(entry.title)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))

            responseBuilder.addCredentialEntry(entryBuilder.build())
        }
    }

    private fun extractOrigin(callingAppInfo: CallingAppInfo?): String {
        if (callingAppInfo == null) return ""
        // H1 整改：浏览器走官方 getOrigin + 特权白名单，普通应用固定 apk-key-hash origin，
        // 绝不反射私有字段或信任调用方可控字符串
        return CallingOriginResolver.resolveTrustedOrigin(
            callingAppInfo,
            privilegedBrowserStore.allowlistJson()
        )
    }

    companion object {
        private const val TAG = "CredResponseAssembler"
    }
}
