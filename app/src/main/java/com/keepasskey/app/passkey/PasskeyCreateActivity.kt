package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.crypto.cbor.CborEncoder
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject

/**
 * 通行密钥创建与注册落地 Activity (对齐 P0-3 要求)。
 * 在受保护的独立生命周期中完成：
 * 1. 确定调用方来源并校验（H1 / L1）；
 * 2. **ISSUE-P0-03 (ZT-03)**：生成密钥并落库前，先执行「本次实际发生」的用户验证门控
 *    （系统级生物识别 / 锁屏凭据，或设备无可用认证器时的受保护窗口手动确认），
 *    结果交由 [PasskeyAuthFlags] 决定 AuthenticatorData 的 UV 位——强验证通过 → `UV=1`，
 *    手动确认仅证实在场 → 如实 `UV=0`；未验证 / 失败 / 取消一律拒绝创建；
 * 3. 硬件级/密码学 ES256 密钥对生成；
 * 4. 写入 KDBX 密码库并原子落盘；
 * 5. 构造标准 WebAuthn W3C 证明对象 (AttestationObject) 与确定性 CBOR 编码；
 * 6. 通过 PendingIntentHandler 回传 CreatePublicKeyCredentialResponse。
 */
@AndroidEntryPoint
class PasskeyCreateActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var fillVerifier: CredentialFillVerifier

    /** 防止验证回调 / 取消回调 / 重复 finish 交错产生重复创建或重复收尾 */
    private var settled = false

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
        // H1 整改：不再从 candidateQueryData 读取调用方可控 origin（不可信）。
        // origin 缺省留空，clientDataJSON 回退为 https://<rpId> 标准值。
        // （P3-28 整改：移除仅含注释的空 if 块）

        if (rpId.isBlank() || userName.isBlank()) {
            Log.e(TAG, "缺少必要注册参数: rpId=$rpId, userName=$userName")
            failAndFinish(getString(R.string.passkey_error_missing_register_params))
            return
        }

        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    Log.w(TAG, "密码库处于锁定状态，无法注册新 Passkey")
                    failAndFinish(getString(R.string.cred_error_vault_locked))
                    return@launch
                }

                // 普通应用（apk-key-hash origin）创建的凭据额外记录调用包绑定（android://<包名>），
                // 供后续 GET 流程按严格包名边界匹配（H1/L1/P1-4 整改）
                val callerPackage = providerReq?.callingAppInfo?.packageName
                    ?: callingPackage?.ifBlank { null }
                if (!CallingOriginResolver.isBrowserOrigin(origin) && callerPackage.isNullOrBlank()) {
                    Log.e(TAG, "无法确定调用应用包名，拒绝创建应用内 Passkey")
                    failAndFinish(getString(R.string.passkey_error_caller_unknown))
                    return@launch
                }

                // ISSUE-P0-03 (ZT-03)：生成并保存凭据前先执行「本次实际发生」的用户验证门控。
                // 修复前注册路径无条件 UP|UV|BE|BS|AT 全置位——即使设备无强认证器、用户未被
                // 验证也会向 RP 谎报 UV=1。现改由门控结果决定 UV 位（强验证 → UV=1；手动确认
                // → UV=0 且 UI 已明示降级；未验证 / 失败 / 取消一律拒绝创建）。
                val rpLabel = rpId.ifBlank { userName }
                requestCredentialUserVerification(
                    biometricAuthManager = biometricAuthManager,
                    fillVerifier = fillVerifier,
                    title = getString(R.string.cred_passkey_create_title),
                    biometricSubtitle = getString(R.string.passkey_create_biometric_subtitle, rpLabel),
                    manualHint = getString(R.string.passkey_create_manual_hint, rpLabel),
                    confirmText = getString(R.string.passkey_confirm_ok),
                    cancelText = getString(R.string.passkey_confirm_cancel),
                    onVerified = { verification ->
                        if (settled) return@requestCredentialUserVerification
                        settled = true
                        createAndReturn(
                            rpId = rpId,
                            userName = userName,
                            userDisplayName = userDisplayName,
                            challenge = challenge,
                            origin = origin,
                            callerPackage = callerPackage,
                            verification = verification
                        )
                    },
                    onRejected = {
                        if (settled) return@requestCredentialUserVerification
                        settled = true
                        Log.w(TAG, "用户验证未通过，拒绝创建 Passkey")
                        failAndFinish()
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Passkey 注册异常", t)
                failAndFinish(t.message)
            }
        }
    }

    /**
     * 用户验证通过后执行密钥生成、落库与响应回传（唯一允许 `RESULT_OK` 的路径）。
     *
     * [verification] 已被门控裁决为满足要求，此处将其实话实说地投影为 flags：
     * 强验证 → [PasskeyAuthFlags] 置 `UV=1`；仅手动确认 → 如实 `UV=0`。
     */
    private fun createAndReturn(
        rpId: String,
        userName: String,
        userDisplayName: String,
        challenge: String,
        origin: String,
        callerPackage: String?,
        verification: CredentialUserVerification
    ) {
        lifecycleScope.launch {
            try {
                // fail-closed 兜底：凡不可签发的验证结果（理论不可达）一律拒绝创建
                val flags = PasskeyAuthFlags.forRegistration(verification)
                if (flags == null) {
                    Log.e(TAG, "用户验证结果不可用于注册: verification=$verification")
                    failAndFinish()
                    return@launch
                }

                // 1. 生成 ES256 密钥对（CPU 密集调度至 Default）
                val passkeyData = withContext(Dispatchers.Default) {
                    PasskeyCryptoEngine.generateEs256KeyPair(
                        relyingPartyId = rpId,
                        userName = userName,
                        userHandle = "",
                        userDisplayName = userDisplayName
                    )
                }

                // 2. 存储至 KDBX 密码库（原子落盘）
                vaultRepository.saveNewPasskeyEntry(
                    data = passkeyData,
                    boundPackage = if (CallingOriginResolver.isBrowserOrigin(origin)) null
                    else callerPackage
                )

                // 3. 构建证明数据 (Attestation) 并回传（确定性 CBOR 编码在 Default 执行）
                val regResponseJson = withContext(Dispatchers.Default) {
                    buildRegistrationJson(passkeyData, rpId, challenge, origin, callerPackage, flags)
                }

                val resultIntent = Intent()
                val response = CreatePublicKeyCredentialResponse(regResponseJson)
                PendingIntentHandler.setCreateCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (t: Throwable) {
                Log.e(TAG, "Passkey 注册异常", t)
                failAndFinish(t.message)
            }
        }
    }

    /**
     * 组装标准 WebAuthn 注册响应 JSON（authData 中携带 AT 位与 UV 位）。
     * 无证明声明采用 `fmt="none"`；私钥不参与该路径，仅使用公钥构建 COSE 键。
     */
    private fun buildRegistrationJson(
        passkeyData: com.keepasskey.core.model.PasskeyData,
        rpId: String,
        challenge: String,
        origin: String,
        callerPackage: String?,
        flags: Byte
    ): String {
        val credIdBytes = Base64.getUrlDecoder().decode(passkeyData.credentialId)
        val pubBytes = Base64.getDecoder().decode(passkeyData.publicKeyBase64)
        val coseKeyBytes = PasskeyCryptoEngine.coseKeyFor(passkeyData.algorithmId, pubBytes)

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
            put("androidPackageName", callerPackage ?: packageName)
        }.toString()

        val clientDataBase64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(clientDataJson.toByteArray(Charsets.UTF_8))
        val attestationBase64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(attestationObjectBytes)

        return JSONObject().apply {
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
        }.toString()
    }

    companion object {
        private const val TAG = "PasskeyCreateActivity"
        const val EXTRA_RP_ID = "com.keepasskey.extra.RP_ID"
        const val EXTRA_USER_NAME = "com.keepasskey.extra.USER_NAME"
        const val EXTRA_USER_DISPLAY_NAME = "com.keepasskey.extra.USER_DISPLAY_NAME"
        const val EXTRA_CHALLENGE = "com.keepasskey.extra.CHALLENGE"
        const val EXTRA_ORIGIN = "com.keepasskey.extra.ORIGIN"
    }
}
