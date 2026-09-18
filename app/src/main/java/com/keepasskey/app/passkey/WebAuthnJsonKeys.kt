package com.keepasskey.app.passkey

/**
 * WebAuthn / Credential Manager 通道上的 **JSON 协议字段名与规范取值**（ISSUE-P3-188 收敛）。
 *
 * 依据 W3C WebAuthn Level 3：`CredentialCreationOptions`（§5.1.2）/ `CredentialRequestOptions`
 * （§5.5.3）/ `AuthenticatorAttestationResponse`（§6.4.5）/ `AuthenticatorAssertionResponse`
 * （§6.4.6）/ `clientDataJSON`（§5.4.1、§6.4.1）/ PRF 扩展（§10.1）；
 * `androidPackageName` 与 `apk-key-hash` 属 Google DAL（`android://`）扩展字段。
 *
 * **收敛纪律**：本对象只承载「同一字面量的定义位置」，**不改任何取值**；
 * 任何取值改动都属协议变更，须另行立项并过对拍。
 */
internal object WebAuthnJson {

    // ---------------- 请求侧（RP → 认证器） ----------------

    const val RP = "rp"

    /** 顶层 `rpId`（断言请求用；创建请求用 [RP] 对象内的 [ID]） */
    const val RP_ID = "rpId"

    /** `rp.id` / `user.id` / 凭据描述符 `id` 共用同名键 */
    const val ID = "id"

    const val USER = "user"
    const val NAME = "name"
    const val DISPLAY_NAME = "displayName"
    const val CHALLENGE = "challenge"
    const val PUB_KEY_CRED_PARAMS = "pubKeyCredParams"
    const val ALG = "alg"
    const val ALLOW_CREDENTIALS = "allowCredentials"
    const val EXCLUDE_CREDENTIALS = "excludeCredentials"
    const val AUTHENTICATOR_SELECTION = "authenticatorSelection"
    const val USER_VERIFICATION = "userVerification"

    /** `userVerification` 的规范取值（缺省按 `preferred` 处理） */
    const val VERIFICATION_REQUIRED = "required"

    const val VERIFICATION_DISCOURAGED = "discouraged"

    // ---------------- 扩展（PRF，WebAuthn L3 §10.1） ----------------

    const val EXTENSIONS = "extensions"
    const val PRF = "prf"
    const val EVAL = "eval"
    const val EVAL_BY_CREDENTIAL = "evalByCredential"
    const val FIRST = "first"
    const val SECOND = "second"
    const val ENABLED = "enabled"
    const val RESULTS = "results"
    const val CLIENT_EXTENSION_RESULTS = "clientExtensionResults"

    // ---------------- 认证器输出（回传给调用方的响应 JSON） ----------------

    const val TYPE = "type"
    const val RAW_ID = "rawId"

    /** `type` 取值：公钥凭据 */
    const val CREDENTIAL_TYPE_PUBLIC_KEY = "public-key"

    const val AUTHENTICATOR_ATTACHMENT = "authenticatorAttachment"

    /** `authenticatorAttachment` 取值：平台内凭据 */
    const val ATTACHMENT_PLATFORM = "platform"

    const val RESPONSE = "response"
    const val CLIENT_DATA_JSON = "clientDataJSON"
    const val AUTHENTICATOR_DATA = "authenticatorData"
    const val SIGNATURE = "signature"
    const val USER_HANDLE = "userHandle"
    const val ATTESTATION_OBJECT = "attestationObject"
    const val TRANSPORTS = "transports"

    /** `transports[]` 取值：仅本设备内部凭据 */
    const val TRANSPORT_INTERNAL = "internal"

    // ---------------- clientDataJSON（§5.4.1 / §6.4.1） ----------------

    /** `clientDataJSON.type` 取值：断言 */
    const val CLIENT_DATA_TYPE_GET = "webauthn.get"

    /** `clientDataJSON.type` 取值：注册 */
    const val CLIENT_DATA_TYPE_CREATE = "webauthn.create"

    const val ORIGIN = "origin"

    /** DAL 扩展：系统背书的调用方包名（仅在有背书时写入，绝不回退为本应用包名） */
    const val ANDROID_PACKAGE_NAME = "androidPackageName"

    // ---------------- `none` 格式 attestationObject（CBOR map 键） ----------------

    const val FORMAT = "fmt"

    /** `fmt` 取值：自 attestation 关闭（本应用不伪造整链） */
    const val FORMAT_NONE = "none"

    const val ATTESTATION_STATEMENT = "attStmt"
}
