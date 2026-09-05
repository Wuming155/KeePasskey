package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统密码凭据在 Credential Manager 中的填充落地 Activity (对齐 P0-3 要求)。
 */
@AndroidEntryPoint
class PasswordFillActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val expectedDomain = intent.getStringExtra(EXTRA_EXPECTED_DOMAIN).orEmpty()
        val expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE).orEmpty()
        if (entryId.isBlank()) {
            Log.e(TAG, "缺少密码凭据 entryId")
            failAndFinish("缺少凭据条目 ID")
            return
        }

        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    Log.w(TAG, "密码库处于锁定状态，无法填充密码")
                    failAndFinish("密码库已锁定")
                    return@launch
                }

                val allEntries = vaultRepository.getKdbxEntries()
                val entry = allEntries.firstOrNull { it.id.toHexString() == entryId }
                if (entry == null) {
                    Log.e(TAG, "未找到目标密码条目: entryId=$entryId")
                    failAndFinish("未找到匹配的凭据条目")
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
                    failAndFinish("凭据与调用方不匹配")
                    return@launch
                }

                val username = entry.userName
                val password = entry.password?.readString().orEmpty()

                val response = GetCredentialResponse(PasswordCredential(username, password))
                val resultIntent = Intent()
                PendingIntentHandler.setGetCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (t: Throwable) {
                Log.e(TAG, "密码填充失败", t)
                failAndFinish(t.message)
            }
        }
    }

    companion object {
        private const val TAG = "PasswordFillActivity"
        const val EXTRA_ENTRY_ID = "com.keepasskey.extra.ENTRY_ID"
        const val EXTRA_EXPECTED_DOMAIN = "com.keepasskey.extra.EXPECTED_DOMAIN"
        const val EXTRA_EXPECTED_PACKAGE = "com.keepasskey.extra.EXPECTED_PACKAGE"
    }
}
