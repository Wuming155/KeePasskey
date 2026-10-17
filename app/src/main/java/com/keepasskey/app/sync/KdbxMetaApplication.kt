package com.keepasskey.app.sync

import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.sync.merge.KdbxMetaLite

/**
 * `ISSUE-P2-659`：库级 Meta 镜像与「合并产物 → 库」的应用（漂移 / 同步 / 导入三路径共用）。
 *
 * 为什么单独成文件：`KdbxMetaLite` 定义在 sync 模块（不依赖 database 模块），
 * `KdbxDatabase` 在 database 模块；两边的字段映射必须有**唯一**实现，否则三条合并路径
 * 各自手抄一份字段清单，漏一个字段就是一次静默丢失。
 *
 * **身份字段不在映射面内**（`recycleBinUuid` / `entryTemplatesGroup` / `masterKey*` /
 * `lastSelectedGroup` / `lastTopVisibleGroup` / `generator` / `memoryProtection` / `header`）：
 * 这些恒取本地，见 [com.keepasskey.sync.merge.KdbxMetaMerge.IDENTITY_FIELDS_NOTE]。
 */

/** `KdbxDatabase` → Meta 合并镜像（仅可合并面）。 */
internal fun KdbxDatabase.toMetaLite(): KdbxMetaLite = KdbxMetaLite(
    databaseName = databaseName,
    databaseDescription = databaseDescription,
    defaultUserName = defaultUserName,
    color = color,
    customData = customData,
    historyMaxItems = historyMaxItems,
    historyMaxSize = historyMaxSize,
    maintenanceHistoryDays = maintenanceHistoryDays,
    databaseNameChanged = databaseNameChanged,
    databaseDescriptionChanged = databaseDescriptionChanged,
    defaultUserNameChanged = defaultUserNameChanged,
    settingsChanged = settingsChanged
)

/**
 * 采用合并后的 Meta；[meta] 为 null（调用方未提供 Meta 镜像，如 `.kdbx` 导入）时原样返回，
 * 即「Meta 保持本地」——不构造第二套默认值，避免把「不合并」误实现成「写回默认值」。
 */
internal fun KdbxDatabase.withMergedMeta(meta: KdbxMetaLite?): KdbxDatabase {
    if (meta == null) return this
    return copy(
        databaseName = meta.databaseName,
        databaseDescription = meta.databaseDescription,
        defaultUserName = meta.defaultUserName,
        color = meta.color,
        customData = meta.customData,
        historyMaxItems = meta.historyMaxItems,
        historyMaxSize = meta.historyMaxSize,
        maintenanceHistoryDays = meta.maintenanceHistoryDays,
        databaseNameChanged = meta.databaseNameChanged,
        databaseDescriptionChanged = meta.databaseDescriptionChanged,
        defaultUserNameChanged = meta.defaultUserNameChanged,
        settingsChanged = meta.settingsChanged
    )
}
