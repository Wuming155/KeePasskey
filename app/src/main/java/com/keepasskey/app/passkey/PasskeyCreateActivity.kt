package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.crypto.cbor.CborEncoder
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject

/**
 * 通行密钥创建与注册落地 Activity (对齐 P0-3 要求)。
 * 在受保护的独立生命周期中完成：
 * 1. 硬件级/密码学 ES256 密钥对生成；
 * 2. 写入 KDBX 密码库并原子落盘；
 * 3. 构造标准 WebAuthn W3C 证明对象 (AttestationObject) 与确定性 CBOR 编码；
 * 4. 通过 PendingIntentHandler 回传 CreatePublicKeyCredentialResponse。
 */
@AndroidEntryPoint
class PasskeyCreateActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var rpId = intent.getStringExtra(EXTRA_RP_ID).orEmpty()
        var userName = intent.getStringExtra(EXTRA_USER_NAME).orEmpty()
        var userDisplayName = intent.getStringExtra(EXTRA_USER_DISPLAY_NAME).orEmpty()
        var challenge = intent.getStringExtra(EXTRA_CHALLENGE).orEmpty()
        var origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()

        val providerReq = try {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        val callingReq = providerReq?.callingRequest
        if (callingReq is androidx.credentials.CreatePublicKeyCredentialRequest) {
            try {
                val json = JSONObject(callingReq.requestJson)
                val rpObj = json.optJSONObject("rp")
                if (rpId.isBlank()) rpId = rpObj?.optString("id").orEmpty()
                val userObj = json.optJSONObject("user")
                if (userName.isBlank()) userName = userObj?.optString("name").orEmpty()
                if (userDisplayName.isBlank()) userDisplayName = userObj?.optString("displayName").orEmpty()
                if (challenge.isBlank()) challenge = json.optString("challenge")
            } catch (e: Exception) {
                Log.w(TAG, "解析 callingRequest.requestJson 失败", e)
            }
        }
        if (origin.isBlank()) {
            val candidateOrigin = providerReq?.callingRequest?.candidateQueryData?.getString(EXTRA_CREDENTIAL_REQUEST_ORIGIN)
            if (!candidateOrigin.isNullOrBlank()) {
                origin = candidateOrigin
            }
        }

        if (rpId.isBlank() || userName.isBlank()) {
            Log.e(TAG, "缺少必要注册参数: rpId=$rpId, userName=$userName")
            failAndFinish("缺少通行密钥注册核心参数")
            return
        }

        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    Log.w(TAG, "密码库处于锁定状态，无法注册新 Passkey")
                    failAndFinish("密码库已锁定")
                    return@launch
                }

                // 1. 生成 ES256 密钥对
                val passkeyData = PasskeyCryptoEngine.generateEs256KeyPair(
                    relyingPartyId = rpId,
                    userName = userName,
                    userHandle = "",
                    userDisplayName = userDisplayName
                )

                // 2. 存储至 KDBX 密码库
                vaultRepository.saveNewPasskeyEntry(passkeyData)

                // 3. 构建证明数据 (Attestation)
                val credIdBytes = Base64.getUrlDecoder().decode(passkeyData.credentialId)
                val pubBytes = Base64.getDecoder().decode(passkeyData.publicKeyBase64)
                val coseKeyBytes = PasskeyCryptoEngine.coseKeyFor(passkeyData.algorithmId, pubBytes)

                val flags = (PasskeyCryptoEngine.FLAG_UP.toInt() or
                        PasskeyCryptoEngine.FLAG_UV.toInt() or
                        PasskeyCryptoEngine.FLAG_BE.toInt() or
                        PasskeyCryptoEngine.FLAG_BS.toInt() or
                        PasskeyCryptoEngine.FLAG_AT.toInt()).toByte()

                val authData = PasskeyCryptoEngine.buildAuthenticatorData(
                    rpId = rpId,
                    flags = flags,
                    signCount = 0,
                    credentialId = credIdBytes,
                    cosePublicKey = coseKeyBytes
                )

                val attestationMap = linkedMapOf<String, Any>(
                    "fmt" to "none",
                    "attStmt" to emptyMap<String, Any>(),
                    "authData" to authData
                )
                val attestationObjectBytes = CborEncoder.encodeMap(attestationMap)

                val clientDataJson = JSONObject().apply {
                    put("type", "webauthn.create")
                    put("challenge", challenge)
                    put("origin", origin.ifBlank { "https://$rpId" })
                    put("androidPackageName", callingPackage ?: packageName)
                }.toString()

                val clientDataBase64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(clientDataJson.toByteArray(Charsets.UTF_8))
                val attestationBase64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(attestationObjectBytes)

                val regResponseJson = JSONObject().apply {
                    put("id", passkeyData.credentialId)
                    put("rawId", passkeyData.credentialId)
                    put("type", "public-key")
                    put("authenticatorAttachment", "platform")
                    put("clientExtensionResults", JSONObject())
                    put("response", JSONObject().apply {
                        put("clientDataJSON", clientDataBase64)
                        put("attestationObject", attestationBase64)
                        put("transports", JSONArray().put("internal"))
                    })
                }

                val resultIntent = Intent()
                val response = CreatePublicKeyCredentialResponse(regResponseJson.toString())
                PendingIntentHandler.setCreateCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (t: Throwable) {
                Log.e(TAG, "Passkey 注册异常", t)
                failAndFinish(t.message)
            }
        }
    }

    companion object {
        private const val TAG = "PasskeyCreateActivity"
        const val EXTRA_RP_ID = "com.keepasskey.extra.RP_ID"
        const val EXTRA_USER_NAME = "com.keepasskey.extra.USER_NAME"
        const val EXTRA_USER_DISPLAY_NAME = "com.keepasskey.extra.USER_DISPLAY_NAME"
        const val EXTRA_CHALLENGE = "com.keepasskey.extra.CHALLENGE"
        const val EXTRA_ORIGIN = "com.keepasskey.extra.ORIGIN"
        const val EXTRA_CREDENTIAL_REQUEST_ORIGIN = "androidx.credentials.provider.extra.CREDENTIAL_REQUEST_ORIGIN"
    }
}
