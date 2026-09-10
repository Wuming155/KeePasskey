package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 分组协调器（ISSUE-P3-31 批次 B 自 `RealVaultRepository` 拆出，纯结构性改动）。
 *
 * 职责单一：KDBX 分组树的 UI 投影（含回收站识别与自定义图标投影）与分组保存。
 *
 * 分组保存沿用 P0-1 修复语义：重命名/改图标**必须**基于会话内既有分组 `copy`，
 * 严禁以新建空分组覆盖既有分组（否则其全部子条目与子分组被清空）。
 */
internal class VaultGroupCoordinator(
    private val databaseSession: DatabaseSession,
    private val entryMapper: VaultEntryMapper,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    /** 分组树的 UI 投影流：会话为空时仅给出回收站占位分组 */
    fun groupsFlow(): Flow<List<VaultGroup>> {
        return databaseSession.databaseFlow.map { db ->
            if (db == null) {
                listOf(RealVaultRepository.RECYCLE_BIN_GROUP)
            } else {
                val binUuid = db.recycleBinUuid
                val allKdbxGroups = db.rootGroup.allGroups()
                allKdbxGroups.map { kdbxGroup ->
                    val isRecycle = (binUuid != null && kdbxGroup.id == binUuid) ||
                            kdbxGroup.name == RealVaultRepository.RECYCLE_BIN_NAME ||
                            kdbxGroup.name.equals("Recycle Bin", ignoreCase = true)
                    VaultGroup(
                        id = kdbxGroup.id.toHexString(),
                        name = kdbxGroup.name,
                        parentId = kdbxGroup.parentGroupId?.toHexString(),
                        // ISSUE-P3-22：分组自定义图标（KdbxGroup.CustomIconUUID → hex 投影）。
                        // 与条目侧 UiVaultEntry.customIconId 同形态，供 KeePassGroupRow 渲染，
                        // 并与条目共用同一 IconBitmapCache（避免同一图标重复解码）。
                        customIconId = kdbxGroup.customIconId?.toHexString(),
                        iconName = if (isRecycle || kdbxGroup.iconId == RealVaultRepository.ICON_TRASH_BIN) "delete" else "folder",
                        updatedAt = entryMapper.formatInstant(kdbxGroup.times.lastModificationTime),
                        createdAt = entryMapper.formatInstant(kdbxGroup.times.creationTime),
                        isRecycleBin = isRecycle
                    )
                }
            }
        }
    }

    suspend fun saveGroup(group: VaultGroup): KdbxResult<Unit> {
        val targetId = parseKdbxUuidOrRandom(group.id)
        // P0-1 灾难性缺陷修复：重命名/改图标路径曾以仅含 4 个字段的新建 KdbxGroup 直接
        // 覆盖既有分组（entries/subgroups 均为默认空列表），导致其全部子条目与子分组被清空。
        // 现先按 UUID 从会话中查找既有分组：命中则基于 existing.copy(...) 仅更新名称/图标/父组，
        // 子条目与子分组原样保留；未命中（真正的新建分组）才构造空分组。
        val existing = databaseSession.databaseFlow.first()
            ?.rootGroup
            ?.findGroup(targetId)
        val kdbxGroup = if (existing != null) {
            existing.copy(
                name = group.name,
                // 断点7 整改：分组图标按名称映射到 KDBX 标准图标 ID（回收站强制 43 TrashBin）；
                // 未知名回退既有图标，避免重命名时把已设置的图标意外重置为默认文件夹
                iconId = if (group.isRecycleBin) RealVaultRepository.ICON_TRASH_BIN
                else entryMapper.mapIconNameToId(group.iconName, fallbackId = existing.iconId),
                // parentId 缺失时保留既有父组，防止空 parentId 把嵌套分组意外改挂到根组
                parentGroupId = group.parentId?.let { parseKdbxUuidOrNull(it) } ?: existing.parentGroupId
            )
        } else {
            KdbxGroup(
                id = targetId,
                parentGroupId = group.parentId?.let { parseKdbxUuidOrNull(it) },
                name = group.name,
                iconId = if (group.isRecycleBin) {
                    RealVaultRepository.ICON_TRASH_BIN
                } else {
                    entryMapper.mapIconNameToId(group.iconName, fallbackId = RealVaultRepository.ICON_FOLDER)
                }
            )
        }
        databaseSession.saveGroup(kdbxGroup)
        return persistSession()
    }
}
