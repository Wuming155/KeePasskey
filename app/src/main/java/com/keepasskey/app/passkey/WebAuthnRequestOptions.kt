package com.keepasskey.app.passkey

import java.util.Base64

/**
 * WebAuthn 请求（创建 / 断言）的**只读模型**（解析一次，多处消费）。
 *
 * ## 为什么需要
 *
 * 整改前本仓只从请求里读 `rp.id` / `challenge` / `user.name` 三项，其余规范字段
 * （`pubKeyCredParams` / `user.id` / `allowCredentials` / `excludeCredentials` /
 * `userVerification` / `authenticatorSelection` / `extensions.prf`）**一概忽略**，
 * 由此产生四类实际故障：
 * 1. 注册写死 ES256（RP 只接受 Ed25519/RS256 时注册必被拒）；
 * 2. 自造随机 `userHandle`（无用户名登录与 RP 账号关联失效）；
 * 3. 候选不按 `allowCredentials` 收敛（用户可选中 RP 不接受的凭据）；
 * 4. `userVerification: required` 被当作 `preferred`（如实降级为 UV=0，RP 侧必然失败）。
 *
 * ## 解析口径与安全边界
 *
 * - 使用零依赖的 [SimpleJson]（宿主单测可执行；非法输入一律回落到安全缺省）；
 * - 解析结果与**授权判定无关**：授权仍由 [DomainMatcher] / [CallingOriginResolver] 裁决，
 *   本类只回答「RP 想要什么」，绝不把调用方可控字符串当作放行依据；
 * - `user.id` / `credentialId` 等均为 Base64URL 文本，**原样保存与回传**（WebAuthn 要求）。
 */
internal class WebAuthnRequest private constructor(private val root: Map<String, Any?>) {

    /** WebAuthn 规范的用户验证要求等级 */
    enum class UserVerification {
        REQUIRED,
        PREFERRED,
        DISCOURAGED;

        companion object {
            fun from(raw: String?): UserVerification = when (raw?.trim()?.lowercase()) {
                WebAuthnJson.VERIFICATION_REQUIRED -> REQUIRED
                WebAuthnJson.VERIFICATION_DISCOURAGED -> DISCOURAGED
                else -> PREFERRED
            }
        }
    }

    /**
     * PRF 扩展的请求输入（`extensions.prf`）。
     *
     * @param first `eval.first` 原始字节（Base64URL 解码后）
     * @param second `eval.second` 原始字节（可空）
     * @param evalByCredentialPresent 请求是否携带 `evalByCredential`（**注册请求中禁止出现**，
     *   出现即按规范 fail-closed 拒绝创建——KeePassDX 同口径）
     */
    class PrfEval(
        val first: ByteArray,
        val second: ByteArray?,
        val evalByCredentialPresent: Boolean
    )

    /** 依赖方标识（创建请求在 `rp.id`，断言请求在顶层 `rpId`） */
    val rpId: String
        get() = SimpleJson.string(SimpleJson.objectAt(root, WebAuthnJson.RP), WebAuthnJson.ID)
            ?: SimpleJson.string(root, WebAuthnJson.RP_ID)
            ?: ""

    val challenge: String get() = SimpleJson.string(root, WebAuthnJson.CHALLENGE) ?: ""

    /** 注册请求 `user.id`（WebAuthn 要求认证器原样保存并回传） */
    val userId: String get() = SimpleJson.string(userEntity, WebAuthnJson.ID) ?: ""

    val userName: String get() = SimpleJson.string(userEntity, WebAuthnJson.NAME) ?: ""

    val userDisplayName: String get() = SimpleJson.string(userEntity, WebAuthnJson.DISPLAY_NAME) ?: ""

    private val userEntity: Map<String, Any?>? get() = SimpleJson.objectAt(root, WebAuthnJson.USER)

