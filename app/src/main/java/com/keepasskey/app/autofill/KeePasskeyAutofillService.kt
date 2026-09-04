package com.keepasskey.app.autofill

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import com.keepasskey.app.data.repository.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android 传统自动填充服务 (AutofillService) 兼容实现。
 * 针对尚未适配 Credential Manager 的应用表单提供用户名与密码自动填充能力。
 */
@AndroidEntryPoint
class KeePasskeyAutofillService : AutofillService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        if (cancellationSignal.isCanceled) return

        serviceScope.launch {
            try {
                val structure: AssistStructure = request.fillContexts.lastOrNull()?.structure ?: run {
                    callback.onSuccess(null)
                    return@launch
                }

                val response = FillResponse.Builder().build()
                callback.onSuccess(response)
            } catch (t: Throwable) {
                Log.e(TAG, "onFillRequest 错误", t)
                callback.onFailure(t.message)
            }
        }
    }

    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback
    ) {
        // 保存新凭据回调
        callback.onSuccess()
    }

    companion object {
        private const val TAG = "KeePasskeyAutofill"
    }
}
