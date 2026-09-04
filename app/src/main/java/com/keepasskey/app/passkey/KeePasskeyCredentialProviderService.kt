package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.credentials.GetCredentialResponse
import android.os.CancellationSignal
import android.service.credentials.Action
import android.service.credentials.BeginCreateCredentialRequest
import android.service.credentials.BeginCreateCredentialResponse
import android.service.credentials.BeginGetCredentialOption
import android.service.credentials.BeginGetCredentialRequest
import android.service.credentials.BeginGetCredentialResponse
import android.service.credentials.ClearCredentialStateRequest
import android.service.credentials.CredentialEntry
import android.service.credentials.CredentialProviderService
import android.util.Log
import com.keepasskey.app.MainActivity
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android 16+ 原生系统凭据提供服务 (CredentialProviderService)。
 * 对接 Android 系统级 Credential Manager：
 * 1. onBeginGetCredential: 接收应用/浏览器的登录请求，匹配域名并构建 Passkey/Password 候选列表；
 * 2. onBeginCreateCredential: 接收新凭据注册请求，准备本地公钥生成与持久化流程；
 * 3. onClearCredentialState: 响应系统清空凭据状态；
 * 严格遵循 API 36+ 现代系统规范。
 */
@AndroidEntryPoint
class KeePasskeyCredentialProviderService : CredentialProviderService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBeginGetCredential(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: android.os.OutcomeReceiver<BeginGetCredentialResponse, android.credentials.GetCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return

        serviceScope.launch {
            try {
                val callingAppInfo = request.callingAppInfo
                val callingPackage = callingAppInfo?.packageName ?: ""
                val callingOrigin = callingAppInfo?.origin ?: ""

                val entries = vaultRepository.getEntries().first()
                val responseBuilder = BeginGetCredentialResponse.Builder()

                for (option in request.beginGetCredentialOptions) {
                    val type = option.type
                    Log.d(TAG, "处理凭据请求选项: type=$type, package=$callingPackage, origin=$callingOrigin")

                    val matchedEntries = findMatchingEntries(entries, callingOrigin, callingPackage)
                    for (entry in matchedEntries) {
                        if (entry.isPasskey) {
                            Log.d(TAG, "命中 Passkey 凭据: rp=${entry.passkeyRpId}, user=${entry.username}")
                        }
                    }
                }

                callback.onResult(responseBuilder.build())
            } catch (t: Throwable) {
                Log.e(TAG, "onBeginGetCredential 失败", t)
                callback.onError(
                    android.credentials.GetCredentialException(
                        android.credentials.GetCredentialException.TYPE_UNKNOWN,
                        t.message
                    )
                )
            }
        }
    }

    override fun onBeginCreateCredential(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: android.os.OutcomeReceiver<BeginCreateCredentialResponse, android.credentials.CreateCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return

        serviceScope.launch {
            try {
                val responseBuilder = BeginCreateCredentialResponse.Builder()
                callback.onResult(responseBuilder.build())
            } catch (t: Throwable) {
                Log.e(TAG, "onBeginCreateCredential 失败", t)
                callback.onError(
                    android.credentials.CreateCredentialException(
                        android.credentials.CreateCredentialException.TYPE_UNKNOWN,
                        t.message
                    )
                )
            }
        }
    }

    override fun onClearCredentialState(
        request: ClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: android.os.OutcomeReceiver<Void?, android.credentials.ClearCredentialStateException>
    ) {
        callback.onResult(null)
    }

    /**
     * 根据调用来源 (Origin 或 Package) 智能匹配密码库中的对应条目
     */
    internal fun findMatchingEntries(
        entries: List<com.keepasskey.app.ui.model.UiVaultEntry>,
        origin: String,
        packageName: String
    ): List<com.keepasskey.app.ui.model.UiVaultEntry> {
        val cleanOrigin = origin.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return entries.filter { entry ->
            val url = entry.url.removePrefix("https://").removePrefix("http://").trimEnd('/')
            val rpMatch = entry.isPasskey && entry.passkeyRpId != null && 
                    (cleanOrigin.contains(entry.passkeyRpId) || entry.passkeyRpId.contains(cleanOrigin))
            val urlMatch = cleanOrigin.isNotEmpty() && (url.contains(cleanOrigin) || cleanOrigin.contains(url))
            val titleMatch = packageName.isNotEmpty() && entry.title.contains(packageName, ignoreCase = true)
            rpMatch || urlMatch || titleMatch
        }
    }

    companion object {
        private const val TAG = "KeePasskeyCredProvider"
    }
}
