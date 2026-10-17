package com.keepasskey.sync.merge

import java.time.Instant

/**
 * `ISSUE-P2-659`：库级 Meta 的三方合并面。
 *
 * 为什么需要独立模型：合并器此前只产出「树 / 墓碑 / 图标池」，库级 Meta（库名 / 描述 /
 * 默认用户名 / 颜色 / 自定义数据 / 历史维护参数）一律取本地。外部工具（KeePassXC / 官方
 * 桌面端）对这些字段的改动，在「合并后保存」时被**永久覆盖且无任何提示**。
 *
 * **身份字段不参与合并**（见 [KdbxMetaMerge.IDENTITY_FIELDS_NOTE]）：加密头 / KDF、回收站与
 * 模板组的 UUID 归属、主密钥策略、上次选中分组、`generator`、图标池与二进制池、字段保护配置
 * ——这些要么是「本机身份」，要么已有独立合并面，做标量三方合并会产生不可解释的语义
 * （例如把回收站 UUID 换成指向对端不存在的分组）。
 *
 * @property databaseNameChanged 等时间戳参与裁决：KDBX 元数据本就带「该字段何时改过」，
 *   双侧异值时以较新者为准属于协议自身语义，而不是无据的 LWW。
 */
data class KdbxMetaLite(
    val databaseName: String,
    val databaseDescription: String,
    val defaultUserName: String,
    val color: String,
    val customData: Map<String, String>,
    val historyMaxItems: Int,
    val historyMaxSize: Long,
    val maintenanceHistoryDays: Int,
    val databaseNameChanged: Instant? = null,
    val databaseDescriptionChanged: Instant? = null,
    val defaultUserNameChanged: Instant? = null,
    val settingsChanged: Instant? = null
)

/** `ISSUE-P2-659`：Meta 字段差异键的单一词汇表（产出侧与消费侧共用，禁止字面量对拍）。 */
object KdbxMetaConflictKeys {
    const val NAME = "meta.name"
    const val DESCRIPTION = "meta.description"
    const val DEFAULT_USER_NAME = "meta.defaultUserName"
    const val COLOR = "meta.color"
    const val HISTORY_MAX_ITEMS = "meta.historyMaxItems"
    const val HISTORY_MAX_SIZE = "meta.historyMaxSize"
    const val MAINTENANCE_HISTORY_DAYS = "meta.maintenanceHistoryDays"

    /** Meta 级 customData 差异键前缀：实际键 = 本前缀 + 自定义键名 */
    const val CUSTOM_DATA_PREFIX = "meta.customData:"

    fun customDataKey(key: String): String = CUSTOM_DATA_PREFIX + key
}

/**
 * `ISSUE-P2-659`：单个 Meta 字段的双侧异值（未能在三方合并中自动定案）。
 *
 * 标量字段没有「双方保留」语义，因此裁决词汇只有「取本地 / 取云端」；两侧值均为非敏感的
 * 库元数据，可直接进展示面（`customData` 亦为公开元数据，不含条目秘密）。
 */
data class MetaFieldConflict(
    val fieldKey: String,
    val localValue: String,
    val remoteValue: String
)

/** `ISSUE-P2-659`：Meta 合并产物——合并后的值 + 无法自动定案的字段清单。 */
data class MergedMeta(
    val meta: KdbxMetaLite,
    val conflicts: List<MetaFieldConflict>
)

/**
 * `ISSUE-P2-659`：库级 Meta 的字段级三方合并。
 *
 * 规则（逐字段，与条目 / 分组单字段三方合并同构）：
 * 1. 两侧同值 ⇒ 该值（无冲突）；
 * 2. 一侧等于 base、另一侧不同 ⇒ 取**变更侧**（单侧修改自动合入，这是外部工具改动不再丢失的关键）；
 * 3. 双侧都改且异值 ⇒ 按该字段的元数据时间戳裁决（较新者胜；一方缺失时间戳视为更旧）；
 * 4. 时间戳相同 / 均缺失 ⇒ **登记冲突**并临时取本地，由调用方决定阻断待裁决还是按策略取一侧。
 *
 * `base == null`（无可信基线，漂移面空底版语义）时退化为「双方并集 / 单侧可判」：
 * 两侧同值取该值，异值走第 3、4 条。
 */
object KdbxMetaMerge {

    /**
     * 身份字段清单（**不合并，恒取本地**）。登记在此是为了让「为什么不合并」有唯一可审点：
     * 新增 Meta 字段时必须先判定它属于「可合并设置」还是「本机身份」。
     *
     * - 加密头 / KDF 参数：与库文件本身绑定，合并无意义且会破坏可打开性；
     * - `recycleBinUuid` / `recycleBinEnabled` / `entryTemplatesGroup`：指向本库树内对象的身份引用，
     *   对端 UUID 在本库可能不存在（或指向不同分组）；
     * - `masterKeyChanged` / `masterKeyChangeRec` / `masterKeyChangeForce` / `masterKeyChangeForceOnce`：
     *   主密钥策略属本机安全设置；
     * - `lastSelectedGroup` / `lastTopVisibleGroup`：界面状态；
     * - `generator`：库文件生成器标识；
     * - `memoryProtection`：字段保护配置，与条目读取语义绑定；
     * - `customIcons` / `binaries`：已有独立合并面（`mergeCustomIcons` / 二进制池按内容指纹去重）。
     */
    const val IDENTITY_FIELDS_NOTE: String =
        "header, recycleBin*, entryTemplatesGroup*, masterKey*, lastSelectedGroup, lastTopVisibleGroup, " +
            "generator, memoryProtection, customIcons, binaries"

