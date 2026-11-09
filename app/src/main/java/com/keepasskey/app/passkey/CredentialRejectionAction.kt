package com.keepasskey.app.passkey

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * 拒绝原因页的**就地补救动作**（ISSUE-P3-221）。
 *
 * ## 为什么需要单独一层
 *
 * [CredentialRejectionReason] 回答「为什么被拒」，本枚举回答「**那怎么办**」。二者分开，
 * 是因为并非每个原因都有用户可执行的动作：`CREDENTIAL_ALREADY_EXISTS` 本身是正常结果、
 * `MISSING_REQUEST` / `INTERNAL_ERROR` 属系统或内部异常，用户无从下手。硬给所有原因配一个
 * 动作按钮只会把用户送进找不到答案的状态。
 *
 * ## 两条受支持的补救路径（产品裁决 PD-12 与 `ENG-20261028-06`）
 *
 * - **加入特权浏览器名单**：`DAL_UNVERIFIED` 的成因是「调用应用未被站点声明授权」，DAL 校验
 *   正是防「任意应用冒充任意域名注册 Passkey」的核心防线。**不提供**「跳过 DAL 校验」的入口；
 * - **登记调用方签名**：原生调用没有可推导的 RP，签发授权只能来自「系统背书的包名 + 签名」。
 *   未登记时**不提供**「跳过签名校验」的入口，只允许把**本次系统背书的摘要**写入信任存储。
 *
 * 两者都只在「从未登记 / 从未授权」时出现；已登记而本次签名不符时一律不给重绑入口
 * （那等于让顶替者自证身份）。
 *
 * ## 「就地」语义（与「跳转设置页」方案的区别）
 *
 * 动作在**当前受保护窗口内**执行完成，不拉起任何其它界面。但**本次**被拒请求**仍然失败**：
 * 其 origin 在创建之初已被解析为 `apk-key-hash`，不会因授权而改变。授权影响的是**下一次**
 * 发起——用户回到浏览器重新创建时，该浏览器才能经官方 `getOrigin` 拿到 web origin 而豁免 DAL。
 *
 * ## 文案纪律（ISSUE-P1-10）
 *
 * 按钮文案一律**无插值**：不得携带调用包名 / rpId / 域名。调用方应用名允许出现在**说明句**
 * 里（它取自系统背书包的展示名，用户正在与该应用交互，不构成跨边界泄露），但不得写入按钮。
 */
enum class CredentialRejectionAction(@StringRes val labelRes: Int) {

    /** 把发起请求的浏览器就地加入特权浏览器白名单（唯一受支持的补救路径） */
    ADD_PRIVILEGED_BROWSER(R.string.cred_reject_action_add_browser),

    /**
     * `ENG-20261028-06`：把**本次系统背书的调用方签名摘要**就地登记为 `android://` 维度的
     * 授权参照（[CredentialManagerCallerTrustStore.trust]）。
     *
     * 只对「从未绑定」提供（[PasskeyRpVerdict.CALLER_UNBOUND]）。已绑定但签名不符时**不得**
     * 出现本动作：那等于让顶替者自证身份。与 [ADD_PRIVILEGED_BROWSER] 同为窗口内闭环、
     * 无跳转、且只影响**下一次**发起。
     */
    AUTHORIZE_CALLER_SIGNATURE(R.string.cred_reject_action_authorize_caller);

    companion object {

        /**
         * 原因 → 补救动作（**纯函数**，JVM 单测穷举）。无可执行动作时返回 `null`，
         * 此时拒绝页维持「只有退出」的原布局。
         *
         * **同一成因族**：`DAL_UNVERIFIED` / `DAL_NETWORK_UNAVAILABLE`（创建链路，非白名单
         * 浏览器走了普通应用 DAL 分支）与 `CALLER_BROWSER_NOT_TRUSTED`（`ISSUE-P2-530`，
         * 使用链路，同一条「非白名单浏览器无 web origin」成因）三者对
         * [ADD_PRIVILEGED_BROWSER] 都是正解。
         *
         * `ENG-20261028-06`：`CALLER_SIGNATURE_UNBOUND` 的唯一正解是**用户显式授权该签名**；
         * `CALLER_SIGNATURE_MISMATCH` 刻意**没有**动作——就地重绑等于让顶替者自证身份。
         */
        fun forReason(reason: CredentialRejectionReason): CredentialRejectionAction? =
            when (reason) {
                CredentialRejectionReason.DAL_UNVERIFIED,
                CredentialRejectionReason.DAL_NETWORK_UNAVAILABLE,
                CredentialRejectionReason.CALLER_BROWSER_NOT_TRUSTED -> ADD_PRIVILEGED_BROWSER

                CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND -> AUTHORIZE_CALLER_SIGNATURE

                else -> null
            }
    }
}