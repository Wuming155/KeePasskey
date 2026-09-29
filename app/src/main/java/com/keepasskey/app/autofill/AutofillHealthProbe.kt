package com.keepasskey.app.autofill

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.credentials.CredentialManager
import com.keepasskey.app.passkey.CredentialProviderHealthProbe
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动填充服务健康探针（ISSUE-P3-41）。
 *
 * 负责读取**真实系统状态**并交由 [AutofillHealthPolicy] 判定；所有系统调用均以
 * try/catch 兜底为「不可用」，绝不因单点异常使设置页崩溃。
 *
 * 隐私约定：本类只检查本应用自身链路状态，**不**枚举用户安装的应用清单，
 * 也不读取任何条目/凭据数据。
 *
 */
@Singleton
class AutofillHealthProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    // ISSUE-P2-239：凭据提供者通道（CM）的系统登记状态——平台查询单点在该探针内部
    private val credentialProviderProbe: CredentialProviderHealthProbe
) {

    /** 采集一次健康报告；[appEnabled] 由调用方从偏好状态传入 */
    fun probe(appEnabled: Boolean): AutofillHealthReport = AutofillHealthPolicy.evaluate(
        serviceDeclared = isServiceDeclared(),
        appEnabled = appEnabled,
        systemEnabled = isSystemAutofillServiceEnabled(),
        credentialManagerAvailable = isCredentialManagerAvailable(),
        credentialProviderRegistration = credentialProviderProbe.probe()
    )

    /** 服务是否在 Manifest 声明且带 `BIND_AUTOFILL_SERVICE` 权限 */
    private fun isServiceDeclared(): Boolean = try {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, KeePasskeyAutofillService::class.java),
            PackageManager.ComponentInfoFlags.of(0L)
        )
        info.permission == android.Manifest.permission.BIND_AUTOFILL_SERVICE
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取自动填充服务声明失败，按未声明处理", t)
        false
    }

    /**
     * 系统当前是否已把本应用选为自动填充服务。
     *
     * `ISSUE-P3-391` 双路判定：`AutofillManager.hasEnabledAutofillServices` 在部分厂商 ROM 上
     * 查询延迟或返回空（Monica 同坑实证），单源 false 会把健康卡误报成「系统未启用」并引导用户
     * 做多余的设置跳转——故 manager 读数为 false 时回退复核 `Settings.Secure` 的
     * `autofill_service`（Monica 同款），与本应用组件名精确比对后下结论；
     * 合成口径与「待真机实测」事项见 [AutofillHealthPolicy.resolveSystemEnabled]。
     */
    private fun isSystemAutofillServiceEnabled(): Boolean {
        val managerEnabled = readManagerEnabledState()
        if (managerEnabled) return true
        return AutofillHealthPolicy.resolveSystemEnabled(
            managerEnabled = managerEnabled,
            secureSetting = readSecureAutofillServiceSetting(),
            ownServiceIds = ownAutofillServiceIds()
        )
    }

    /** manager 通道读数（`ISSUE-P3-41` 原单源读数；异常收敛为 false） */
    private fun readManagerEnabledState(): Boolean = try {
        context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取系统自动填充服务状态失败，按未启用处理", t)
        false
    }

    /**
     * 回退通道：`Settings.Secure` 的 `autofill_service` 读数（取值即当前所选自动填充服务的
     * 组件名）。该键为 AOSP 内部键、**无公开常量**（官方 `Settings.Secure` API 参考的常量表
     * 不含它，2026-09-29 核对），故以命名常量承载字符串字面量；读取失败收敛为 `null`
     * （判「未启用」），不谎报正常——本仓先例：内部键 `credential_service` 真机读取抛
     * `SecurityException`，此处同型失败必须兜住。
     */
    private fun readSecureAutofillServiceSetting(): String? = try {
        Settings.Secure.getString(context.contentResolver, SECURE_AUTOFILL_SERVICE_KEY)
    } catch (t: Throwable) {
        AppLog.w(TAG, "回退读取 Settings.Secure 的 autofill_service 失败，按未启用处理", t)
        null
    }

    /** 本应用自动填充服务组件名的两种落盘形态（全名 / 短名）；推导失败返回空集 ⇒ 回退复核不可用 */
    private fun ownAutofillServiceIds(): Set<String> = try {
        val component = ComponentName(context, KeePasskeyAutofillService::class.java)
        setOf(component.flattenToString(), component.flattenToShortString())
    } catch (t: Throwable) {
        AppLog.w(TAG, "推导本应用自动填充服务组件名失败，回退复核不可用", t)
        emptySet()
    }

    /** Credential Manager 通道是否可用（依赖存在且可实例化） */
    private fun isCredentialManagerAvailable(): Boolean = try {
        CredentialManager.create(context)
        true
    } catch (t: Throwable) {
        AppLog.w(TAG, "Credential Manager 不可用", t)
        false
    }

    private companion object {
        const val TAG = "AutofillHealthProbe"

        /**
         * `Settings.Secure` 的自动填充服务键（AOSP 内部键，无公开常量——
         * 官方 `Settings.Secure` API 参考常量表不含它，2026-09-29 核对）。
         * Monica `AutofillServiceChecker.kt:172` 修复同款即按此字面量读取。
         */
        const val SECURE_AUTOFILL_SERVICE_KEY = "autofill_service"
    }
}