    /**
     * 三方合并库级 Meta。
     *
     * @param base 可信共同基线；null 表示无可信基线（按双方并集语义处理，不推断删除）
     */
    fun merge(base: KdbxMetaLite?, local: KdbxMetaLite, remote: KdbxMetaLite): MergedMeta {
        val conflicts = mutableListOf<MetaFieldConflict>()

        fun text(key: String, l: String, r: String, b: String?, lTime: Instant?, rTime: Instant?): String =
            resolveScalar(key, l, r, b, lTime, rTime, conflicts)

        val name = text(
            KdbxMetaConflictKeys.NAME,
            local.databaseName,
            remote.databaseName,
            base?.databaseName,
            local.databaseNameChanged,
            remote.databaseNameChanged
        )
        val description = text(
            KdbxMetaConflictKeys.DESCRIPTION,
            local.databaseDescription,
            remote.databaseDescription,
            base?.databaseDescription,
            local.databaseDescriptionChanged,
            remote.databaseDescriptionChanged
        )
        val defaultUserName = text(
            KdbxMetaConflictKeys.DEFAULT_USER_NAME,
            local.defaultUserName,
            remote.defaultUserName,
            base?.defaultUserName,
            local.defaultUserNameChanged,
            remote.defaultUserNameChanged
        )
        val color = text(
            KdbxMetaConflictKeys.COLOR,
            local.color,
            remote.color,
            base?.color,
            local.settingsChanged,
            remote.settingsChanged
        )
        val historyMaxItems = text(
            KdbxMetaConflictKeys.HISTORY_MAX_ITEMS,
            local.historyMaxItems.toString(),
            remote.historyMaxItems.toString(),
            base?.historyMaxItems?.toString(),
            local.settingsChanged,
            remote.settingsChanged
        ).toIntOrNull() ?: local.historyMaxItems
        val historyMaxSize = text(
            KdbxMetaConflictKeys.HISTORY_MAX_SIZE,
            local.historyMaxSize.toString(),
            remote.historyMaxSize.toString(),
            base?.historyMaxSize?.toString(),
            local.settingsChanged,
            remote.settingsChanged
        ).toLongOrNull() ?: local.historyMaxSize
        val maintenanceDays = text(
            KdbxMetaConflictKeys.MAINTENANCE_HISTORY_DAYS,
            local.maintenanceHistoryDays.toString(),
            remote.maintenanceHistoryDays.toString(),
            base?.maintenanceHistoryDays?.toString(),
            local.settingsChanged,
            remote.settingsChanged
        ).toIntOrNull() ?: local.maintenanceHistoryDays

        val customData = mergeCustomData(base?.customData, local.customData, remote.customData, conflicts)

        return MergedMeta(
            meta = KdbxMetaLite(
                databaseName = name,
                databaseDescription = description,
                defaultUserName = defaultUserName,
                color = color,
                customData = customData,
                historyMaxItems = historyMaxItems,
                historyMaxSize = historyMaxSize,
                maintenanceHistoryDays = maintenanceDays,
                // 时间戳随取值侧一并采用：取远端值时若仍保留本地时间戳，下次合并会把「本地更旧」
                // 误判成「远端更新」。同值场景取较晚者（元数据无实质差异，仅对齐口径）。
                databaseNameChanged = pickChanged(name == remote.databaseName, local.databaseNameChanged, remote.databaseNameChanged),
                databaseDescriptionChanged = pickChanged(
                    description == remote.databaseDescription,
                    local.databaseDescriptionChanged,
                    remote.databaseDescriptionChanged
                ),
                defaultUserNameChanged = pickChanged(
                    defaultUserName == remote.defaultUserName,
                    local.defaultUserNameChanged,
                    remote.defaultUserNameChanged
                ),
                settingsChanged = pickChanged(false, local.settingsChanged, remote.settingsChanged)
            ),
            conflicts = conflicts
        )
    }

    /**
     * `ISSUE-P2-659`（裁决）：按字段级选择重裁 Meta 冲突字段。
     *
     * 以合并产物 [merged] 为底版，只改 [conflicts] 中用户钦点的字段；`DELETE_REMOTE` 对标量
     * 无对应语义，按「保持合并产物」处理（调用方 UI 不提供该选项）。
     */
    fun resolveFieldsOnly(
        merged: KdbxMetaLite,
        conflicts: List<MetaFieldConflict>,
        choices: Map<String, FieldResolutionChoice>
    ): KdbxMetaLite {
        var result = merged
        for (conflict in conflicts) {
            when (choices[conflict.fieldKey]) {
                FieldResolutionChoice.KEEP_REMOTE -> result = adoptRemote(result, conflict)
                FieldResolutionChoice.KEEP_LOCAL, FieldResolutionChoice.DELETE_REMOTE, null -> Unit
            }
        }
        return result
    }

