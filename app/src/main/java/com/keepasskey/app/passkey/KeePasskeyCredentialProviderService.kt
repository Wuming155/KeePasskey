package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialCustomException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.Action
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.BiometricPromptData
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricStatus
import com.keepasskey.core.model.PasskeyData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject

/**
 * Android 16+ 原生系统级凭据提供者服务 (CredentialProviderService)。
 * 深度集成 Jetpack androidx.credentials.provider 框架，对接 Android 系统级 Credential Manager：
 * 1. onBeginGetCredentialRequest: 接收应用/浏览器的登录请求，处理 Passkey (FIDO2) 与传统密码候选构建；
 *    - 响应超时预算：设置严格的 [TIMEOUT_MS] 5,000ms 预算，超时或取消将返回已完成的部分条目或安全空响应，杜绝阻塞系统身份验证弹窗；
 *    - 库锁定 UX v1：当密码库处于锁定状态时，主动返回「解锁 KeePasskey 填充凭据」动作条目 (Action) 引导用户解锁；
 *    - 严格域名隔离：采用严格标签边界判定，杜绝跨域钓鱼；
 * 2. onBeginCreateCredentialRequest: 响应新凭据创建请求，引导至独立的 Passkey 注册或密码保存流程；
 * 3. onClearCredentialStateRequest: 响应凭据状态清理。
 */
@AndroidEntryPoint
class KeePasskeyCredentialProviderService : CredentialProviderService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return

        serviceScope.launch {
            try {
                val response = withTimeoutOrNull(TIMEOUT_MS) {
                    buildBeginGetResponse(request)
                } ?: run {
                    Log.w(TAG, "onBeginGetCredential 超出超时预算 (${TIMEOUT_MS}ms)，返回空响应")
                    BeginGetCredentialResponse.Builder().build()
                }
                callback.onResult(response)
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
    }

    private suspend fun buildBeginGetResponse(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val callingAppInfo = request.callingAppInfo
        val callingPackage = callingAppInfo?.packageName.orEmpty()
        val callingOrigin = extractOrigin(callingAppInfo, null)
        val responseBuilder = BeginGetCredentialResponse.Builder()

        // 1. 密码库处于锁定状态：输出解锁 Action
        if (vaultRepository.isLocked()) {
            val unlockIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE_UNLOCK,
                unlockIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val action = Action.Builder(
                getString(R.string.cred_unlock_action_title),
                pendingIntent
            ).setSubtitle(getString(R.string.cred_unlock_action_subtitle)).build()

            responseBuilder.addAction(action)
            return responseBuilder.build()
        }

        // 2. 密码库已就绪：检索并输出匹配凭据
        val allEntries = vaultRepository.getKdbxEntries()
        val isBiometricAvailable = biometricAuthManager.canAuthenticate(this) == BiometricStatus.AVAILABLE

        for (option in request.beginGetCredentialOptions) {
            when (option) {
                is BeginGetPublicKeyCredentialOption -> {
                    val rpIdFromOption = try {
                        val json = JSONObject(option.requestJson)
                        json.optJSONObject("rp")?.optString("id").orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    val targetRpId = rpIdFromOption.ifBlank { DomainMatcher.extractDomain(callingOrigin) }
                    if (targetRpId.isBlank()) continue

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

                        val intent = Intent(this, PasskeyAssertionActivity::class.java).apply {
                            putExtra(PasskeyAssertionActivity.EXTRA_ENTRY_ID, entry.id.toHexString())
                            putExtra(PasskeyAssertionActivity.EXTRA_REQUEST_JSON, option.requestJson)
                            putExtra(PasskeyAssertionActivity.EXTRA_CHALLENGE, challenge)
                            putExtra(PasskeyAssertionActivity.EXTRA_ORIGIN, callingOrigin.ifBlank { "https://$targetRpId" })
                        }
                        val pendingIntent = PendingIntent.getActivity(
                            this,
                            REQUEST_CODE_ASSERT + entry.id.hashCode(),
                            intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )

                        val entryBuilder = PublicKeyCredentialEntry.Builder(
                            this,
                            passkey.userName.ifBlank { entry.title },
                            pendingIntent,
                            option
                        ).setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))

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

                is BeginGetPasswordOption -> {
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
                        val intent = Intent(this, PasswordFillActivity::class.java).apply {
                            putExtra(PasswordFillActivity.EXTRA_ENTRY_ID, entry.id.toHexString())
                        }
                        val pendingIntent = PendingIntent.getActivity(
                            this,
                            REQUEST_CODE_FILL + entry.id.hashCode(),
                            intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )

                        val entryBuilder = PasswordCredentialEntry.Builder(
                            this,
                            entry.userName.ifBlank { entry.title },
                            pendingIntent,
                            option
                        ).setDisplayName(entry.title)
                            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))

                        responseBuilder.addCredentialEntry(entryBuilder.build())
                    }
                }
            }
        }

        return responseBuilder.build()
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return

        serviceScope.launch {
            try {
                val response = withTimeoutOrNull(TIMEOUT_MS) {
                    buildBeginCreateResponse(request)
                } ?: run {
                    Log.w(TAG, "onBeginCreateCredential 超出超时预算 (${TIMEOUT_MS}ms)，返回空响应")
                    BeginCreateCredentialResponse.Builder().build()
                }
                callback.onResult(response)
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
    }

    private suspend fun buildBeginCreateResponse(request: BeginCreateCredentialRequest): BeginCreateCredentialResponse {
        val responseBuilder = BeginCreateCredentialResponse.Builder()
        val callingAppInfo = request.callingAppInfo
        val callingPackage = callingAppInfo?.packageName.orEmpty()
        val callingOrigin = extractOrigin(callingAppInfo, request.candidateQueryData)

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
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

    private fun extractOrigin(callingAppInfo: CallingAppInfo?, candidateQueryData: Bundle?): String {
        if (callingAppInfo == null) return ""
        val fromBundle = candidateQueryData?.getString(EXTRA_CREDENTIAL_REQUEST_ORIGIN)
        if (!fromBundle.isNullOrBlank()) return fromBundle
        return try {
            val field = CallingAppInfo::class.java.getDeclaredField("origin")
            field.isAccessible = true
            (field.get(callingAppInfo) as? String).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 根据调用来源 (Origin 或 Package) 严格安全匹配条目 (供既有单元测试与内部查询复用)
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
            val packageMatch = packageName.isNotEmpty() && (
                    entry.title.contains(packageName, ignoreCase = true) ||
                            DomainMatcher.isPackageMatch(entry.url, packageName)
                    )
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
        const val EXTRA_CREDENTIAL_REQUEST_ORIGIN = "androidx.credentials.provider.extra.CREDENTIAL_REQUEST_ORIGIN"
    }
}
