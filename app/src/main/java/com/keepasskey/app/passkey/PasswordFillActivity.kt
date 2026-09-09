package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.core.model.KdbxEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统密码凭据在 Credential Manager 中的填充落地 Activity (对齐 P0-3 要求)。
 *
 * ISSUE-P0-02 (ZT-02) 整改：本 Activity 此前是「零用户验证」的直通管道——候选条目未挂
 * `BiometricPromptData`，本类亦无任何确认，密码库一旦处于解锁态，任意调起 Credential Manager
 * 的应用即可在用户零交互下取得明文密码（设备被短暂占有即等同全库可读）。
 * 现强制「先验证、后下发」：
 * 1. 进入即校验调用包名黑名单（与 Autofill 通道一致的 fail-closed）；
 * 2. 读取目标条目并二次校验「条目 ⇄ 调用方」绑定关系；
 * 3. 由 [CredentialFillVerifier] 判定所需验证等级后拉起系统级 BiometricPrompt，
 *    设备无可用认证器时退化为受保护窗口内（FLAG_SECURE + 反 overlay）的显式手动确认；
 * 4. 仅当验证结果被 [CredentialFillVerifier.isSatisfied] 判为通过时才回传明文密码，
 *    其余路径（未验证 / 失败 / 取消）一律 `RESULT_CANCELED`，绝不返回 `RESULT_OK`。
 *
 * 门控自持于 Activity 内而非挂在 entry 上的理由见 [CredentialFillVerifier] KDoc：
 * `androidx.credentials:1.6.0` 未向提供方暴露 `BiometricPromptResult` 读取入口，
 * 挂在 entry 上无法闭环校验，属「看起来已验证」的假门控。
 */
@AndroidEntryPoint
class PasswordFillActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var fillVerifier: CredentialFillVerifier

    @Inject
    lateinit var autofillBlocklistStore: AutofillBlocklistStore

    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val expectedDomain = intent.getStringExtra(EXTRA_EXPECTED_DOMAIN).orEmpty()
        val expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE).orEmpty()
        if (entryId.isBlank()) {
            Log.e(TAG, "缺少密码凭据 entryId")
            failAndFinish()
            return
        }

        // ISSUE-P0-02：与 Autofill 通道一致的 fail-closed 黑名单复核。
        // 候选组装侧已拦截一次；此处对回传路径再校验，杜绝「组装后被加入黑名单」的窗口被利用。
        if (expectedPackage.isNotBlank() && autofillBlocklistStore.isBlocked(expectedPackage)) {
            Log.w(TAG, "调用应用已列入自动填充黑名单，拒绝回传密码")
            failAndFinish()
            return
        }

        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    Log.w(TAG, "密码库处于锁定状态，无法填充密码")
                    failAndFinish()
                    return@launch
                }

                val allEntries = vaultRepository.getKdbxEntries()
                val entry = allEntries.firstOrNull { it.id.toHexString() == entryId }
                if (entry == null) {
                    Log.e(TAG, "未找到目标密码条目")
                    failAndFinish()
                    return@launch
                }

                // H1 整改：回传明文密码前二次校验条目与预期调用方（域名/包名）的严格绑定关系，
                // 与候选组装逻辑（DomainMatcher）保持一致，杜绝候选与回传之间的窗口被利用
                val domainOk = expectedDomain.isNotBlank() && entry.url.isNotBlank() &&
                        DomainMatcher.isDomainMatch(entry.url, expectedDomain)
                val packageOk = expectedPackage.isNotBlank() && entry.url.isNotBlank() &&
                        DomainMatcher.isPackageMatch(entry.url, expectedPackage)
                if (!domainOk && !packageOk) {
                    Log.e(TAG, "条目与调用方不匹配，拒绝回传密码")
                    failAndFinish()
                    return@launch
                }

                // 先验证、后取密：密码明文在用户验证通过之前绝不物化
                requestUserVerification(entry) { deliverPassword(entry) }
            } catch (t: Throwable) {
                Log.e(TAG, "密码填充失败", t)
                failAndFinish()
            }
        }
    }

    /**
     * 拉起本次下发所要求的用户验证；验证通过后回调 [onVerified]。
     *
     * 验证结果统一交由 [CredentialFillVerifier] 裁决，本方法不对「是否放行」做任何自行判断。
     */
    private fun requestUserVerification(entry: KdbxEntry, onVerified: () -> Unit) {
        val status = biometricAuthManager.canAuthenticate(
            this,
            BiometricAuthManager.UNLOCK_AUTHENTICATORS
        )
        val requirement = fillVerifier.requirementFor(status)
        val credentialLabel = entry.title.ifBlank { entry.userName }.ifBlank { entry.url }

        when (requirement) {
            CredentialFillRequirement.BIOMETRIC -> {
                biometricAuthManager.authenticate(
                    activity = this,
                    title = getString(R.string.cred_fill_confirm_title),
                    subtitle = getString(R.string.cred_fill_confirm_biometric_subtitle, credentialLabel),
                    authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS
                ) { result ->
                    val verification = when (result) {
                        is BiometricResult.Success -> CredentialUserVerification.BiometricSucceeded
                        is BiometricResult.Cancelled -> CredentialUserVerification.BiometricCancelled
                        is BiometricResult.Failed -> CredentialUserVerification.BiometricFailed
                        is BiometricResult.Error -> CredentialUserVerification.BiometricFailed
                    }
                    consumeVerification(requirement, verification, onVerified)
                }
            }

            CredentialFillRequirement.MANUAL_CONFIRMATION -> {
                setContent {
                    CredentialFillConfirmScreen(
                        title = getString(R.string.cred_fill_confirm_title),
                        hint = getString(R.string.cred_fill_confirm_manual_hint, credentialLabel),
                        confirmText = getString(R.string.autofill_confirm_ok),
                        cancelText = getString(R.string.autofill_confirm_cancel),
                        onConfirm = {
                            consumeVerification(
                                requirement,
                                CredentialUserVerification.ManualConfirmed,
                                onVerified
                            )
                        },
                        onCancel = {
                            consumeVerification(
                                requirement,
                                CredentialUserVerification.ManualCancelled,
                                onVerified
                            )
                        }
                    )
                }
            }
        }
    }

    private fun consumeVerification(
        requirement: CredentialFillRequirement,
        verification: CredentialUserVerification,
        onVerified: () -> Unit
    ) {
        if (!fillVerifier.isSatisfied(requirement, verification)) {
            Log.w(TAG, "用户验证未通过，拒绝回传密码: requirement=$requirement result=$verification")
            failAndFinish()
            return
        }
        onVerified()
    }

    /** 验证通过后回传明文密码（唯一允许 `RESULT_OK` 的路径） */
    private fun deliverPassword(entry: KdbxEntry) {
        if (settled) return
        settled = true

        val username = entry.userName
        val password = entry.password?.readString().orEmpty()

        val response = GetCredentialResponse(PasswordCredential(username, password))
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialResponse(resultIntent, response)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        private const val TAG = "PasswordFillActivity"
        const val EXTRA_ENTRY_ID = "com.keepasskey.extra.ENTRY_ID"
        const val EXTRA_EXPECTED_DOMAIN = "com.keepasskey.extra.EXPECTED_DOMAIN"
        const val EXTRA_EXPECTED_PACKAGE = "com.keepasskey.extra.EXPECTED_PACKAGE"
    }
}
