package com.keepasskey.app.passkey

import com.keepasskey.app.security.CallerCertDigests

/**
 * Credential Manager 通道 `android://` 包名维度的放行判定（**ISSUE-P2-83**）。
 *
 * ## 为什么不能直接复用自动填充通道的判据
 *
 * 自动填充侧（`ISSUE-P2-46`）用的是 [com.keepasskey.app.autofill.AndroidPackageBindingPolicy]
 * 的**二元**判据：未获授权即**不命中**。那在自动填充通道成立，是因为它有一个**选择器中立面**——
 * 候选为空时用户仍可经选择器显式指认调用方，授权有路可走。
 *
 * **CM 通道没有这个中立面**：候选为空，用户在系统 UI 上就什么也看不到，也无处授权。
 * 若照搬二元判据，则任何**从未绑定**过的 `android://` 条目（存量库 / 导入库 / 手工建库）
 * 在 CM 通道将**永久无路可走**——那是功能回归，不是加固。
 *
 * 故本通道采用**三值**判定，把「已绑定但不匹配」与「从未绑定」这两件事分开：
 *
 * | 判定 | 条件 | 处置 |
 * |---|---|---|
 * | [Decision.AUTHORIZED] | 已绑定该包名，且本次调用方摘要**命中** | 正常放行 |
 * | [Decision.REJECTED] | 包名为空，**或**已绑定该包名但本次摘要**不匹配** | **不命中**（`AC②` 的两条负向判据） |
 * | [Decision.UNBOUND] | 该包名**从未**绑定过 | 无可参照，退回包名维度的既有行为（签发侧另见下文） |
 *
 * ## `UNBOUND` 的边界（如实声明，不得读作「已加固」）
 *
 * `UNBOUND` 下**候选检索**仍按「条目 `android://` 绑定包名 == 调用包名」放行，因此
 * 对从未绑定过的包名，包名维度本身不提供签名保证。之所以不在此处 fail-closed：
 * 无参照时「拒绝」会让存量条目在系统 UI 上**零候选**，用户既看不到内容也拿不到授权入口；
 * 而「首次使用即隐式绑定」更糟——若把**首个**调用方（可能是侧载应用）记成绑定，
 * 之后真正的应用反而会被判为「已绑定但不匹配」而**永久失效**。
 *
 * ## 与签发授权的关系（`ENG-20261028-06`）
 *
 * 本对象只回答「包名维度**能不能用来挑选候选**」，不是签发授权：通行密钥签发由
 * [PasskeyRpAuthorization] 裁决，两者的分工是刻意的：
 *
 * - **口令候选**：`UNBOUND` 继续按包名维度放行（`ISSUE-P2-83` 的取舍；口令没有 RP 概念）；
 * - **通行密钥候选**：`UNBOUND` 仍出候选（保住授权入口的可达性），
 * - **通行密钥签发**：原生调用 `UNBOUND` 一律拒绝（`CALLER_UNBOUND`，与请求是否带 RP 无关），
 *   只能经用户在受保护窗口内**显式授权**（写入本次系统背书的签名摘要）后，下一次发起才能签发；
 *   已绑定但签名不符（`REJECTED`）任何 RP 状态下都拒绝，且不得就地重绑。
 *
 * ## 纯函数
 *
 * 判定不依赖 Activity / Service，两个外部事实以函数参数注入，可被 JVM 单测穷举。
 */
object CredentialManagerPackageBindingGate {

    enum class Decision {
        /** 已绑定且本次调用方摘要命中 —— 正常放行 */
        AUTHORIZED,

        /**
         * 该包名从未绑定过 —— 候选检索可按包名维度列候选，但**通行密钥签发一律拒绝**
         * （`CALLER_UNBOUND`），须用户在受保护窗口内显式登记签名后才能签发
         */
        UNBOUND,

        /** 包名为空，或已绑定该包名但本次摘要不匹配 —— 必须不命中 */
        REJECTED
    }

    /**
     * @param callingPackage 系统背书的调用方包名
     * @param certDigests 调用方签名摘要集合（不可读时为 `CallerCertDigests.EMPTY`）
     * @param isTrusted 「包名 + 签名摘要」是否已获授权（生产传
     *   [CredentialManagerCallerTrustStore.isTrusted] 的集合重载）
     * @param hasAnyBinding 该包名是否已有任一签名绑定（生产传
     *   [CredentialManagerCallerTrustStore.hasAnyBindingFor]）
     */
    fun decide(
        callingPackage: String,
        certDigests: CallerCertDigests,
        isTrusted: (String, CallerCertDigests) -> Boolean,
        hasAnyBinding: (String) -> Boolean
    ): Decision {
        if (callingPackage.isBlank()) return Decision.REJECTED
        if (isTrusted(callingPackage, certDigests)) return Decision.AUTHORIZED
        // 摘要不可读时 isTrusted 恒 false，故「已绑定」一律落到此处 ⇒ fail-closed（不放行）。
        if (hasAnyBinding(callingPackage)) return Decision.REJECTED
        return Decision.UNBOUND
    }

    /**
     * 包名维度是否可用于**候选检索**（仅 [Decision.REJECTED] 不放行）。
     *
     * 本函数**不是**签发授权：`UNBOUND` 返回 `true` 只表示「可以按包名挑候选」，
     * 通行密钥签发须改用 [PasskeyRpAuthorization.evaluate]（它还会看请求是否带 RP）。
     */
    fun allowsPackageDimension(
        callingPackage: String,
        certDigests: CallerCertDigests,
        isTrusted: (String, CallerCertDigests) -> Boolean,
        hasAnyBinding: (String) -> Boolean
    ): Boolean = decide(callingPackage, certDigests, isTrusted, hasAnyBinding) != Decision.REJECTED
}
