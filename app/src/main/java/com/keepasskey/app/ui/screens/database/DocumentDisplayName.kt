package com.keepasskey.app.ui.screens.database

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 选取结果的共享展示名解析器（自 `DatabasePickerScreen.kt` 按职责拆出——
 * 本文件新增后该页面回落出行数闸门 tier2 以下；拆分**零行为变更**，只做结构搬运）。
 *
 * 调用方有三：`DatabasePickerScreen` 的库列表装配、`OpenExistingVaultDialog` 与
 * `CreateVaultWizardDialog` 的 SAF 回调——三处都在组合作用域协程内调用
 * （`ISSUE-P3-616` 离主线程接线，见 `ProviderMetadataQueryOffMainThreadWiringTest`）。
 */

/**
 * 从 SAF 选取结果解析展示名；失败时回退 URI 末段，绝不因异常阻断选择流程。
 *
 * `ISSUE-P3-616`：查询与 Cursor 读取固定落 [Dispatchers.IO]——SAF 选择器可选中由缓慢 / 恶意
 * Provider 提供的文档，绝不让主线程等待外部 Provider（ANR 面）；调用方须在带取消语义的
 * 协程作用域内调用（对话框组合作用域 / ViewModel scope），离场即取消、结果作废。
 */
internal suspend fun queryDocumentDisplayName(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        // cancel-n/a: 保护段为阻塞式 Provider 查询（块内无挂起点），取消经 withContext 边界传播
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }
