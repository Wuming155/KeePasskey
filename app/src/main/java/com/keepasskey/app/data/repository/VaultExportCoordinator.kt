package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.csv.KdbxCsvExporter
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.xml.KeePassXmlExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 导出与模板协调器（ISSUE-P3-31 批次 B 自 `RealVaultRepository` 拆出，纯结构性改动）。
 *
 * 职责单一：整库 `.kdbx` 字节导出、KeePass XML 导出、密钥文件导出，以及条目模板分组安装
 * （TASK-13 设置页导出/模板真实化）。
 *
 * XML 导出为 CPU 密集的序列化，保留 `Dispatchers.Default` 调度；
 * 模板安装为幂等操作（同名模板分组已存在且已登记 Meta 即失败返回）。
 *
 * `ISSUE-P3-469`：本类**不依赖 Android `Context`**——唯一曾用之处的失败文案已改走
 * [StringsProvider] 通道，故可在宿主 JVM 用例中直接驱动 [installEntryTemplates] 落盘对拍。
 */
internal class VaultExportCoordinator(
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
            } catch (t: Throwable) { // cancel-n/a: 保护段为纯 CPU 导出（挂起的 .first() 在 try 之前）
                // ISSUE-P3-550：`t.message` 会带出文件路径 / 协议细节，只进日志；
                // UI 侧一律走错误码映射（未归类 ⇒ `err_unknown`）
                AppLog.w(TAG, "导出 XML 失败", t)
                KdbxResult.Failure(
                    t, strings.get(R.string.repo_export_xml_failed, strings.get(R.string.err_unknown))
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
            } catch (t: Throwable) { // cancel-n/a: 保护段为纯 CPU 导出（挂起的 .first() 在 try 之前）
                // ISSUE-P3-550：细节只进日志，UI 走错误码映射
                AppLog.w(TAG, "导出 CSV 失败", t)
                KdbxResult.Failure(
                    t, strings.get(R.string.repo_export_csv_failed, strings.get(R.string.err_unknown))
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

    /**
     * 安装条目模板库（真实创建「模板」分组与 5 个模板条目）。
     *
     * `ISSUE-P3-469` 写侧：模板组的 UUID 必须同步写入 Meta `EntryTemplatesGroup` ——
     * 该字段是官方 KeePass / KeePassDX / KeePassXC 识别「模板组」的**唯一依据**
     * （官方语义要求模板集中在单一组内、且不承载真实数据条目），此前本仓只建组、
     * 从不写该 Meta，导致本仓写的库三家均不识别其模板组。
     *
     * 幂等分两种形态：模板组已在（Meta 命中或同名分组）且 Meta 已登记 ⇒ 直接失败不重复安装；
     * 模板组已在但 Meta 未登记（存量库 / 第三方库）⇒ **回填** Meta 后视为安装完成。
     */
    suspend fun installEntryTemplates(): KdbxResult<Unit> {
        val currentDb = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException("活动数据库为空"),
                strings.get(R.string.repo_no_active_db_for_export)
            )
        val metaTemplateGroup = currentDb.entryTemplatesGroup
            ?.let { id -> currentDb.rootGroup.allGroups().firstOrNull { it.id == id } }
        val namedTemplateGroup = currentDb.rootGroup.subgroups
            .firstOrNull { it.name == VaultTemplateFactory.TEMPLATE_GROUP_NAME }
        val existingTemplateGroup = metaTemplateGroup ?: namedTemplateGroup
        if (existingTemplateGroup != null) {
            if (currentDb.entryTemplatesGroup == existingTemplateGroup.id) {
                return KdbxResult.Failure(
                    IllegalStateException("模板分组已存在"),
                    strings.get(
                        R.string.repo_templates_already_installed,
                        VaultTemplateFactory.TEMPLATE_GROUP_NAME
                    )
                )
            }
            // 模板组已在、Meta 未登记 ⇒ 回填 EntryTemplatesGroup 使其对第三方工具可识别
            markTemplatesGroup(existingTemplateGroup.id)
            return persistSession()
        }
        // saveGroup 仅更新内存树（置 DIRTY），由 persistSession 统一序列化落盘并上传播结果
        val templateGroup = VaultTemplateFactory.buildTemplateGroup()
        databaseSession.saveGroup(templateGroup)
        markTemplatesGroup(templateGroup.id)
        return persistSession()
    }

    /** 将模板组 UUID 写入 Meta（`EntryTemplatesGroup` + 变更时间戳，官方 KDBX 4 语义）。 */
    private suspend fun markTemplatesGroup(groupId: KdbxUuid) {
        databaseSession.updateDatabaseMeta { db ->
            db.copy(
                entryTemplatesGroup = groupId,
                entryTemplatesGroupChanged = Instant.now()
            )
        }
    }

    private companion object {
        /** ISSUE-P3-550：异常细节（含文件路径）只进日志，UI 侧只留错误码映射。 */
        const val TAG = "VaultExportCoordinator"
    }
}
