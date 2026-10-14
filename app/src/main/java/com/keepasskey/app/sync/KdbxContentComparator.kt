package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader

/**
 * 会话库「内容是否变化」的**纯比较**（`ISSUE-P2-91`：自 [SyncContentChangeDetector] 抽出以便直测，
 * 原为三个 private 函数，逻辑逐字搬运）。
 *
 * **本比较回答的问题**：`current` 相对 `reference`（内存基线 / 缓存快照）是否存在
 * **需要重新序列化并上传**的内容变更——调用方在判「无变化」时会**直接复用旧缓存字节**上传
 * （见 `SyncCycleRunner`），故漏判的后果是「本地编辑不上云」；多判的后果是
 * 「无意义重传 + 前移远端 ETag」（多设备协同噪声）。
 *
 * ## 口径（刻意如此，勿「顺手对齐」）
 *
 * 本比较与合并侧的 [com.keepasskey.sync.merge.KdbxEntryMerger.isModified] **不是同一个问题**，
 * 两者口径**刻意不同**且各自写在自己的 KDoc 里：
 *
 * - **合并侧**回答「条目相对 base 是否被修改」，用于冲突裁决，**必须**把
 *   `times.lastModificationTime` 计入（否则时间戳更新过的条目会被判成未改、不参与合并）；
 * - **本比较**回答「是否需要重新序列化上传」，**刻意不比 `times`**：`KdbxTimes` 同时承载
 *   **使用性**字段（`lastAccessTime` / `usageCount`）与 `lastModificationTime`，而
 *   `KdbxEntry.withField` 会经 `withModified()` 一次性改写三者 ⇒ 纳入比较会让「触碰但内容等同」
 *   被判成变更，触发重序列化 + 上传 + 前移远端 ETag，正是本检测器存在的理由
 *   （KDBX4 随机 IV 使重序列化字节必然漂移；F5 记录的原始动机）。
 *
 * ## 前提复核结论（`ISSUE-P2-91` AC 的「补齐 `times` 比较」**未照原样实施**）
 *
 * 2026-09-17 开工复核：**生产代码不存在只改 `times` 的编辑入口**——
 * `expires` / `expiryTime` 在全仓生产代码中**无任何写入者**（仅 `KdbxXmlTimesNode` 读取端与
 * `KdbxXmlTimeHelper` 默认值构造），`times` 的改动一律伴随 `fields`（`withField` 经 `withModified`）
 * 或 `customFields`（passkey 计数器补丁）变化，而两者本就在比较集内。
 * ⇒ 「仅 `times` 变化的本地编辑被静默丢弃」在既有可达面上**不成立**；
 * 该口径边界与解除条件登记于 `docs/architecture/已知工程限界.md` §10。
 *
 * ## `ISSUE-P2-596` 补齐（2026-10-14）：到期语义 / 库级 Meta / 头部语义 / 历史内容级
 *
 * 上述「仅 `times` 变化不成立」的前提随生产演进失效——编辑页已有**有效期**（`expires` /
 * `expiryTime`）写入口，故 PD-20 的重开条件成立。本比较相应补齐：
 * - 条目：以 [expiryChanged] 纳入**到期语义**（`expires` 翻转即变更；仅生效时比 `expiryTime`），
 *   仍**不**比较 `lastModificationTime` / `lastAccessTime` / `usageCount`；
 * - 条目：历史改为**内容级**比较 [historyChanged]（等条数原地改写同样判为变更）；
 * - 库级：以 [metaChanged] 纳入可编辑 Meta（名称 / 描述 / 默认用户名 / 回收站 / 模板组 /
 *   自定义图标 / MemoryProtection / Meta customData / 历史预算）；以 [headerSemanticChanged]
 *   纳入加密算法 / 压缩 / KDF 语义参数；
 * - **排除随机材料**：`masterSeed` / `encryptionIv` / KDF `salt` / `seed`（每次保存随机重生成，
 *   纳入即「无编辑也恒判变更」）与 KDF `secretKey` / `associatedData`（秘密，且其相等性刻意不敏感）。
 *
 * ## 已知不覆盖
 *
 * 库级 `binaries`（内层二进制池）不在此列——附件内容变化由条目 `attachments` 覆盖；
 * 若日后引入「只改池、不改条目」的路径，须同步改本处判据。
 *
 * ## 标准五字段按**内容**比较（`ISSUE-P3-542`；`ISSUE-P3-543` 起判据上收 `core`）
 *
 * 标准五字段（Title / UserName / Password / URL / Notes）的 `isProtected` **不参与序列化往返**：
 * 写侧 `KdbxXmlEntrySerializer.resolveProtectedFlag` 以**库级 MemoryProtection 无条件覆盖**
 * per-value 标志（对齐官方 `KdbxFile.Write.cs:838-854`），读侧从 `Protected="True"` 属性派生
 * ⇒ 同一内容的「内存构造实例」与「解析实例」可能标志不同却**不代表内容变化**。故本比较对
 * 标准五字段按**内容**比较（忽略该标志），**自定义 / 非标准字段保留 per-value 标志比较**
 * （对它们 per-value 才是被序列化的真值）。两种比较均不解密、不物化明文。
 *
 * **判据实现已上收 `core` 的 [KdbxEntry.fieldsContentEquals]**（`ISSUE-P3-543`）：合并侧
 * [com.keepasskey.sync.merge.KdbxEntryMerger.isModified] 与本节同引该函数，**禁止各写一份字段
 * 清单与比较方式**。`ProtectedString.equals` / [ProtectedString.contentEquals] 均以 HMAC
 * 等值标签比较字节内容，整字段比较不会物化明文密码。
 */
