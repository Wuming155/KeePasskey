package com.keepasskey.app.passkey

import com.keepasskey.app.passkey.CredentialManagerPackageBindingGate.Decision

/**
 * 通行密钥**签发前**的「请求 RP × 调用方身份」授权裁决（`ENG-20261028-06`）。
 *
 * ## 它补上的是哪些格
 *
 * 候选检索按请求 `rp.id` 放行、签发侧只比 RP 是否相抵触，于是「包名相同」被当成了授权：
 * 只要条目 `android://<包名>` 与系统背书的调用包名相等，[CredentialManagerPackageBindingGate]
 * 的 [Decision.UNBOUND]（该包名从未登记签名）就不再提供任何参照。本对象要求原生调用
 * **必须**有签名绑定才签发，与请求是否声明 RP 无关。
 *
 * ## 为什么原生调用一律要看签名绑定
 *
 * - **web（浏览器委派）调用**：origin 由系统背书，`rpId` 缺省即「origin 的有效域」，
 *   这正是 WebAuthn 的缺省规则 ⇒ RP 与调用方归属都由系统背书的 origin 锚定，签名绑定不参评；
 *   origin ⇄ 凭据 RP 的归属复校仍由调用方单独执行，本对象不替代它。
 * - **原生调用**：origin 是 `android:apk-key-hash:…`，**没有**有效域；包名不是 RP 标识符；
 *   请求自带的 `rpId` 由调用方自报，只能用于**挑选**凭据，不能用于**授权**调用方。
 *   因此原生调用的授权参照只有「系统背书的包名 + 签名绑定」这一条：
 *   [Decision.AUTHORIZED] 才可签发，[Decision.UNBOUND] 需用户**显式授权**
 *   （[PasskeyRpVerdict.authorizeCallerInWindow]），[Decision.REJECTED]
 *   （已绑定但本次签名不符）任何 RP 状态下都拒绝且不得就地重绑。
 *
 * ## 完整判定表
 *
 * | 调用形态 | 请求 RP | 包名绑定 | 裁决 | 可在窗口内补授权 |
 * |---|---|---|---|---|
 * | web | 显式且相等 | 不参评 | `ALLOWED` | — |
 * | web | 显式但不相等 | 不参评 | `RP_MISMATCH` | — |
 * | web | 缺失 | 不参评 | `ALLOWED`（origin 锚定，另需 origin ⇄ RP 复校） | — |
 * | 原生 | 显式且相等 | `AUTHORIZED` | `ALLOWED` | — |
 * | 原生 | 显式且相等 | `UNBOUND` | `CALLER_UNBOUND` | 是 |
 * | 原生 | 显式且相等 | `REJECTED` | `CALLER_BINDING_REJECTED` | **否** |
 * | 原生 | 显式但不相等 | 任意 | `RP_MISMATCH` | — |
 * | 原生 | 缺失 | `AUTHORIZED` | `ALLOWED` | — |
 * | 原生 | 缺失 | `UNBOUND` | `CALLER_UNBOUND` | 是 |
 * | 原生 | 缺失 | `REJECTED` | `CALLER_BINDING_REJECTED` | **否** |
 *
 * 「显式 RP 相等」只说明请求要的是哪把密钥（选择器），不构成对调用方的授权：请求 JSON 由
 * 调用方自报，任何应用都能写 `rpId: "example.com"`。这正是「显式 RP + `UNBOUND`」必须与
 * 「缺失 RP + `UNBOUND`」同判的原因。
 *
 * ## 依据
 *
 * - **WebAuthn**：断言请求缺 `rpId` 时以调用方 origin 的有效域代替；`android:apk-key-hash:`
 *   不是可推导有效域的 tuple origin，故原生缺失 RP 的请求本身没有 RP 身份；`rpId` 由客户端
 *   自报，规范中的 RP 归属校验是**服务端**对 origin / 关联声明的验证，不是认证器的授权判据。
 * - **Android Credential Manager**：`android://<包名>` 是**客户端归属**维度，不是 RP；
 *   框架不为原生调用补 `rpId`，`CallingAppInfo.packageName` 只提供系统背书的包名，
 *   签名真伪须由 provider 自行比对（本项目即 `CredentialManagerCallerTrustStore`）。
 * - **参考实现对照**（只按本问题查阅）：fenris-authenticator 在 `rpId` 缺失且调用方非浏览器时
 *   直接判「Unable to obtain RP」拒签（`credentialprovider/webauthn/Validation.kt` 的
 *   `appInfoToRpId` 返回 null + `AuthenticateActivity.isValid` 的早退）；Monica 与 KeePassDX
 *   未做该判定，不作为本仓口径依据。
 *
 * 纯函数：不读 Activity / Service / 存储，两个外部事实由参数注入，可被 JVM 穷举。
 */
