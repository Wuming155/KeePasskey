package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
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
                    buildPasskeyEntries(option, callingOrigin, allEntries, isBiometricAvailable, responseBuilder)
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
        allEntries: List<KdbxEntry>,
        isBiometricAvailable: Boolean,
        responseBuilder: BeginGetCredentialResponse.Builder
    ) {
        val rpIdFromOption = try {
            JSONObject(option.requestJson).optJSONObject("rp")?.optString("id").orEmpty()
        } catch (_: Exception) {
            ""
        }
        val targetRpId = rpIdFromOption.ifBlank { DomainMatcher.extractDomain(callingOrigin) }
        if (targetRpId.isBlank()) return

        val matchedPasskeys = allEntries.filter { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields)
            passkey != null && DomainMatcher.isDomainMatch(passkey.relyingPartyId, targetRpId)
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
                putExtra(PasskeyAssertionActivity.EXTRA_ORIGIN, callingOrigin.ifBlank { "https://$targetRpId" })
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
        val targetDomain = DomainMatcher.extractDomain(callingOrigin)
        val matchedPasswords = allEntries.filter { entry ->
            val hasPassword = entry.password != null
            val domainMatch = targetDomain.isNotBlank() && entry.url.isNotBlank() &&
                    DomainMatcher.isDomainMatch(entry.url, targetDomain)
            val packageMatch = callingPackage.isNotBlank() && (
                    entry.title.contains(callingPackage, ignoreCase = true) ||
                            DomainMatcher.isPackageMatch(entry.url, callingPackage)
                    )
            hasPassword && (domainMatch || packageMatch)
        }

        for (entry in matchedPasswords) {
            val intent = Intent(context, PasswordFillActivity::class.java).apply {
                putExtra(PasswordFillActivity.EXTRA_ENTRY_ID, entry.id.toHexString())
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
        return try {
            val field = CallingAppInfo::class.java.getDeclaredField("origin")
            field.isAccessible = true
            (field.get(callingAppInfo) as? String).orEmpty()
        } catch (_: Exception) {
            Log.w(TAG, "提取 callingAppInfo origin 失败")
            ""
        }
    }

    companion object {
        private const val TAG = "CredResponseAssembler"
        private const val REQUEST_CODE_ASSERT = 101
        private const val REQUEST_CODE_FILL = 102
    }
}