    /** 注册请求 `pubKeyCredParams[].alg`（按请求顺序） */
    val pubKeyCredParams: List<Int>
        get() = SimpleJson.arrayAt(root, WebAuthnJson.PUB_KEY_CRED_PARAMS)
            .orEmpty()
            .mapNotNull { SimpleJson.int(SimpleJson.asObject(it), WebAuthnJson.ALG) }

    /** 断言请求 `allowCredentials[].id`（空集表示「无用户名」流程，不做收敛） */
    val allowCredentialIds: Set<String> get() = descriptorIds(WebAuthnJson.ALLOW_CREDENTIALS)

    /** 注册请求 `excludeCredentials[].id` */
    val excludeCredentialIds: Set<String> get() = descriptorIds(WebAuthnJson.EXCLUDE_CREDENTIALS)

    /** 断言请求 `userVerification`（缺省 `preferred`） */
    val userVerification: UserVerification
        get() = UserVerification.from(SimpleJson.string(root, WebAuthnJson.USER_VERIFICATION))

    /** 注册请求 `authenticatorSelection.userVerification`（缺省 `preferred`） */
    val authenticatorSelectionUserVerification: UserVerification
        get() = UserVerification.from(
            SimpleJson.string(SimpleJson.objectAt(root, WebAuthnJson.AUTHENTICATOR_SELECTION), WebAuthnJson.USER_VERIFICATION)
        )

    /** PRF 请求输入；未请求 PRF 或形态非法（缺 `eval.first`）时返回 null */
    val prfEval: PrfEval?
        get() {
            val prf = SimpleJson.objectAt(SimpleJson.objectAt(root, WebAuthnJson.EXTENSIONS), WebAuthnJson.PRF) ?: return null
            val evalByCredentialPresent = prf.containsKey(WebAuthnJson.EVAL_BY_CREDENTIAL)
            val eval = SimpleJson.objectAt(prf, WebAuthnJson.EVAL)
            val firstBytes = base64UrlDecode(SimpleJson.string(eval, WebAuthnJson.FIRST).orEmpty()) ?: return null
            val secondBytes = SimpleJson.string(eval, WebAuthnJson.SECOND)
                ?.takeIf { it.isNotBlank() }
                ?.let { base64UrlDecode(it) }
            return PrfEval(firstBytes, secondBytes, evalByCredentialPresent)
        }

    private fun descriptorIds(key: String): Set<String> {
        val array = SimpleJson.arrayAt(root, key) ?: return emptySet()
        val ids = LinkedHashSet<String>(array.size)
        for (item in array) {
            val id = SimpleJson.string(SimpleJson.asObject(item), WebAuthnJson.ID)
            if (!id.isNullOrBlank()) ids += id
        }
        return ids
    }

    companion object {

        /** 解析请求 JSON；非法 / 空输入返回 null（调用方按安全缺省处理） */
        fun parse(json: String?): WebAuthnRequest? {
            if (json.isNullOrBlank()) return null
            return try {
                SimpleJson.asObject(SimpleJson.parse(json))?.let { WebAuthnRequest(it) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Base64URL 解码（容忍有/无 padding，对齐 `android.util.Base64` 的
         * `URL_SAFE or NO_PADDING or NO_WRAP` 语义）；非法输入返回 null（fail-closed）。
         */
        fun base64UrlDecode(text: String): ByteArray? = decode(text, urlSafe = true)

        /** 标准 Base64 解码（容忍无 padding）；非法输入返回 null */
        fun base64Decode(text: String): ByteArray? = decode(text, urlSafe = false)

        private fun decode(text: String, urlSafe: Boolean): ByteArray? {
            if (text.isBlank()) return null
            val normalized = text.trim().let { value ->
                if (urlSafe) value.replace('-', '+').replace('_', '/') else value
            }
            val padded = when (normalized.length % 4) {
                2 -> "$normalized=="
                3 -> "$normalized="
                0 -> normalized
                else -> return null
            }
            return try {
                Base64.getDecoder().decode(padded)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