internal object KdbxContentComparator {

    /** `current` 相对 [reference] 是否存在内容变更（墓碑 + 库级 Meta / 头部语义 + 内容树） */
    fun changed(current: KdbxDatabase, reference: KdbxDatabase): Boolean {
        if (current.deletedObjects != reference.deletedObjects) return true
        if (metaChanged(current, reference)) return true
        if (headerSemanticChanged(current.header, reference.header)) return true
        return groupChanged(current.rootGroup, reference.rootGroup)
    }

    /**
     * 库级 Meta **可编辑语义**比较（ISSUE-P2-596）：名称 / 描述 / 默认用户名 / 回收站 /
     * 模板组 / 自定义图标 / MemoryProtection / Meta 级 customData / 历史预算。
     * 刻意排除 `*Changed` 时间戳与 `lastSelectedGroup` / `lastTopVisibleGroup`（会话 UI 定位）、
     * `generator`（常量）——这些不构成需重新上传的内容变更（理由见类 KDoc）。
     */
    fun metaChanged(a: KdbxDatabase, b: KdbxDatabase): Boolean =
        a.databaseName != b.databaseName ||
            a.databaseDescription != b.databaseDescription ||
            a.defaultUserName != b.defaultUserName ||
            a.recycleBinEnabled != b.recycleBinEnabled ||
            a.recycleBinUuid != b.recycleBinUuid ||
            a.entryTemplatesGroup != b.entryTemplatesGroup ||
            a.customIcons != b.customIcons ||
            a.memoryProtection != b.memoryProtection ||
            a.customData != b.customData ||
            a.historyMaxItems != b.historyMaxItems ||
            a.historyMaxSize != b.historyMaxSize

    /**
     * 外层头部**语义**比较（ISSUE-P2-596）：只比可编辑且**非随机**的参数（加密算法 / 压缩 /
     * KDF 参数族）。刻意排除每次保存随机重生成的 `masterSeed` / `encryptionIv` 与 KDF
     * `salt` / `seed` / `secretKey` / `associatedData`（见类 KDoc「排除随机材料」）。
     */
    fun headerSemanticChanged(a: KdbxHeader, b: KdbxHeader): Boolean {
        if (a.cipherUuid != b.cipherUuid) return true
        if (a.compression != b.compression) return true
        return kdfSemanticChanged(a.kdfParameters, b.kdfParameters)
    }

