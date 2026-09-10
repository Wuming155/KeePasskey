package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.log.AppLog
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
        // M4 整改（加解密审查 2026-09）：密码唯一来源为系统 Credential Manager 的
        // CreatePasswordRequest（平台 API 边界，String 不可避免，OS Parcel 副本不受本应用控制）。
        // 原意图回退分支 EXTRA_PASSWORD 为死代码且是可被外部 Intent 注入伪造的明文密码通道
        // （String 经 Parcel 落堆不可擦除），已整体移除——与 H1 同源整改同一原则：
        // 敏感数据绝不从调用方可控的 Intent extra 读取。
        val password: String? = when (callingReq) {
            is androidx.credentials.CreatePasswordRequest -> callingReq.password
            else -> null
        }
        val targetPackage = providerReq?.callingAppInfo?.packageName ?: intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        // H1 同源整改（审计观察 1）：web 域只信任本应用 Provider 服务填充的 EXTRA_WEB_DOMAIN，
        // 绝不回退读取调用方可控的 candidateQueryData（死代码清理，防止未来重构使其可达）
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)

        if (password.isNullOrBlank()) {
            AppLog.e(TAG, "缺少待保存的密码数据")
            failAndFinish()
            return
        }

        // M4 整改：平台 String 到 CharArray 的唯一副本即取即用；擦除必须发生在协程内部
        // （lifecycleScope.launch 为异步，外层 finally 会先于保存完成执行）——任何结果路径
        // （成功/失败/异常）均在 finally 中显式清零，不放大 String 的不可擦除驻留面
        val passwordChars = password.toCharArray()
        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    AppLog.w(TAG, "密码库处于锁定状态，无法保存密码凭据")
                    failAndFinish()
                    return@launch
                }

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
                AppLog.e(TAG, "保存密码凭据失败", t)
                failAndFinish()
            } finally {
                passwordChars.fill('0')
            }
        }
    }

    companion object {
        private const val TAG = "PasswordSaveActivity"
        const val EXTRA_PACKAGE_NAME = "com.keepasskey.extra.PACKAGE_NAME"
        const val EXTRA_WEB_DOMAIN = "com.keepasskey.extra.WEB_DOMAIN"
        const val EXTRA_USERNAME = "com.keepasskey.extra.USERNAME"
    }
}
