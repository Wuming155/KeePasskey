package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统密码凭据在 Credential Manager 中的保存落地 Activity (对齐 P0-3 要求)。
 */
@AndroidEntryPoint
class PasswordSaveActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val providerReq = try {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        val callingReq = providerReq?.callingRequest
        val username = when {
            callingReq is androidx.credentials.CreatePasswordRequest -> callingReq.id
            else -> intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        }
        val password = when {
            callingReq is androidx.credentials.CreatePasswordRequest -> callingReq.password
            else -> intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        }
        val targetPackage = providerReq?.callingAppInfo?.packageName ?: intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        // H1 同源整改（审计观察 1）：web 域只信任本应用 Provider 服务填充的 EXTRA_WEB_DOMAIN，
        // 绝不回退读取调用方可控的 candidateQueryData（死代码清理，防止未来重构使其可达）
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)

        if (password.isBlank()) {
            Log.e(TAG, "缺少待保存的密码数据")
            failAndFinish("密码数据为空")
            return
        }

        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    Log.w(TAG, "密码库处于锁定状态，无法保存密码凭据")
                    failAndFinish("密码库已锁定")
                    return@launch
                }

                val passwordChars = password.toCharArray()
                vaultRepository.saveAutofillCredential(
                    packageName = targetPackage.ifBlank { callingPackage ?: packageName },
                    webDomain = webDomain,
                    username = username,
                    passwordChars = passwordChars
                )

                val response = CreatePasswordResponse()
                val resultIntent = Intent()
                PendingIntentHandler.setCreateCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (t: Throwable) {
                Log.e(TAG, "保存密码凭据失败", t)
                failAndFinish(t.message)
            }
        }
    }

    companion object {
        private const val TAG = "PasswordSaveActivity"
        const val EXTRA_PACKAGE_NAME = "com.keepasskey.extra.PACKAGE_NAME"
        const val EXTRA_WEB_DOMAIN = "com.keepasskey.extra.WEB_DOMAIN"
        const val EXTRA_USERNAME = "com.keepasskey.extra.USERNAME"
        const val EXTRA_PASSWORD = "com.keepasskey.extra.PASSWORD"
    }
}
