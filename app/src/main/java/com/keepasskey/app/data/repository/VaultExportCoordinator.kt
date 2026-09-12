package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.csv.KdbxCsvExporter
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.xml.KeePassXmlExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 导出与模板协调器（ISSUE-P3-31 批次 B 自 `RealVaultRepository` 拆出，纯结构性改动）。
 *
 * 职责单一：整库 `.kdbx` 字节导出、KeePass XML 导出、密钥文件导出，以及条目模板分组安装
 * （TASK-13 设置页导出/模板真实化）。
 *
 * XML 导出为 CPU 密集的序列化，保留 `Dispatchers.Default` 调度；
 * 模板安装为幂等操作（同名模板分组已存在即失败返回）。
 */
internal class VaultExportCoordinator(
    private val context: Context,
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    suspend fun exportKdbxBytes(): KdbxResult<ByteArray> =
        databaseSession.exportToBytes()

    suspend fun exportVaultXmlBytes(): KdbxResult<ByteArray> =
        withContext(Dispatchers.Default) {
            val db = databaseSession.databaseFlow.first()
                ?: return@withContext KdbxResult.Failure(
                    IllegalStateException("活动数据库为空"),
                    strings.get(R.string.repo_no_active_db_for_export)
                )
            try {
                KdbxResult.Success(KeePassXmlExporter.export(db))
            } catch (t: Throwable) {
                KdbxResult.Failure(
                    t, strings.get(R.string.repo_export_xml_failed, t.message ?: "")
                )
            }
        }

    /**
     * ISSUE-P3-73：将当前内存数据库导出为通用明文 CSV（设置页「导出 CSV」用）。
     * 明文包含全部受保护字段（风险由导出二次确认对话框告知），锁定/关闭时返回 Failure。
     */
    suspend fun exportVaultCsvBytes(): KdbxResult<ByteArray> =
        withContext(Dispatchers.Default) {
            val db = databaseSession.databaseFlow.first()
                ?: return@withContext KdbxResult.Failure(
                    IllegalStateException("活动数据库为空"),
                    strings.get(R.string.repo_no_active_db_for_export)
                )
            try {
                KdbxResult.Success(KdbxCsvExporter.export(db))
            } catch (t: Throwable) {
                KdbxResult.Failure(
                    t, strings.get(R.string.repo_export_csv_failed, t.message ?: "")
                )
            }
        }

    suspend fun exportKeyFileBytes(): KdbxResult<ByteArray> {
        val bytes = databaseSession.exportKeyFileBytes()
            ?: return KdbxResult.Failure(
                IllegalStateException("会话未绑定密钥文件"),
                strings.get(R.string.repo_no_keyfile_to_export)
            )
        return KdbxResult.Success(bytes)
    }

    suspend fun installEntryTemplates(): KdbxResult<Unit> {
        // 幂等保护：已存在同名模板分组时不再重复安装
        val currentDb = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException("活动数据库为空"),
                strings.get(R.string.repo_no_active_db_for_export)
            )
        if (currentDb.rootGroup.subgroups.any { it.name == VaultTemplateFactory.TEMPLATE_GROUP_NAME }) {
            return KdbxResult.Failure(
                IllegalStateException("模板分组已存在"),
                context.getString(
                    R.string.repo_templates_already_installed,
                    VaultTemplateFactory.TEMPLATE_GROUP_NAME
                )
            )
        }
        // saveGroup 仅更新内存树（置 DIRTY），由 persistSession 统一序列化落盘并上传播结果
        databaseSession.saveGroup(VaultTemplateFactory.buildTemplateGroup())
        return persistSession()
    }
}
