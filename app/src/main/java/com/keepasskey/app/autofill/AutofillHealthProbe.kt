package com.keepasskey.app.autofill

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.autofill.AutofillManager
import androidx.credentials.CredentialManager
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
 * ISSUE-P3-113：另采集字段屏蔽签名密钥的可用性（来自
 * [AutofillFieldBlocklistStore.signatureUnavailable]，属本应用自身运行时状态）。
 */
@Singleton
class AutofillHealthProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    // ISSUE-P3-113：字段屏蔽签名密钥的可用性（用户可感知的静默故障来源之一）
    private val fieldBlocklistStore: AutofillFieldBlocklistStore
) {

    /** 采集一次健康报告；[appEnabled] 由调用方从偏好状态传入 */
    fun probe(appEnabled: Boolean): AutofillHealthReport = AutofillHealthPolicy.evaluate(
        serviceDeclared = isServiceDeclared(),
        appEnabled = appEnabled,
        systemEnabled = isSystemAutofillServiceEnabled(),
        credentialManagerAvailable = isCredentialManagerAvailable(),
        fieldBlockSignatureUnavailable = fieldBlocklistStore.signatureUnavailable.value
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

    /** 系统当前是否已把本应用选为自动填充服务 */
    private fun isSystemAutofillServiceEnabled(): Boolean = try {
        context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取系统自动填充服务状态失败，按未启用处理", t)
        false
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
    }
}
