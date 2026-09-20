package com.keepasskey.app.passkey

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「调用浏览器不在特权名单」的**就地补救**构建器（ISSUE-P3-221）。
 *
 * 刻意从 [PasskeyCreateActivity] 抽出：该活动行数贴着约 400 行阈值，内联这段会越线。
 * 本对象无状态、只读外部传入的事实。
 *
 * ## 三个 fail-closed 前提（任一不满足即返回 `null`，不给假入口）
 *
 * 1. 原因本身带补救动作（仅 `DAL_UNVERIFIED` / `DAL_NETWORK_UNAVAILABLE`，
 *    见 [CredentialRejectionAction.forReason]）；
 * 2. 调用包名非空——它必须是**系统背书**的包名（`CallingOriginResolver.systemAttestedPackageName`），
 *    取不到即无从授权；
 * 3. 该包**确实是浏览器候选**：出现在 [PasskeyPrivilegedBrowserStore.installedBrowsers]
 *    （= 能处理 https VIEW、且现场可读签名）中。**这一条同时排除了原生 App**——原生 App
 *    的 DAL 失败不会因「加入浏览器白名单」而治愈，给它这个入口就是骗用户。
 *
 * 另外：若该浏览器**已启用**仍走到这里，说明问题不在白名单（本次 origin 早已固定），
 * 给「添加」按钮无意义 ⇒ 同样返回 `null`。
 */
internal object BrowserRemedyBuilder {

    /**
     * @param callerPackage 系统背书的调用方包名
     * @param scope 宿主生命周期作用域，授权协程在其上发起
     * @return 可渲染的补救描述；不满足前提时为 `null`
     */
    fun build(
        context: Context,
        store: PasskeyPrivilegedBrowserStore,
        reason: CredentialRejectionReason,
        callerPackage: String?,
        scope: LifecycleCoroutineScope
    ): CredentialRejectionRemedy? {
        if (CredentialRejectionAction.forReason(reason) == null) return null
        val pkg = callerPackage?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // 浏览器候选枚举（含资格 + 展示名 + 当前启用态）；仅拒绝路径执行，频率低
        val browser = store.installedBrowsers()
            .firstOrNull { it.packageName == pkg.lowercase() } ?: return null
        if (browser.enabled) return null

        return CredentialRejectionRemedy(
            description = context.getString(
                R.string.cred_reject_browser_not_allowed, browser.label
            ),
            actionLabel = context.getString(R.string.cred_reject_action_add_browser),
            successMessage = context.getString(
                R.string.cred_reject_browser_added, browser.label
            ),
            failureMessage = context.getString(R.string.cred_reject_browser_add_failed),
            // 写入属包管理/偏好面：下沉 Default，完成后回调（默认主线程）更新页面
            perform = { onOutcome ->
                scope.launch {
                    onOutcome(
                        withContext(Dispatchers.Default) { store.setEnabled(pkg, true) }
                    )
                }
            }
        )
    }
}