internal object PasskeyRpAuthorization {

    /**
     * @param requestRpId 请求携带的 RP（创建取 `rp.id`、断言取顶层 `rpId`；缺失为空串）
     * @param credentialRpId 凭据自身记录的 RP（条目 `Passkey.RelyingPartyId`）
     * @param browserFlow 调用方是否持系统背书的 web origin
     * @param callerBinding 原生调用的包名签名绑定判定（web 调用不参评，可传任意值）
     */
    fun evaluate(
        requestRpId: String,
        credentialRpId: String,
        browserFlow: Boolean,
        callerBinding: Decision
    ): PasskeyRpVerdict {
        // 请求显式声明了别的 RP：两条通道同一口径，先拒绝（与调用方身份无关）
        if (PasskeyRequestRpBinding.evaluate(requestRpId, credentialRpId) == RequestRpBinding.MISMATCHED) {
            return PasskeyRpVerdict.RP_MISMATCH
        }
        // web：RP 由系统背书 origin 锚定，签名绑定不参与；缺失 RP 交给 origin ⇄ RP 复校
        if (browserFlow) return PasskeyRpVerdict.ALLOWED
        // 原生：包名相同不等于调用方已获授权——签名绑定是唯一参照，RP 状态不改变这一点
        return when (callerBinding) {
            Decision.AUTHORIZED -> PasskeyRpVerdict.ALLOWED
            Decision.UNBOUND -> PasskeyRpVerdict.CALLER_UNBOUND
            Decision.REJECTED -> PasskeyRpVerdict.CALLER_BINDING_REJECTED
        }
    }
}

/** [PasskeyRpAuthorization.evaluate] 的裁决结果；非 `ALLOWED` 一律不得签发 */
internal enum class PasskeyRpVerdict {
    /** 授权成立，可继续用户验证与签发 */
    ALLOWED,

    /** 请求显式 RP 与凭据 RP 不一致 */
    RP_MISMATCH,

    /**
     * 原生调用，该包名**从未**登记签名绑定（与请求是否声明 RP 无关）。
     *
     * 本地没有可比对的签名参照，因此不得签发；用户可在受保护窗口内**显式授权**
     * （写入「包名 + 本次系统背书的签名摘要」），授权只影响下一次发起。
     */
    CALLER_UNBOUND,

    /**
     * 原生调用，该包名**已登记**签名绑定、但本次调用的签名不一致
     * （同 `applicationId` 顶替 / 换签名）。
     *
     * 一律拒绝，且**绝不**提供就地重绑：那等于让顶替者自证身份。
     */
    CALLER_BINDING_REJECTED;

    /** 是否非放行裁决 */
    val rejected: Boolean get() = this != ALLOWED

    /**
     * 该拒绝是否可在受保护窗口内**就地补授权**（把「包名 + 本次系统背书的签名摘要」写入
     * [CredentialManagerCallerTrustStore]，与 `BrowserRemedyBuilder` 的浏览器就地启用同型）。
     *
     * 只有「从未绑定」可补：[CALLER_BINDING_REJECTED] 说明该包名**已有**绑定记录而本次签名
     * 不符，就地补绑定等于让顶替者自证身份，一律不得提供。
     */
    val authorizeCallerInWindow: Boolean
        get() = this == CALLER_UNBOUND
}
