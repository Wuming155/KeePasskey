package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.file.KdbxDatabase
import java.time.Instant

/**
 * KDBX 4.1 特征谓词的**单一真源**与最小版本判定（ISSUE-P3-367）。
 *
 * 背景：写侧的 4.1 专有条件元素（SettingsChanged / MasterKeyChangeForceOnce /
 * CustomIcon Name·LastModificationTime / CustomData Item LastModificationTime /
 * Entry QualityCheck / Tags / PreviousParentGroup）与外层 Header 的 `version`
 * 必须同源——「按需升版、尽量保持 4.0」，对齐官方 KeePass `KdbxFile.GetMinKdbxVersion`
 * （`KdbxFile.cs:351`）与 KeePassDX `getMinKdbxVersion` 的姿态，既不
 * 「声明 4.0、夹带 4.1」，也不无谓抬高互操作下限。
 *
 * **同源防漂移契约**：下列谓词是对应序列化条件的唯一真源——
 * [KdbxXmlMetaSerializer] / [KdbxXmlEntrySerializer] / [KdbxXmlGroupSerializer] 的
 * 条件写出分支与 [resolveMinVersion] **必须调用同一谓词**；新增 4.1 条件写出点时
 * 两侧须同批挂接，回归由 `KdbxMinVersionResolutionTest` 的「判定 ⇔ 序列化」对拍锁定。
 *
 * 谓词形态约定：
 * - 布尔型 `writesXxx(...)`：序列化侧 `if` 与判定侧共用同一布尔；
 * - 取值型 `xxxToWrite(...)`：返回待写出的值（null/空＝不写），序列化侧以
 *   `?.let` 消费（保持可空安全），判定侧以 `!= null` 消费——写出条件随函数体单点变更。
 */
internal object KdbxVersion41Features {

    /** Meta `<SettingsChanged>`：仅非 null 写出（返回待写值，null＝不写）。 */
    fun settingsChangedToWrite(value: Instant?): Instant? = value

    /** Meta `<MasterKeyChangeForceOnce>`：官方仅在为 true 时写出（Write.cs:461-462）。 */
    fun writesMasterKeyChangeForceOnce(forceOnce: Boolean): Boolean = forceOnce

    /** 自定义图标 4.1 追加 `<Name>`：仅非空写出（Write.cs:697-703）。 */
    fun writesCustomIconName(icon: CustomIcon): Boolean = icon.name.isNotEmpty()

    /** 自定义图标 4.1 追加 `<LastModificationTime>`：仅非 null 写出（返回待写值）。 */
    fun customIconTimeToWrite(icon: CustomIcon): Instant? = icon.lastModificationTime

    /**
     * Meta CustomData Item 的 4.1 追加 `<LastModificationTime>`：
     * 仅该键在 [KdbxMetaData.customDataTimes] 中有时间戳时写出（返回待写值）。
     */
    fun customDataTimeToWrite(meta: KdbxMetaData, key: String): Instant? = meta.customDataTimes[key]

    /** 条目 `<QualityCheck>`：仅在为 false 时写出（Write.cs:540-541）。 */
    fun writesQualityCheck(entry: KdbxEntry): Boolean = !entry.qualityCheck

    /** `<PreviousParentGroup>`（条目与分组共用）：仅非 null 写出（返回待写值）。 */
    fun previousParentGroupToWrite(value: KdbxUuid?): KdbxUuid? = value

    /** `<Tags>`（条目与分组共用）：仅非空写出。 */
    fun writesTags(tags: List<String>): Boolean = tags.isNotEmpty()

    /**
     * 按**待写内容**计算最小 KDBX 版本（ISSUE-P3-367；`KdbxFile.save` 组装
     * `updatedHeader` 处的唯一决策点，新建与保存同路径）。
     *
     * - 任一 4.1 特征存在 → [KdbxConstants.Version.VERSION_4_1]；
     * - 否则 → [KdbxConstants.Version.VERSION_4_0]——含既有 4.1 库在无 4.1 特征时保存
     *   **回退** 4.0，与官方 `GetMinKdbxVersion` 行为一致。
     *
     * 头部侧：[KdbxDatabase.header] 的 `publicCustomData` 为 4.1 专有 HeaderId，
     * 其写出条件（与 `KdbxHeader.serialize` 字段 12 同为 `!= null` 判定）一并纳入。
     */
    fun resolveMinVersion(database: KdbxDatabase): Int =
        if (has41Feature(database)) {
            KdbxConstants.Version.VERSION_4_1
        } else {
            KdbxConstants.Version.VERSION_4_0
        }

    private fun has41Feature(database: KdbxDatabase): Boolean =
        database.header.publicCustomData != null ||
            has41Feature(database.toMetaData()) ||
            has41Feature(database.rootGroup)

    private fun has41Feature(meta: KdbxMetaData): Boolean =
        settingsChangedToWrite(meta.settingsChanged) != null ||
            writesMasterKeyChangeForceOnce(meta.masterKeyChangeForceOnce) ||
            meta.customIcons.any { writesCustomIconName(it) || customIconTimeToWrite(it) != null } ||
            meta.customData.any { (key, _) -> customDataTimeToWrite(meta, key) != null }

    private fun has41Feature(group: KdbxGroup): Boolean =
        writesTags(group.tags) ||
            previousParentGroupToWrite(group.previousParentGroup) != null ||
            group.entries.any { has41Feature(it) } ||
            group.subgroups.any { has41Feature(it) }

    /**
     * 条目（含历史快照）特征扫描。镜像序列化器：历史条目自身的 4.1 元素照常写出
     * （计入），但历史条目**嵌套的 history 不再写出**（`isHistory` 守卫，不计入）。
     */
    private fun has41Feature(entry: KdbxEntry, isHistory: Boolean = false): Boolean =
        writesTags(entry.tags) ||
            writesQualityCheck(entry) ||
            previousParentGroupToWrite(entry.previousParentGroup) != null ||
            (!isHistory && entry.history.any { has41Feature(it, isHistory = true) })
}
