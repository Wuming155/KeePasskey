package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.biometric.BiometricManager
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.BiometricPromptData
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricStatus
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
 */
class CredentialResponseAssembler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
    private val biometricAuthManager: BiometricAuthManager
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

        val allEntries = vaultRepository.getKdbxEntries()
        val isBiometricAvailable = biometricAuthManager.canAuthenticate(context) == BiometricStatus.AVAILABLE

        for (option in request.beginGetCredentialOptions) {
            when (option) {
                is BeginGetPublicKeyCredentialOption -> {
                    buildPasskeyEntries(option, callingOrigin, callingPackage, allEntries, isBiometricAvailable, responseBuilder)
                }

                is BeginGetPasswordOption -> {
                    buildPasswordEntries(option, callingPackage, callingOrigin, allEntries, responseBuilder)
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
        isBiometricAvailable: Boolean,
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
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_ASSERT + entry.id.hashCode(),
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

            if (isBiometricAvailable) {
                val bioPromptData = BiometricPromptData(
                    null,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                entryBuilder.setBiometricPromptData(bioPromptData)
            }

            responseBuilder.addCredentialEntry(entryBuilder.build())
        }
    }

    private fun buildPasswordEntries(
        option: BeginGetPasswordOption,
        callingPackage: String,
        callingOrigin: String,
        allEntries: List<KdbxEntry>,
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
                REQUEST_CODE_FILL + entry.id.hashCode(),
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
        private const val REQUEST_CODE_ASSERT = 101
        private const val REQUEST_CODE_FILL = 102
    }
}
