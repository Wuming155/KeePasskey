package com.keepasskey.app.data.repository

import com.keepasskey.core.result.KdbxResult

/**
 * 敏感值按需读取通道（ISSUE-P3-305：自 [VaultRepository] 按职责拆出的能力面，纯结构性改动）。
 *
 * 职责单一：条目的口令 / 历史修订口令 / 修订快照 / 受保护自定义字段 / TOTP（取码与配置原文）/
 * 附件字节的**按需读取**，以及 HOTP 计数器的原子推进。
 *
 * 拆出动机（与 `ISSUE-P3-29` 拆伴随值对象同源）：[VaultRepository] 承载的能力面过多，
 * 单文件已越过规模闸门；本接口按「取用凭据内容」这一内聚边界分列，`VaultRepository` 继承之，
 * 故所有既有调用点（形如 `vaultRepository.getEntryPasswordChars(...)`）**零改动**。
 *
 * 擦除契约（不变）：所有 `CharArray` / `ByteArray` 通道返回**调用方独占副本**，
 * 调用方用毕必须显式清零；`String` 通道为待下线面，仅保留给不得不用 String 语义的引擎。
 */
interface VaultSecretAccess {

    /**
     * 按需解密单条凭据的密码（M1 整改）。
     * 仅在用户显式查看/复制密码时调用，杜绝全库密码明文驻留 StateFlow / 堆内存；
     * 返回 String 由调用方用毕自然丢弃（UI 显示边界），条目不存在或无密码时返回 null。
     *
     * ISSUE-P2-15：返回值为不可擦除 String，已列入下线通道；新代码一律改用
     * [getEntryPasswordChars]（CharArray 独占副本，调用方用毕 `fill('0')`）。
     * 仅剩 {REF:...} 字段引用解析等 String 语义引擎不得不用时保留。
     */
    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryPasswordChars(entryId)")
    )
    suspend fun getEntryPassword(entryId: String): String?

    /**
     * 按需解密单条凭据的密码为 CharArray（加解密审查 2026-09 M1 整改，编辑页 CharArray 直通链路）。
     * 返回的数组是分配给调用方的独占副本，调用方使用完毕必须显式清零
     * （`Arrays.fill(chars, '0')`）；条目不存在或无密码时返回 null。
     */
    suspend fun getEntryPasswordChars(entryId: String): CharArray?

    /**
     * 按需解密单条历史修订的密码（M1 整改，供详情页回滚/对比使用），语义同 [getEntryPassword]。
     *
     * ISSUE-P2-15：同属待下线 String 通道，请改用 [getEntryRevisionPasswordChars]
     * （CharArray 独占副本，调用方用毕 `fill('0')` 或交由 [VaultRepository.saveEntry] 擦除）。
     */
    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryRevisionPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryRevisionPasswordChars(entryId, revisionId)")
    )
    suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String?

    /**
     * 按需解密单条历史修订的密码为 CharArray（加解密审查 2026-09 M2 整改，回滚路径专用：
     * 全程 CharArray、不经 String 中转）。返回的数组是分配给调用方的独占副本，调用方
     * 使用完毕必须显式清零；修订不存在或无密码时返回 null。
     */
    suspend fun getEntryRevisionPasswordChars(entryId: String, revisionId: String): CharArray?

    /**
     * 读取单条历史修订的完整回滚快照（断点8 整改，供详情页全字段回滚）。
     * 返回的 [EntryRevisionSnapshot.entry] 中受保护字段已解密（仅驻留编辑会话），
     * [EntryRevisionSnapshot.totpSecretChars] 为该修订 TOTP 配置原文独占 CharArray 副本
     * （无则空数组），调用方按借用语义用毕清零或交由 [VaultRepository.saveEntry] 擦除；
     * 修订不存在时返回 null。
     */
    suspend fun getEntryRevisionSnapshot(entryId: String, revisionId: String): EntryRevisionSnapshot?

    /**
     * 按需解密单条凭据的受保护自定义字段为 CharArray（TASK-10：编辑态 CharArray 化，
     * 与 [getEntryPasswordChars] 同一借用语义）。
     * 仅在用户显式编辑该字段时调用；返回的数组是分配给调用方的独占副本，调用方使用完毕
     * 必须显式清零；条目或字段不存在时返回 null，未加保护的字段直接返回其值副本。
     */
    suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray?

    /**
     * 按需计算单条凭据的当前 TOTP 验证码（F2 整改）。
     * TOTP 种子绝不离开数据层——种子解析与验证码计算均在仓库内部完成并即时丢弃，
     * UI 层仅取得验证码与展示配置；条目未配置 TOTP 时返回 null。
     *
     * ISSUE-P2-90：同一周期内的重复调用由数据层缓存吸收（不重复解密 / 不重复计算），
     * 调用方无需自行节流。
     */
    suspend fun calculateEntryTotp(entryId: String): EntryTotpSnapshot?

    /**
     * ISSUE-P2-90：批量计算多条凭据的当前验证码——**一次会话读取 + 一次条目索引**，
     * 适用于「同一时刻要拿一批码」的场景（列表页每周期重算），
     * 相对逐条调用 [calculateEntryTotp] 可把 O(T×N) 的条目定位收敛为 O(N + T)。
     *
     * 条目不存在 / 未配置 TOTP / 计算失败的 id **不出现在返回值中**（与单条通道返回 null 同义）。
     */
    suspend fun calculateEntryTotps(entryIds: List<String>): Map<String, EntryTotpSnapshot>

    /**
     * ISSUE-P3-49：推进 HOTP（RFC 4226）条目的计数器并返回**本次所出之码**。
     *
     * 语义（对齐 KeePassXC）：
     * 1. 读取条目 `otp` 字段的 `otpauth://hotp/...&counter=N` 配置；
     * 2. 计算计数器 N 对应的验证码并**先把计数器 N+1 落库**，成功后才返回该码——
     *    落库失败则整体失败（绝不返回一个「未推进」的码，否则同一计数器会被重复使用）；
     * 3. 计数器推进**不产生历史修订**（属口令取用而非内容修订）。
     *
     * 非 HOTP 条目（TOTP / 无 OTP）返回 [KdbxResult.Failure]；
     * 计数器非法 / URI 非法时 fail-closed 失败，不落库。
     */
    suspend fun advanceEntryHotpCounter(entryId: String): KdbxResult<EntryTotpSnapshot>

    /**
     * 按需读取单条凭据的 TOTP 配置原文为 CharArray（断点4 整改 + TASK-10 编辑态 CharArray 化）。
     * 与 [calculateEntryTotp] 同源：标准 otp 字段优先，回退 TOTP 开头的自定义字段；
     * 未配置时返回 null。返回的数组是分配给调用方的独占副本，调用方使用完毕必须显式清零。
     */
    suspend fun getEntryTotpSecretChars(entryId: String): CharArray?

    /**
     * 按需解析单条凭据指定附件的二进制内容（断点3 整改，SAF 导出用）。
     *
     * `ISSUE-P3-295`：寻址参数由**文件名**改为**附件下标 `refIndex`**（条目 `attachments`
     * 列表中的位置）。外部库（KeePass XML / Bitwarden / 桌面版）可含**同名不同内容**的附件，
     * 按名字取会「导出 A 得 B 的字节且提示为 A」；下标寻址恒精确。
     * 下标越界或该附件为空字节时返回 null（空字节语义与改前一致）。
     *
     * 返回的是**调用方独占的独立副本**（内存附件走 `copyOf()`，落盘附件走 `source.load()`，
     * 见 ISSUE-P3-105）；调用方用毕应自行 `fill(0)` 清零，勿长期持有。
     */
    suspend fun getAttachmentData(entryId: String, refIndex: Int): ByteArray?
}
