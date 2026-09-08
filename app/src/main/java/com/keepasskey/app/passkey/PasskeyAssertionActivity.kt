package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import javax.inject.Inject

/**
 * 通行密钥断言 (Passkey Assertion / Get) 认证落地 Activity (对齐 P0-3 要求)。
 * 在独立受保护窗口中：
 * 1. 从密码库检索目标 Passkey 条目；
 * 2. 组装 AuthenticatorData 二进制块；
 * 3. 构造 ClientDataJSON 并哈希；
 * 4. 调用 PasskeyCryptoEngine 签名 (私钥敏感内存即用即清)；
 * 5. 组装并返回 PublicKeyCredential 响应，原子递增签名计数器防重放。
 */
@AndroidEntryPoint
class PasskeyAssertionActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val challenge = intent.getStringExtra(EXTRA_CHALLENGE).orEmpty()
        val origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()

        if (entryId.isBlank()) {
            Log.e(TAG, "缺少通行密钥 entryId")
            failAndFinish(getString(R.string.passkey_error_missing_credential_id))
            return
        }

        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    Log.w(TAG, "密码库处于锁定状态，无法执行 Passkey 认证")
                    failAndFinish(getString(R.string.cred_error_vault_locked))
                    return@launch
                }

                val allEntries = vaultRepository.getKdbxEntries()
                val entry = allEntries.firstOrNull { it.id.toHexString() == entryId }
                if (entry == null) {
                    Log.e(TAG, "未找到目标条目: entryId=$entryId")
                    failAndFinish(getString(R.string.cred_error_entry_not_found))
                    return@launch
                }

                val passkeyData = PasskeyData.fromCustomFields(entry.customFields)
                if (passkeyData == null) {
                    Log.e(TAG, "条目不含有效的 Passkey 自定义字段")
                    failAndFinish(getString(R.string.passkey_error_invalid_passkey_entry))
                    return@launch
                }

                // F4 整改：origin 缺失一律拒绝签发（fail-closed），不得以 RP ID 冒充 web origin
                if (origin.isBlank()) {
                    Log.e(TAG, "缺少调用来源 origin，拒绝签发断言")
                    failAndFinish(getString(R.string.passkey_error_origin_missing))
                    return@launch
                }

                // H1 整改：签名前二次校验 origin 与凭据 RP-ID 的绑定关系。
                // 浏览器委派的 web origin 必须与 RP ID 同域（或为其子域）。
                if (CallingOriginResolver.isBrowserOrigin(origin)) {
                    val originHost = DomainMatcher.extractDomain(origin)
                    if (originHost.isEmpty() ||
                        !DomainMatcher.isDomainMatch(passkeyData.relyingPartyId, originHost)
                    ) {
                        Log.e(TAG, "origin 与凭据 RP-ID 不匹配，拒绝签发断言")
                        failAndFinish(getString(R.string.passkey_error_caller_mismatch))
                        return@launch
                    }
                } else {
                    // F4 整改：非浏览器（apk-key-hash）路径补充二次校验，不再单纯依赖候选组装阶段过滤——
                    // 调用包名必须与条目绑定的 android://<包名> 精确一致（预期包名由候选组装方
                    // 经不可伪造的 PendingIntent extras 传入，同 PasswordFillActivity 模式）；
                    // 非应用绑定（https://<rpId>）的条目一律拒绝普通应用签发
                    val expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE).orEmpty()
                    val boundPackage = DomainMatcher.extractAndroidBoundPackage(entry.url)
                    if (expectedPackage.isBlank() || boundPackage != expectedPackage.trim().lowercase()) {
                        Log.e(TAG, "调用包名与凭据绑定包名不一致，拒绝签发断言")
                        failAndFinish(getString(R.string.passkey_error_caller_mismatch))
                        return@launch
                    }
                }

                // 1. 构造 AuthenticatorData (flags: UP | UV | BE | BS, 无 AT)
                val flags = (PasskeyCryptoEngine.FLAG_UP.toInt() or
                        PasskeyCryptoEngine.FLAG_UV.toInt() or
                        PasskeyCryptoEngine.FLAG_BE.toInt() or
                        PasskeyCryptoEngine.FLAG_BS.toInt()).toByte()
                val nextSignCount = passkeyData.signCount + 1

                val authData = PasskeyCryptoEngine.buildAuthenticatorData(
                    rpId = passkeyData.relyingPartyId,
                    flags = flags,
                    signCount = nextSignCount
                )

                // 2. 构造 ClientDataJSON 与 SHA-256 哈希
                val clientDataJson = JSONObject().apply {
                    put("type", "webauthn.get")
                    put("challenge", challenge)
                    put("origin", origin)
                    put("androidPackageName", callingPackage ?: packageName)
                }.toString()
                val clientDataBytes = clientDataJson.toByteArray(Charsets.UTF_8)
                val sha256 = MessageDigest.getInstance("SHA-256")
                val clientDataHash = sha256.digest(clientDataBytes)

                // 3. 构造待签名数据包 (authData || clientDataHash)
                val dataToSign = ByteArray(authData.size + clientDataHash.size)
                System.arraycopy(authData, 0, dataToSign, 0, authData.size)
                System.arraycopy(clientDataHash, 0, dataToSign, authData.size, clientDataHash.size)

                // 4. 读取私钥并执行签名，全流程保护敏感内存
                // P0-7 整改：直接从 ProtectedString 取字节数组并解码，严禁生成不可变私钥 String
                val rawBytes = passkeyData.privateKey.readUtf8()
                val privBytes = try {
                    val trimmed = String(rawBytes).trim() // 获取编码形式判断
                    val isHex = trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                    if (isHex && trimmed.length <= 64) {
                        val bigInt = BigInteger(trimmed, 16)
                        val raw = bigInt.toByteArray()
                        if (raw.size > 32 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
                    } else {
                        Base64.getDecoder().decode(trimmed)
                    }
                } finally {
                    Arrays.fill(rawBytes, 0.toByte())
                }

                val signature = try {
                    PasskeyCryptoEngine.signAssertion(passkeyData.algorithmId, privBytes, dataToSign)
                } finally {
                    Arrays.fill(privBytes, 0.toByte())
                }

                // 5. 构造最终 WebAuthn 断言响应 JSON
                val b64Url = Base64.getUrlEncoder().withoutPadding()
                val assertionJson = JSONObject().apply {
                    put("id", passkeyData.credentialId)
                    put("rawId", passkeyData.credentialId)
                    put("type", "public-key")
                    put("authenticatorAttachment", "platform")
                    put("clientExtensionResults", JSONObject())
                    put("response", JSONObject().apply {
                        put("clientDataJSON", b64Url.encodeToString(clientDataBytes))
                        put("authenticatorData", b64Url.encodeToString(authData))
                        put("signature", b64Url.encodeToString(signature))
                        put("userHandle", passkeyData.userHandle)
                    })
                }

                val resultIntent = Intent()
                val response = GetCredentialResponse(PublicKeyCredential(assertionJson.toString()))
                PendingIntentHandler.setGetCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)

                // 递增签名计数器并落盘
                vaultRepository.patchPasskeySignCount(entryId, nextSignCount)

                finish()
            } catch (t: Throwable) {
                Log.e(TAG, "Passkey 认证执行失败", t)
                failAndFinish(t.message)
            }
        }
    }

    companion object {
        private const val TAG = "PasskeyAssertionActivity"
        const val EXTRA_ENTRY_ID = "com.keepasskey.extra.ENTRY_ID"
        const val EXTRA_REQUEST_JSON = "com.keepasskey.extra.REQUEST_JSON"
        const val EXTRA_CHALLENGE = "com.keepasskey.extra.CHALLENGE"
        const val EXTRA_ORIGIN = "com.keepasskey.extra.ORIGIN"
        const val EXTRA_EXPECTED_PACKAGE = "com.keepasskey.extra.EXPECTED_PACKAGE"
    }
}
