package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统密码凭据在 Credential Manager 中的保存落地 Activity (对齐 P0-3 要求)。
 *
 * 库锁定时在**同一受保护窗口内**呈现解锁页（[CredentialUnlockPresenter]）——此前直接
 * `failAndFinish()`，用户点选系统「保存密码」后静默失败（与 Passkey 注册同一缺陷形态）。
 */
@AndroidEntryPoint
class PasswordSaveActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var callerTrustStore: CredentialManagerCallerTrustStore

    @Inject
    lateinit var unlockPresenter: CredentialUnlockPresenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val providerReq: ProviderCreateCredentialRequest? = try {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        val callingReq = providerReq?.callingRequest
        val username = when {
            callingReq is CreatePasswordRequest -> callingReq.id
            else -> intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        }
        // M4 整改（加解密审查 2026-09）：密码唯一来源为系统 Credential Manager 的
        // CreatePasswordRequest（平台 API 边界，String 不可避免，OS Parcel 副本不受本应用控制）。
        val password: String? = when (callingReq) {
            is CreatePasswordRequest -> callingReq.password
            else -> null
        }
        val targetPackage = providerReq?.callingAppInfo?.packageName
            ?: intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        // H1 同源整改（审计观察 1）：web 域只信任本应用 Provider 服务填充的 EXTRA_WEB_DOMAIN，
        // 绝不回退读取调用方可控的 candidateQueryData
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)

        if (password.isNullOrBlank()) {
            AppLog.e(TAG, "缺少待保存的密码数据")
            failAndFinish()
            return
        }

        // M4 整改：平台 String 到 CharArray 的唯一副本即取即用；擦除必须发生在协程内部
        // （lifecycleScope.launch 为异步，外层 finally 会先于保存完成执行）
        val passwordChars = password.toCharArray()
        // 锁库时先在同一窗口内解锁，解锁成功后继续保存流程
        unlockPresenter.requireUnlocked(this) {
            startSave(
                providerReq = providerReq,
                username = username,
                passwordChars = passwordChars,
                targetPackage = targetPackage,
                webDomain = webDomain
            )
        }
    }

    /** 解锁后（或库本就解锁）的保存主流程。任何结果路径都会清零 [passwordChars]。 */
    private fun startSave(
        providerReq: ProviderCreateCredentialRequest?,
        username: String,
        passwordChars: CharArray,
        targetPackage: String,
        webDomain: String?
    ) {
        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    AppLog.w(TAG, "密码库仍未解锁，无法保存密码凭据")
                    failAndFinish()
                    return@launch
                }

                val boundPackage = targetPackage.ifBlank { callingPackage ?: packageName }
                val saveResult = vaultRepository.saveAutofillCredential(
                    packageName = boundPackage,
                    webDomain = webDomain,
                    username = username,
                    passwordChars = passwordChars
                )

                // ISSUE-P2-84：保存失败**不得**回传成功结果（原实现丢弃 KdbxResult 后无条件 RESULT_OK，
                // 落盘失败被谎报为保存成功，系统据此可能不再提示保存——用户口令静默丢失）。
                if (saveResult is KdbxResult.Failure) {
                    AppLog.e(TAG, "保存密码凭据失败: ${saveResult.error.javaClass.simpleName}")
                    failAndFinish()
                    return@launch
                }

                // ISSUE-P2-83：保存流程是「用户在受保护窗口内把凭据显式交给该调用方」的两个入口之一
                // （另一个是 Passkey 注册），故在**确认入库成功后**写入 CM 通道的调用方绑定。
                val callerDigests = providerReq?.callingAppInfo
                    ?.let { CallingOriginResolver.certDigests(it) }
                    ?: CallerCertDigests.EMPTY
                if (callerDigests.isEmpty) {
                    AppLog.w(TAG, "调用方签名摘要不可读，CM 通道保持未绑定（fail-closed）")
                } else {
                    callerTrustStore.trust(boundPackage, callerDigests.primary)
                }

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
