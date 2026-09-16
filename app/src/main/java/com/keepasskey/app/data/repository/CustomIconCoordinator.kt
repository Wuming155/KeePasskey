package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * 库级自定义图标管理通道（ISSUE-P3-02 / TASK-49）。
 *
 * 抽象置于数据层，UI（ViewModel）依赖本接口而非内部协调器实现，
 * 避免把 `DatabaseSession` 与落盘细节上浮到界面层。
 */
interface CustomIconAdmin {

    /**
     * 删除库内指定自定义图标，并把所有引用该图标的条目回退为默认图标。
     *
     * 幂等语义：图标已不在池中（其他端已删除而引用残留）时仍执行引用清理，
     * 返回实际回退的条目数量；图标 id 非法、库未打开或会话只读时返回 [KdbxResult.Failure]。
     */
    suspend fun deleteCustomIcon(iconIdHex: String): KdbxResult<Int>
}

/**
 * KDBX 自定义图标协调器（TASK-15，拆分自 RealVaultRepository 职责）。
 *
 * 自定义图标存储于库 Meta `CustomIcons`（KDBX 2.x 规范），条目经 `CustomIconUUID`
 * 引用；PNG 字节随库文件同步，KeePass 2.x / KeePassXC 互认。
 * 上传侧防线：PNG 魔数校验（fail-closed 拒绝非 PNG）与单图体积上限；
 * 相同字节内容去重，避免图标池无谓膨胀。
 * 删除侧（TASK-49）：先清 Meta 图标池条目，再回退全部引用条目为默认图标，最后整体落盘。
 */
internal class CustomIconCoordinator(
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val persistSession: suspend () -> KdbxResult<Unit>
) : CustomIconAdmin {

    /**
     * 上传 PNG 字节为库级自定义图标，返回其 UUID（hex）。
     * 内容去重：与既有图标字节完全一致时直接返回既有 UUID，不重复落盘。
     */
    suspend fun addCustomIcon(pngBytes: ByteArray): KdbxResult<String> {
        if (!isPngBytes(pngBytes)) {
            return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.icon_invalid_not_png)),
                strings.get(R.string.icon_invalid_not_png)
            )
        }
        if (pngBytes.size > MAX_ICON_BYTES) {
            return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.icon_too_large)),
                strings.get(R.string.icon_too_large)
            )
        }
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        if (databaseSession.isReadOnly) {
            return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.readonly_save_rejected)),
                strings.get(R.string.readonly_save_rejected)
            )
        }

        val duplicate = db.customIcons.firstOrNull { it.data.contentEquals(pngBytes) }
        if (duplicate != null) {
            return KdbxResult.Success(duplicate.uuid.toHexString())
        }

        val icon = CustomIcon(uuid = KdbxUuid.random(), data = pngBytes.copyOf())
        databaseSession.updateDatabaseMeta { current ->
            current.copy(customIcons = current.customIcons + icon)
        }
        val persistResult = persistSession()
        return when (persistResult) {
            is KdbxResult.Success -> KdbxResult.Success(icon.uuid.toHexString())
            is KdbxResult.Failure -> persistResult
        }
    }

    /**
     * 删除自定义图标并回退引用条目（ISSUE-P3-02）。
     *
     * 单次元数据事务内完成「图标池移除 + 全树引用清空」，再统一走会话落盘：
     * 任一环节失败都向上返回 Failure，绝不静默吞掉——否则会出现「界面已回退默认图标、
     * 库文件里图标与引用仍在」的假成功。
     */
    override suspend fun deleteCustomIcon(iconIdHex: String): KdbxResult<Int> {
        val targetIcon = parseKdbxUuidOrNull(iconIdHex)
            ?: return KdbxResult.Failure(
                IllegalArgumentException(INVALID_ICON_ID),
                strings.get(R.string.vault_icon_delete_failed)
            )
        databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        if (databaseSession.isReadOnly) {
            return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.readonly_save_rejected)),
                strings.get(R.string.readonly_save_rejected)
            )
        }

        var revertedEntries = 0
        databaseSession.updateDatabaseMeta { current ->
            val (updatedRoot, reverted) = CustomIconRefs.clearReferences(current.rootGroup, targetIcon)
            revertedEntries = reverted
            current.copy(
                customIcons = current.customIcons.filterNot { it.uuid == targetIcon },
                rootGroup = updatedRoot
            )
        }
        return when (val persistResult = persistSession()) {
            is KdbxResult.Success -> KdbxResult.Success(revertedEntries)
            is KdbxResult.Failure -> persistResult
        }
    }

    /** 库内自定义图标池快照（UUID hex → PNG 字节），供 UI 解码渲染 */
    fun snapshotIconBytes(db: com.keepasskey.database.file.KdbxDatabase): Map<String, ByteArray> =
        db.customIcons.associate { it.uuid.toHexString() to it.data }

    companion object {
        /** PNG 文件魔数：\x89PNG\r\n\x1a\n */
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        /** 单个图标体积上限：KDBX 生态约定图标为小尺寸 PNG（KeePassXC 默认 128px） */
        const val MAX_ICON_BYTES: Int = 256 * 1024

        /** 非法图标 id 的内部异常标识（不含用户数据，用户可见文案走资源） */
        private const val INVALID_ICON_ID = "自定义图标 UUID 非法"

        private fun isPngBytes(bytes: ByteArray): Boolean =
            bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
    }
}

