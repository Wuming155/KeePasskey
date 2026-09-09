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
    private val autofillBlocklistStore: AutofillBlocklistStore
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

        // ISSUE-P0-02：候选出口处的 fail-closed 黑名单复核（命中即不产出任何候选，
        // 语义等价于本应用从未注册过凭据服务）。包名空白时交由下游严格匹配兜底为「无候选」。
        if (autofillBlocklistStore.isBlocked(callingPackage)) {
            return responseBuilder.build()
        }

        val allEntries = vaultRepository.getKdbxEntries()

        // P1 整改：整份响应的 requestCode 由单一分配器供给，跨 Passkey/密码两类候选两两互异
        val requestCodes = RequestCodeAllocator()

        for (option in request.beginGetCredentialOptions) {
            when (option) {
                is BeginGetPublicKeyCredentialOption -> {
                    buildPasskeyEntries(option, callingOrigin, callingPackage, allEntries, requestCodes, responseBuilder)
                }

                is BeginGetPasswordOption -> {
                    buildPasswordEntries(option, callingPackage, callingOrigin, allEntries, requestCodes, responseBuilder)
                }
            }
        }

        return responseBuilder.build()
    }

    private fun buildPasskeyEntries(
        option: BeginGetPublicKeyCredentialOption,
        callingOrigin: String,
        callingPackage: String,
        allEntries: List<KdbxEntry>,
        requestCodes: RequestCodeAllocator,
        responseBuilder: BeginGetCredentialResponse.Builder
    ) {
        val rpIdFromOption = try {
            JSONObject(option.requestJson).optJSONObject("rp")?.optString("id").orEmpty()
        } catch (_: Exception) {
            ""
        }

        // H1 整改：origin 与 RP-ID 强绑定
        // - 浏览器委派（可信 web origin）：requestJson 的 rp.id 必须等于浏览器 origin 域
        //   或为其可注册后缀（WebAuthn 规范），杜绝伪造 rp.id 骗取任意站点凭据；
        // - 普通应用（apk-key-hash origin）：不信任 requestJson 中的 web rp.id，
        //   仅返回调用包名已绑定的凭据（严格包名匹配，无启发式）。
        val browserFlow = CallingOriginResolver.isBrowserOrigin(callingOrigin)
        val targetRpId: String
        if (browserFlow) {
            targetRpId = rpIdFromOption.ifBlank { DomainMatcher.extractDomain(callingOrigin) }
            if (targetRpId.isBlank()) return
            if (!DomainMatcher.isDomainMatch(targetRpId, DomainMatcher.extractDomain(callingOrigin))) return
        } else {
            targetRpId = ""
        }

        val matchedPasskeys = allEntries.filter { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields) ?: return@filter false
            when {
                browserFlow -> DomainMatcher.isDomainMatch(passkey.relyingPartyId, targetRpId)
                else -> callingPackage.isNotBlank() && DomainMatcher.isPackageMatch(entry.url, callingPackage)
            }
        }

        for (entry in matchedPasskeys) {
            val passkey = PasskeyData.fromCustomFields(entry.customFields) ?: continue
            val challenge = try {
                JSONObject(option.requestJson).optString("challenge")
            } catch (_: Exception) {
                ""
            }

            val intent = Intent(context, PasskeyAssertionActivity::class.java).apply {
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
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCodes.next(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
    }

    private fun buildPasswordEntries(
        option: BeginGetPasswordOption,
        callingPackage: String,
        callingOrigin: String,
        allEntries: List<KdbxEntry>,
        requestCodes: RequestCodeAllocator,
        responseBuilder: BeginGetCredentialResponse.Builder
    ) {
        // H1/L1 整改：仅浏览器委派信任 web origin 域匹配；普通应用仅按严格包名边界匹配
        // （条目 url 为 android://<包名> 时同样可匹配），移除 title/notes.contains 启发式。
        val browserFlow = CallingOriginResolver.isBrowserOrigin(callingOrigin)
        val targetDomain = if (browserFlow) DomainMatcher.extractDomain(callingOrigin) else ""
        val matchedPasswords = allEntries.filter { entry ->
            val hasPassword = entry.password != null
            val domainMatch = targetDomain.isNotBlank() && entry.url.isNotBlank() &&
                    DomainMatcher.isDomainMatch(entry.url, targetDomain)
            val packageMatch = callingPackage.isNotBlank() && entry.url.isNotBlank() &&
                    DomainMatcher.isPackageMatch(entry.url, callingPackage)
            hasPassword && (domainMatch || packageMatch)
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
                requestCodes.next(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
        return CallingOriginResolver.resolveTrustedOrigin(callingAppInfo)
    }

    companion object {
        private const val TAG = "CredResponseAssembler"

        /**
         * PendingIntent requestCode 分配器：一次候选组装内全域单调递增，保证同一批候选两两互异。
         *
         * P1 整改：原实现使用 `REQUEST_CODE_ASSERT + entry.id.hashCode()`，存在两重缺陷——
         * 1. 同一批候选内不同条目哈希可能碰撞，叠加 FLAG_UPDATE_CURRENT 后写条目会覆盖先写条目，
         *    表现为「用户点中第 1 条候选，实际拉起第 3 条」；
         * 2. 两个 base 仅相差 1（101/102），跨类型（Passkey 断言 / 密码填充）条目同样会撞同一 requestCode
         *    （如 101 + h(A) == 102 + h(B)）。
         * 现改为单次响应内统一基数分配，彻底消除碰撞面。
         */
        private class RequestCodeAllocator {
            private var next = REQUEST_CODE_BASE
            fun next(): Int = next++
        }

        /** PendingIntent requestCode 分配基线 */
        private const val REQUEST_CODE_BASE = 1000
    }
}
