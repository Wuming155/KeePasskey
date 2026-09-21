package com.keepasskey.app.passkey

import android.content.ComponentName
import android.content.Context
import android.credentials.CredentialManager
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据提供者通道健康探针（`ISSUE-P2-239`，先例 [com.keepasskey.app.autofill.AutofillHealthProbe]）。
 *
 * 职责严格限定为两件事——**唯一的平台查询点** + 本应用组件名推导；
 * 判定本身落在纯函数 [CredentialProviderRegistration.of]（AC③：平台查询单点化便于注入与穷举）。
 *
 * 隐私约定：只向系统询问「本应用自己的组件是否已启用」，不枚举应用、不触碰任何凭据数据。
 */
@Singleton
class CredentialProviderHealthProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 采集一次登记状态；任何异常都收敛为 [CredentialProviderRegistration.UNKNOWN]（不外抛） */
    fun probe(): CredentialProviderRegistration =
        CredentialProviderRegistration.of(readEnabledState(ownComponentName()))

    /**
     * 本应用凭据提供者组件。
     *
     * 由 Manifest 登记的组件类**推导**（`ComponentName(context, 类)`），**不得**硬编码包名 ——
     * 本项目的 `applicationId`（`com.keepasskey`）与源码命名空间（`com.keepasskey.app`）
     * 并不一致，硬编码任一形态都会在另一形态下判错（§240 R1 现场即为此形态）。
     */
    private fun ownComponentName(): ComponentName? = try {
        ComponentName(context, KeePasskeyCredentialProviderService::class.java)
    } catch (t: Throwable) {
        AppLog.w(TAG, "推导本应用凭据提供者组件名失败，登记状态按未知处理", t)
        null
    }

    /**
     * 向系统询问该组件是否为「已启用的凭据提供者」。
     *
     * 用**公开 API** `android.credentials.CredentialManager.isEnabledCredentialProviderService`
     * （API 34+）：无需权限、不读内部键。初版曾读 `Settings.Secure.credential_service`，
     * 该方案在真机上被推翻（本应用读取抛 `SecurityException`，见 [CredentialProviderRegistration]
     * 的类注释）。
     *
     * 失败一律返回 [CredentialProviderEnabledState.UNREADABLE]（AC②：读取失败 ⇒ 未知，不得当正常）。
     */
    private fun readEnabledState(component: ComponentName?): CredentialProviderEnabledState {
        if (component == null) return CredentialProviderEnabledState.UNREADABLE
        val manager = try {
            context.getSystemService(CredentialManager::class.java)
        } catch (t: Throwable) {
            AppLog.w(TAG, "取用系统凭据服务失败，登记状态按未知处理", t)
            null
        } ?: return CredentialProviderEnabledState.UNREADABLE

        return try {
            if (manager.isEnabledCredentialProviderService(component)) {
                CredentialProviderEnabledState.ENABLED
            } else {
                CredentialProviderEnabledState.DISABLED
            }
        } catch (t: Throwable) {
            // 只记失败事实与异常类型，不落任何取值
            AppLog.w(TAG, "查询系统凭据提供者启用状态失败，登记状态按未知处理", t)
            CredentialProviderEnabledState.UNREADABLE
        }
    }

    private companion object {
        const val TAG = "CredentialProviderProbe"
    }
}