/**
 * 图标引用回退（纯函数，无会话/IO 依赖，可 JVM 直测）。
 *
 * 语义对齐 KeePass 2.x「删除自定义图标」：图标从 Meta 图标池移除后，所有
 * `CustomIconUUID` 指向它的条目与分组回退为标准图标（引用置空）。
 * 未变更的子树按引用原样返回，避免整树无谓拷贝（copy-on-write 友好）。
 */
internal object CustomIconRefs {

    /**
     * 把 [group] 子树中所有指向 [iconId] 的引用置空。
     * @return 更新后的树（无变更时返回原实例）与**被回退的条目数量**（分组引用不计入）
     */
    fun clearReferences(group: KdbxGroup, iconId: KdbxUuid): Pair<KdbxGroup, Int> {
        var revertedEntries = 0

        fun clearInGroup(current: KdbxGroup): KdbxGroup {
            var changed = false
            val updatedEntries = current.entries.map { entry ->
                if (entry.customIconId == iconId) {
                    revertedEntries++
                    changed = true
                    entry.copy(customIconId = null)
                } else {
                    entry
                }
            }
            val updatedSubgroups = current.subgroups.map { subgroup ->
                val updated = clearInGroup(subgroup)
                if (updated !== subgroup) changed = true
                updated
            }
            val self = if (current.customIconId == iconId) {
                changed = true
                current.copy(customIconId = null)
            } else {
                current
            }
            return if (changed) self.copy(entries = updatedEntries, subgroups = updatedSubgroups) else current
        }

        return clearInGroup(group) to revertedEntries
    }
}

/**
 * ISSUE-P3-02：UI 层（ViewModel）的图标管理注入通道。
 *
 * 删除图标必须有库会话与落盘能力，而 `VaultRepository` 现有契约不含该写操作；
 * 本模块在数据层导出 [CustomIconAdmin]（不修改仓库接口，避免影响其他并行组）。
 * 协调器实例与 `RealVaultRepository` 内部实例共享同一 [DatabaseSession] 单例，
 * 且自身无状态，重复装配安全。
 */
@Module
@InstallIn(SingletonComponent::class)
object CustomIconModule {

    @Provides
    @Singleton
    fun provideCustomIconAdmin(
        strings: StringsProvider,
        databaseSession: DatabaseSession
    ): CustomIconAdmin = CustomIconCoordinator(strings, databaseSession) { databaseSession.save() }
}