    private fun kdfSemanticChanged(a: KdfParameters, b: KdfParameters): Boolean {
        if (a.kdfUuid != b.kdfUuid) return true
        return when {
            a is KdfParameters.Argon2 && b is KdfParameters.Argon2 ->
                a.type != b.type || a.parallelism != b.parallelism ||
                    a.memoryInBytes != b.memoryInBytes || a.iterations != b.iterations ||
                    a.version != b.version
            a is KdfParameters.Aes && b is KdfParameters.Aes -> a.rounds != b.rounds
            else -> false
        }
    }

    /** 分组递归比较（name / notes / 图标 / 父组 / tags / customData 及其子项） */
    fun groupChanged(a: KdbxGroup, b: KdbxGroup): Boolean {
        if (a.id != b.id || a.name != b.name || a.notes != b.notes ||
            a.iconId != b.iconId || a.customIconId != b.customIconId ||
            a.parentGroupId != b.parentGroupId ||
            // P1-8 配套：分组 tags / customData 已为一等持久化字段，纳入变化检测，
            // 防止仅修改分组标签的编辑被误判为「无变化」而把旧字节上传云端
            a.tags != b.tags || a.customData != b.customData
        ) {
            return true
        }
        if (a.entries.size != b.entries.size) return true
        val bEntries = b.entries.associateBy { it.id }
        for (entry in a.entries) {
            val ref = bEntries[entry.id] ?: return true
            if (entryChanged(entry, ref)) return true
        }
        if (a.subgroups.size != b.subgroups.size) return true
        val bSubgroups = b.subgroups.associateBy { it.id }
        for (sub in a.subgroups) {
            val ref = bSubgroups[sub.id] ?: return true
            if (groupChanged(sub, ref)) return true
        }
        return false
    }

    /**
     * 条目比较：全部**内容**字段 + 到期语义（[expiryChanged]）+ 历史**内容级**比较
     * （[historyChanged]）。`lastModificationTime` / `lastAccessTime` / `usageCount` 刻意不在此列
     * （理由见类 KDoc）。`fields` 走 [KdbxEntry.fieldsContentEquals]（标准五字段按内容、忽略 `isProtected`）。
     */
    fun entryChanged(a: KdbxEntry, b: KdbxEntry): Boolean {
        return !a.fieldsContentEquals(b) ||
                a.customFields != b.customFields ||
                a.tags != b.tags ||
                a.attachments != b.attachments ||
                a.iconId != b.iconId ||
                a.customIconId != b.customIconId ||
                a.overrideUrl != b.overrideUrl ||
                a.qualityCheck != b.qualityCheck ||
                a.parentGroupId != b.parentGroupId ||
                a.previousParentGroup != b.previousParentGroup ||
                a.customData != b.customData ||
                a.autoType != b.autoType ||
                a.backgroundColor != b.backgroundColor ||
                a.foregroundColor != b.foregroundColor ||
                expiryChanged(a.times, b.times) ||
                historyChanged(a.history, b.history)
    }

    /**
     * 到期语义联合比较（ISSUE-P2-596）：`expires` 翻转即变更；仅在**生效**（`expires == true`）时
     * 比较 `expiryTime`——避免把禁用到期时并不生效的日期差异误判为变更。刻意不比较
     * `lastModificationTime` / `lastAccessTime` / `usageCount`（使用性字段，理由见类 KDoc）。
     */
    fun expiryChanged(a: KdbxTimes, b: KdbxTimes): Boolean {
        if (a.expires != b.expires) return true
        return a.expires && a.expiryTime != b.expiryTime
    }

    /**
     * 历史**内容级**比较（ISSUE-P2-596）：等条数原地改写历史同样判为变更
     * （原实现只比条数）。逐条复用 [entryChanged]；历史快照自身的 `history` 通常为空，
     * 递归有界。不解密、不物化明文（依赖 `fieldsContentEquals` 的 HMAC 等值标签）。
     */
    fun historyChanged(a: List<KdbxEntry>, b: List<KdbxEntry>): Boolean {
        if (a.size != b.size) return true
        return a.indices.any { entryChanged(a[it], b[it]) }
    }
}
