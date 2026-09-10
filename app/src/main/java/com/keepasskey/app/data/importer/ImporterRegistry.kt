package com.keepasskey.app.data.importer

import com.keepasskey.app.data.logger.DebugLogBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 解析器注册表（ISSUE-P3-19 交付物 1.2）：按 [ImportSource] 或文件扩展名分发。
 *
 * **扩展方式（与并行子批的零冲突契约）**：本表不硬编码任何具体解析器类名，完全依赖 Hilt 的
 * `@IntoSet` 多绑定集合 `Set<EntryImporter>`。本批次通过 [ImporterModule] 贡献
 * `KEEPASS_XML` 与 `BROWSER_CSV` 两个实现；Bitwarden JSON 与 1Password 1PUX 由另一子批
 * 以同样方式追加自己的 `@IntoSet` 绑定即可自动生效——双方无需改动对方文件。
 *
 * **缺失解析器时 fail-closed**：`find()` 返回 null，调用方（控制器）必须映射为明确的
 * `ImportFailureReason.SOURCE_UNAVAILABLE` 失败状态，**不得**静默成功（这正是 ISSUE-P3-03
 * 「假回执」的反面）。
 */
@Singleton
class ImporterRegistry @Inject constructor(
    importers: Set<@JvmSuppressWildcards EntryImporter>,
    private val debugLog: DebugLogBuffer
) {

    private val bySource: Map<ImportSource, EntryImporter> = buildIndex(importers)

    /** 按数据源取解析器；未注册返回 null。 */
    fun find(source: ImportSource): EntryImporter? = bySource[source]

    /** 全部已注册解析器支持的扩展名（小写、不含点），供 UI 选择器过滤。 */
    fun supportedExtensions(): Set<String> =
        bySource.values.flatMap { it.supportedExtensions }.map { it.lowercase() }.toSet()

    /** 按文件扩展名反查解析器；扩展名缺失或不识别返回 null。 */
    fun findForFileName(fileName: String): EntryImporter? {
        val extension = fileName.substringAfterLast(EXTENSION_SEPARATOR, "").lowercase()
        if (extension.isEmpty()) return null
        return bySource.values.firstOrNull { extension in it.supportedExtensions }
    }

    /**
     * 构建索引：同一 [ImportSource] 被绑定多次属装配缺陷，此时**保留排序后的首个**并落警告
     * （不抛异常——DI 构造期抛异常会让整个 App 起不来，代价远高于收益；但绝不静默）。
     */
    private fun buildIndex(importers: Set<EntryImporter>): Map<ImportSource, EntryImporter> {
        val grouped = importers.sortedBy { it::class.java.name }.groupBy { it.source }
        grouped.filterValues { it.size > 1 }.forEach { (source, duplicated) ->
            debugLog.warn(TAG, "数据源 ${source.id} 注册了多个解析器，保留首个: " +
                duplicated.map { it::class.java.simpleName })
        }
        return grouped.mapValues { it.value.first() }
    }

    private companion object {
        const val TAG = "ImporterRegistry"
        const val EXTENSION_SEPARATOR = '.'
    }
}
