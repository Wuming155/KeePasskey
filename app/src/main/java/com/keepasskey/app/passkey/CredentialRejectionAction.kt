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
 * ## 为什么只有「加入特权名单」一条路（产品裁决 PD-12）
 *
 * `DAL_UNVERIFIED` 的成因是「调用应用未被站点声明授权」，DAL 校验正是防「任意应用冒充任意
 * 域名注册 Passkey」的核心防线。**不提供**「跳过 DAL 校验」的入口——那是一步削弱防线的
 * 捷径。唯一的受支持路径是把发起请求的浏览器**就地加入**特权浏览器白名单（浏览器路径本就
 * 豁免 DAL，与网络无关，故 `DAL_NETWORK_UNAVAILABLE` 同样由它治愈）。
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
    ADD_PRIVILEGED_BROWSER(R.string.cred_reject_action_add_browser);

    companion object {

        /**
         * 原因 → 补救动作（**纯函数**，JVM 单测穷举）。无可执行动作时返回 `null`，
         * 此时拒绝页维持「只有退出」的原布局。
         *
         * **顺序敏感**：`DAL_UNVERIFIED` 与 `DAL_NETWORK_UNAVAILABLE` 都源自同一条
         * 「非白名单浏览器走了普通应用 DAL 分支」，授权对两者都是正解。
         */
        fun forReason(reason: CredentialRejectionReason): CredentialRejectionAction? =
            when (reason) {
                CredentialRejectionReason.DAL_UNVERIFIED,
                CredentialRejectionReason.DAL_NETWORK_UNAVAILABLE -> ADD_PRIVILEGED_BROWSER

                else -> null
            }
    }
}