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

    /**
     * `authenticatorSelection.userVerification` 的规范取值（缺省按 `preferred` 处理）。
     *
     * 注：**不**登记 `authenticatorSelection.residentKey` / `requireResidentKey` 两个常量——
     * `clientExtensionResults.credProps.rk` 如实报 `true`（本仓凭据断言时按 rpId 全库匹配即可取出，
     * **确实可发现**，与 KeePassDX 恒报 `true` 一致），无需读取这两个请求字段。
     */
    const val VERIFICATION_REQUIRED = "required"

    const val VERIFICATION_DISCOURAGED = "discouraged"

    // ---------------- 扩展（PRF，WebAuthn L3 §10.1） ----------------

    const val EXTENSIONS = "extensions"
    const val PRF = "prf"

    /** Credential Properties 扩展（WebAuthn L3 §10.2）；请求侧取值恒为布尔 `true` */
    const val CRED_PROPS = "credProps"

    /** `credProps` 输出：该凭据是否可被发现（discoverable / resident key） */
    const val RK = "rk"
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

    /**
     * `response.publicKey`：凭据公钥的 base64url 编码。
     *
     * **编码口径已修正为 DER `SubjectPublicKeyInfo`（SPKI）**：W3C WebAuthn 把
     * `AuthenticatorAttestationResponse.getPublicKey()` 定义为「DER-encoded
     * SubjectPublicKeyInfo」，Monica 用 `keyPair.public.encoded`（SPKI）即遵循此口径。
     * 本仓此前误用 **COSE_Key CBOR**（那是 `attestationObject.authData.credentialPublicKey`
     * 的编码，两者不可互换），已由 [com.keepasskey.crypto.passkey.PasskeyCryptoEngine.publicKeySubjectInfoFor]
     * 单独产出 SPKI。
     */
    const val PUBLIC_KEY = "publicKey"

    /** `response.publicKeyAlgorithm`：COSE 算法号（**数字**，如 ES256 = `-7`） */
    const val PUBLIC_KEY_ALGORITHM = "publicKeyAlgorithm"

    /**
     * `transports[]` 取值：仅本设备内部凭据。**本仓当前只产出该值**。
     *
     * `hybrid`（混合传输，本机 + 其它设备）**已从此常量表删除**——本仓无蓝牙权限、无 BLE 广播、
     * 无会话隧道，声明它属对外虚报能力（`ISSUE-P3-338`）；恢复该常量的前提是 CTAP2.2 §11.5 全链可用，
     * 判据与两种相反先例见 [com.keepasskey.app.passkey.PasskeyRegistrationPayload] 的 `transports` 段。
     */
    const val TRANSPORT_INTERNAL = "internal"

    // ---------------- clientDataJSON（§5.4.1 / §6.4.1） ----------------

    /** `clientDataJSON.type` 取值：断言 */
    const val CLIENT_DATA_TYPE_GET = "webauthn.get"

    /** `clientDataJSON.type` 取值：注册 */
    const val CLIENT_DATA_TYPE_CREATE = "webauthn.create"

    const val ORIGIN = "origin"

    /**
     * `clientDataJSON.crossOrigin`：本次请求是否发生在跨源（iframe）上下文。
     *
     * `ISSUE-P2-265`：本仓此前省略该字段（规范允许省略、默认 `false`），
     * 而 Monica 显式写 `false`。显式下发可让 RP 无需依赖缺省语义，对齐参考实现。
     */
    const val CROSS_ORIGIN = "crossOrigin"

    /** DAL 扩展：系统背书的调用方包名（仅在有背书时写入，绝不回退为本应用包名） */
    const val ANDROID_PACKAGE_NAME = "androidPackageName"

    // ---------------- `none` 格式 attestationObject（CBOR map 键） ----------------
    //
    // ⚠️ 这一节是 **CBOR map 的键名**，与响应 JSON 的字段名只是形近、语义与位置完全不同。
    // 尤其 `authData`（本节的 CBOR 键，8 字符）与 [AUTHENTICATOR_DATA]（响应 JSON 字段，
    // `authenticatorData`，17 字符）**不可互相替换**——本仓曾因此产出一份浏览器无法解析的
    // 证明对象（详见 [AUTH_DATA]）。

    const val FORMAT = "fmt"

    /** `fmt` 取值：自 attestation 关闭（本应用不伪造整链） */
    const val FORMAT_NONE = "none"

    const val ATTESTATION_STATEMENT = "attStmt"

    /**
     * `attestationObject` CBOR map 内承载认证器数据的字节串键 —— **`authData`**（CTAP2 §6.5.4）。
     *
     * ⚠️ **与 [AUTHENTICATOR_DATA] 是两个东西**：后者是响应 JSON 里 `response.authenticatorData`
     * 的**字段名**（17 字符），前者是 CBOR map 的**键名**（8 字符）。二者仅形近。
     *
     * 本仓曾在此误用 [AUTHENTICATOR_DATA] 作 CBOR 键，产出的证明对象里只有 `authenticatorData`
     * 键、没有规范要求的 `authData` 键。后果具有极强的误导性：
     *
     * 1. **系统侧一切正常** —— CredMan 只校验注册响应 JSON 的合法性（`isValidJSON`），
     *    不解析 CBOR，于是 `Final credential received`、`resultCode=-1` 一路绿灯；
     * 2. **浏览器侧直接失败** —— Chromium 把响应转成 WebAuthn 对象时按规范查找 `authData`：
     *    ```
     *    E chromium: [ERROR:components/webauthn/android/fido2credentialrequest_native_android.cc:59]
     *      MojoClassFromJSON failed to convert JSON: field missing or invalid: attestationObject
     *    ```
     *    网页因此收到 `UnknownError`（"unknown error occurred while talking to the credential
     *    manager"），**依赖方从未收到任何凭据**，自然「未成功验证」。
     *
     * 这正是「本地显示添加成功、网站一律判未通过」且「浏览器与原生 App 表现一致、换站点也一样」
     * 的**单一根因**，也与两个参考实现（KeePassDX `ao["authData"]`、Monica 同）的行为差异所在。
     */
    const val AUTH_DATA = "authData"
}