    private fun adoptRemote(meta: KdbxMetaLite, conflict: MetaFieldConflict): KdbxMetaLite = when {
        conflict.fieldKey == KdbxMetaConflictKeys.NAME -> meta.copy(databaseName = conflict.remoteValue)
        conflict.fieldKey == KdbxMetaConflictKeys.DESCRIPTION ->
            meta.copy(databaseDescription = conflict.remoteValue)
        conflict.fieldKey == KdbxMetaConflictKeys.DEFAULT_USER_NAME ->
            meta.copy(defaultUserName = conflict.remoteValue)
        conflict.fieldKey == KdbxMetaConflictKeys.COLOR -> meta.copy(color = conflict.remoteValue)
        conflict.fieldKey == KdbxMetaConflictKeys.HISTORY_MAX_ITEMS ->
            meta.copy(historyMaxItems = conflict.remoteValue.toIntOrNull() ?: meta.historyMaxItems)
        conflict.fieldKey == KdbxMetaConflictKeys.HISTORY_MAX_SIZE ->
            meta.copy(historyMaxSize = conflict.remoteValue.toLongOrNull() ?: meta.historyMaxSize)
        conflict.fieldKey == KdbxMetaConflictKeys.MAINTENANCE_HISTORY_DAYS ->
            meta.copy(
                maintenanceHistoryDays = conflict.remoteValue.toIntOrNull() ?: meta.maintenanceHistoryDays
            )
        conflict.fieldKey.startsWith(KdbxMetaConflictKeys.CUSTOM_DATA_PREFIX) -> {
            val key = conflict.fieldKey.removePrefix(KdbxMetaConflictKeys.CUSTOM_DATA_PREFIX)
            meta.copy(customData = meta.customData + (key to conflict.remoteValue))
        }
        // 未登记键：保持合并产物（不得因未知键退化为整体替换）
        else -> meta
    }

    /**
     * 单字段三方裁决（返回取值；双侧异值且无法按时间戳定案时登记冲突并取本地）。
     *
     * 时间戳口径：一方缺失视为「更旧」（KDBX 4.0 库无该字段）；两侧相同则无法区分先后 ⇒ 冲突。
     */
    private fun resolveScalar(
        key: String,
        local: String,
        remote: String,
        base: String?,
        localTime: Instant?,
        remoteTime: Instant?,
        conflicts: MutableList<MetaFieldConflict>
    ): String {
        if (local == remote) return local
        if (base != null && local == base) return remote
        if (base != null && remote == base) return local
        val remoteNewer = when {
            localTime == null && remoteTime == null -> null
            localTime == null -> true
            remoteTime == null -> false
            remoteTime.isAfter(localTime) -> true
            localTime.isAfter(remoteTime) -> false
            else -> null
        }
        return if (remoteNewer == null) {
            conflicts += MetaFieldConflict(fieldKey = key, localValue = local, remoteValue = remote)
            local
        } else if (remoteNewer) {
            remote
        } else {
            local
        }
    }

    /**
     * Meta 级 customData 键级三方合并（与组级 `mergeCustomDataMap` 同口径）：
     * 键并集；单侧修改取该侧；双侧异值登记冲突并取本地；无逐键时间戳可裁。
     */
    private fun mergeCustomData(
        base: Map<String, String>?,
        local: Map<String, String>,
        remote: Map<String, String>,
        conflicts: MutableList<MetaFieldConflict>
    ): Map<String, String> {
        if (local == remote) return local
        val baseMap = base.orEmpty()
        val keys = baseMap.keys + local.keys + remote.keys
        val merged = LinkedHashMap<String, String>()
        for (key in keys) {
            val bv = baseMap[key]
            val lv = local[key]
            val rv = remote[key]
            val winner = when {
                lv == rv -> lv
                base != null && lv == bv -> rv
                base != null && rv == bv -> lv
                // 无底版（base 缺失或双方均为新增）：单侧存在的键按并集保留，不推断删除
                bv == null && lv == null -> rv
                bv == null && rv == null -> lv
                else -> {
                    conflicts += MetaFieldConflict(
                        fieldKey = KdbxMetaConflictKeys.customDataKey(key),
                        localValue = lv.orEmpty(),
                        remoteValue = rv.orEmpty()
                    )
                    lv
                }
            }
            if (winner != null) merged[key] = winner
        }
        return merged
    }

    /**
     * 时间戳随取值侧对齐：取值来自远端时取远端时间戳，否则取两者较晚者（同值场景）。
     * 本地侧为 null 而远端非 null 时取远端（避免把「远端改过」记成本地未知）。
     */
    private fun pickChanged(fromRemote: Boolean, local: Instant?, remote: Instant?): Instant? = when {
        fromRemote -> remote ?: local
        local == null -> remote
        remote == null -> local
        else -> if (remote.isAfter(local)) remote else local
    }
}
