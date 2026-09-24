package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * 条目敏感值读取协调器（ISSUE-P3-31 批次 B 自 `RealVaultRepository` 拆出，纯结构性改动）。
 *
 * 职责单一：条目的密码 / 历史修订密码 / 修订快照 / 受保护自定义字段 / TOTP
 * （计算与配置原文）/ 附件数据的**按需解密读取**。
 *
 * ISSUE-P3-148：按 id 定位条目一律走 `KdbxGroup.findEntry`（深度优先短路），**不再**
 * `rootGroup.allEntries().firstOrNull { … }`——后者的语义虽等价（同为深度优先首命中），
 * 但会先把**整库条目**物化成一份临时列表，在「只需一条」的热路径（自动填充确认页按条目取
 * 用户名 / 口令 / TOTP）上属于与库规模成正比的纯浪费。
 *
 * ## ISSUE-P2-90：TOTP 验证码缓存与批量取码通道
 *
 * 缺陷背景：验证码重算的粒度原本是「每个带 TOTP 的条目各调一次 [calculateEntryTotp]」，
 * 而每次调用都要 `databaseFlow.first()` + `findEntry`（线性深度优先）+ Base32 解码 + HMAC。
 * 列表页每周期、验证器页**每秒**、详情页**每秒**各来一轮，构成 O(T×N) 的持续开销
 * （T = TOTP 条目数，N = 全库条目数）——用户看不见时也在烧 CPU 与电量。
 *
 * 收敛手段（**不改变任何可观察输出**，验证码恒为当前周期之码）：
 * 1. **验证码缓存**：TOTP 之码在一个周期内恒定，故按 `entryId → (周期号, 快照)` 缓存；
 *    命中即返回（无会话访问、无解密、无 HMAC），跨周期自然失效 ⇒ 非翻转秒的 HMAC 次数为 0；
 * 2. **批量通道** [calculateEntryTotps]：一次遍历建立 id 索引后集中计算，把逐条 `findEntry`
 *    的 O(T×N) 收敛为 O(N + T)；
 * 3. **失效点**：会话落库（[invalidateTotpCache] 由仓库在 `databaseFlow` 变更与 `save()`
 *    后调用）——改种子 / 改周期 / 锁库 / 同步合并后缓存立即作废，不会留下过期验证码。
 *
 * **HOTP 不入缓存**：其码由**持久化计数器**决定，任何一步写回都会改变它，
 * 缓存换来的收益（用户显式取码是低频动作）远小于「交付一个已被推进掉的码」的风险，
 * 故 [EntryTotpSnapshot.isHotp] 为真时一律现算。
 *
 * 擦除契约（ISSUE-P2-15）保持不变：
 * - [readErasableString] 中转的 CharArray 副本在用毕立即清零；
 * - 所有 `CharArray` 通道返回**独占副本**，清零责任按借用契约移交调用方；
 * - TOTP 计算路径持有的 Base32 种子字节在 `finally` 中擦除（**只缓存结果快照，
 *   绝不缓存种子配置**——快照不含任何密钥材料）。
 */
internal class VaultEntrySecretReader(
    private val databaseSession: DatabaseSession,
    private val entryMapper: VaultEntryMapper,
    /**
     * 墙钟读取器——验证码缓存以「周期号」判定命中，故必须与真实时间同源。
     * 生产为 [System.currentTimeMillis]；单测注入可控时钟，使「跨周期即失效」可被确定性断言。
     */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * ISSUE-P3-273：TOTP 解析参数通道（字段名映射 + 默认步长 / 位数）。
     * 缺省为 [TotpPreferences.DEFAULT]（纯 JVM 单测未注入时的回落）。
     */
    private val totpPreferences: () -> TotpPreferences = { TotpPreferences.DEFAULT }
) {

    /** 单条验证码缓存项：命中判据为「同一周期号，且解析参数未变」。 */
    private class CachedTotp(
        val periodIndex: Long,
        val preferences: TotpPreferences,
        val snapshot: EntryTotpSnapshot
    )

    /** `entryId → 本周期验证码`（并发安全；HOTP 从不写入） */
    private val totpCache = ConcurrentHashMap<String, CachedTotp>()

    /**
     * 作废全部验证码缓存。
     *
     * 调用时机由仓库统一收口：① 会话状态流（`databaseFlow`）每次变更（含锁库归空、
     * 同步合并、外部写入）；② 每次 [com.keepasskey.app.data.repository.RealVaultRepository]
     * 落库成功后同步调用（消除「刚改完种子、同一周期内仍读到旧码」的竞态窗口）。
     */
    fun invalidateTotpCache() {
        totpCache.clear()
    }

    /**
     * ISSUE-P2-15 兼容读取通道：把受保护值读成 String 并保证中间 CharArray 副本即时清零。
     *
     * 返回值本身仍是不可擦除的 String（UI 投影模型与待下线 String 接口的既有约束），
     * 但相对直接调用 `ProtectedString.readString()`，本通道不再让明文副本静默等待 GC。
     * 新代码应优先使用对应的 CharArray 借用通道。
     */
    internal fun readErasableString(value: ProtectedString?): String? {
        val chars = value?.readChars() ?: return null
        return try {
            String(chars)
        } finally {
            chars.fill('0')
        }
    }

    // 原 readErasableChars（ProtectedString? → CharArray?）随 ISSUE-P3-273 的字段定位收敛
    // 失去全部调用点（修订快照改走 VaultEntryTotpMapping.locateConfigSource().readChars()），
    // 按「删除死代码」口径移除；需要该能力处直接调 ProtectedString.readChars()（同为独占副本语义）。

    suspend fun getEntryPassword(entryId: String): String? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid)
        // ISSUE-P2-15：不再直接 readString()，经 CharArray 独占副本中转并即时清零
        return readErasableString(entry?.password)
    }

    suspend fun getEntryPasswordChars(entryId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid)
        // readChars() 返回独占副本（内部中间量已清零），清零责任随契约移交调用方
        return entry?.password?.readChars()
    }

    suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid)
        // ISSUE-P2-15：不再直接 readString()，经 CharArray 独占副本中转并即时清零
        return readErasableString(entry?.history?.firstOrNull { it.id == revisionUuid }?.password)
    }

    suspend fun getEntryRevisionPasswordChars(entryId: String, revisionId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid)
        // M2 整改：回滚路径全程 CharArray（readChars 返回独占副本，内部中间量已清零）
        return entry?.history?.firstOrNull { it.id == revisionUuid }?.password?.readChars()
    }

    suspend fun getEntryRevisionSnapshot(entryId: String, revisionId: String): EntryRevisionSnapshot? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid) ?: return null
        val revision = entry.history.firstOrNull { it.id == revisionUuid } ?: return null
        // 断点8 整改：整修订快照投影 + 受保护字段解密回填（仅驻留回滚会话），
        // 使回滚保存时 title/url/自定义字段/TOTP/密码全字段真实还原
        val projection = entryMapper.mapKdbxEntryToUi(revision)
        // ISSUE-P2-15：受保护字段经 CharArray 独占副本中转（用毕清零）；UiCustomField.value 仍为
        // String（UI 投影模型已知约束），此处物化的 String 属投影边界、不可擦，见 ISSUE-P2-15 备注
        val decryptedFields = projection.customFields.map { cf ->
            if (cf.isProtected) {
                cf.copy(
                    value = readErasableString(
                        revision.customFields.firstOrNull { it.key == cf.key }?.value
                    ).orEmpty()
                )
            } else {
                cf
            }
        }
        // ISSUE-P2-15：TOTP 原文以 CharArray 独占副本返回，不再物化不可擦 String
        // ISSUE-P3-273：字段定位改走单一真相源（设置值优先 → otp → TOTP 前缀）
        val totpRawChars = VaultEntryTotpMapping.locateConfigSource(revision, totpPreferences())
            ?.readChars()
            ?: CharArray(0)
        return EntryRevisionSnapshot(
            entry = projection.copy(customFields = decryptedFields),
            totpSecretChars = totpRawChars
        )
    }

    suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid) ?: return null
        // TASK-10：编辑态 CharArray 化——readChars 返回独占副本，调用方按借用语义用毕清零
        return entry.customFields.firstOrNull { it.key == fieldKey }?.value?.readChars()
    }

    /**
     * 按 id 计算单条 TOTP 验证码（ISSUE-P2-90：命中缓存时**不触碰会话、不解密、不算 HMAC**）。
     *
     * 返回 null 的三种情形（与接线前一致）：条目不存在 / 未配置 OTP / 验证码计算失败。
     */
    suspend fun calculateEntryTotp(entryId: String): EntryTotpSnapshot? {
        val now = nowMillis()
        cachedTotpOrNull(entryId, now)?.let { return it }
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid) ?: return null
        return computeAndCacheTotp(entryId, entry, now)
    }

    /**
     * 批量计算验证码（ISSUE-P2-90）：**一次会话读取 + 一次条目索引**覆盖全部请求的条目，
     * 把「逐条 `findEntry`」的 O(T×N) 收敛为 O(N + T)。列表页每周期重算即走本通道。
     *
     * 未命中缓存且条目不存在 / 无 OTP 的 id **不出现在返回值中**（与单条通道返回 null 同义）。
     * 索引只在**存在缺失项**时构建——全命中时零遍历、零分配。
     */
    suspend fun calculateEntryTotps(entryIds: List<String>): Map<String, EntryTotpSnapshot> {
        if (entryIds.isEmpty()) return emptyMap()
        val now = nowMillis()
        val result = LinkedHashMap<String, EntryTotpSnapshot>(entryIds.size)
        val pending = ArrayList<String>()
        for (id in entryIds) {
            val cached = cachedTotpOrNull(id, now)
            if (cached != null) result[id] = cached else pending += id
        }
        if (pending.isEmpty()) return result

        val currentDb = databaseSession.databaseFlow.first() ?: return result
        // 批次内条目集合固定，故单次物化索引优于「每条各走一遍深度优先」：
        // 这里的 O(N) 是一次性成本，而逐条 findEntry 是 O(T×N)（ISSUE-P3-148 针对的是
        // 「只需一条」的路径，两处取舍不冲突）
        val index = currentDb.rootGroup.allEntries().associateBy { it.id }
        for (id in pending) {
            val uuid = parseKdbxUuidOrNull(id) ?: continue
            val entry = index[uuid] ?: continue
            computeAndCacheTotp(id, entry, now)?.let { result[id] = it }
        }
        return result
    }

    /**
     * 单条验证码计算与入缓存（种子解析 → 计算 → 擦除种子 → 仅缓存结果快照）。
     * 缓存写入对 HOTP 与非法周期一律跳过（见类 KDoc）。
     *
     * @param now 取值时刻：**出码与周期号判定共用本时刻**，杜绝两者错配。
     */
    private fun computeAndCacheTotp(entryId: String, entry: KdbxEntry, now: Long): EntryTotpSnapshot? {
        val preferences = totpPreferences()
        val config = entryMapper.parseTotpConfig(entry, preferences) ?: return null
        return try {
            val snapshot = entryMapper.computeTotpCode(config, now)?.let { code ->
                EntryTotpSnapshot(
                    code = code,
                    periodSeconds = config.period,
                    digits = config.digits,
                    algorithm = config.algorithm,
                    isHotp = config.isHotp,
                    counter = config.counter
                )
            }
            if (snapshot != null && !snapshot.isHotp && snapshot.periodSeconds > 0) {
                totpCache[entryId] = CachedTotp(
                    periodIndex = periodIndexOf(now, snapshot.periodSeconds),
                    preferences = preferences,
                    snapshot = snapshot
                )
            }
            snapshot
        } finally {
            // ISSUE-P2-12：解析配置持有的 Base32 种子字节用毕即擦（成功/失败路径一致）
            config.secret.fill(0)
        }
    }

    /**
     * 命中缓存则返回本周期验证码；未命中 / HOTP / 周期非法 / **解析参数已变**返回 null
     * （调用方走现算路径）。
     *
     * ISSUE-P3-273：用户在设置页改动字段名或默认步长 / 位数后，缓存内按旧参数算出的
     * 码不再可信——新增 [TotpPreferences] 等值判据使其立即失效，不必等会话变更。
     */
    private fun cachedTotpOrNull(entryId: String, nowMillis: Long): EntryTotpSnapshot? {
        val cached = totpCache[entryId] ?: return null
        if (cached.preferences != totpPreferences()) return null
        val period = cached.snapshot.periodSeconds
        if (cached.snapshot.isHotp || period <= 0) return null
        return cached.snapshot.takeIf { periodIndexOf(nowMillis, period) == cached.periodIndex }
    }

    /** 时间戳落在第几个 TOTP 周期（跨周期即缓存失效）。 */
    private fun periodIndexOf(nowMillis: Long, periodSeconds: Int): Long =
        nowMillis / MILLIS_PER_SECOND / periodSeconds

    suspend fun getEntryTotpSecretChars(entryId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid) ?: return null
        // 断点4 整改：与 parseTotpConfig 同源读取。
        // ISSUE-P3-273：改为直接复用单一真相源（原为本地重写一份优先序，与解析侧漂移即出错）
        // TASK-10：返回配置原文独占 CharArray 副本（otpauth:// URI 或 Base32 种子）供编辑页回填，
        // 调用方按借用语义用毕清零
        return VaultEntryTotpMapping.locateConfigSource(entry, totpPreferences())?.readChars()
    }

    suspend fun getAttachmentData(entryId: String, fileName: String): ByteArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.findEntry(targetUuid) ?: return null
        val attachment = entry.attachments.firstOrNull { it.name == fileName } ?: return null
        // ISSUE-P2-24：按需读取本附件字节（落盘大附件由 source 流式读回），
        // 不再把整个二进制池 map 成字节数组把全库附件拉回内存。
        // ISSUE-P3-105：按来源分流，消除落盘路径的双重拷贝——`source.load()` 已返回独立副本，
        // 原实现再 `.copyOf()` 一次，第一份副本无人持有 / 无人清零，随 GC 静默留存。
        // - 落盘来源：`load()` 的独立副本直接交出（所有权归调用方）；
        // - 内存来源：`attachment.data` 是实例内部数组的**借用视图**，必须复制后交出，
        //   否则调用方清零会连带清空库内附件字节（ISSUE-P3-07 借用语义）。
        val source = attachment.binarySource()
        val bytes = if (source != null) source.load() else attachment.data.copyOf()
        return if (bytes.isEmpty()) null else bytes
    }

    private companion object {
        /** 毫秒 → 秒（TOTP 周期号换算用）。 */
        const val MILLIS_PER_SECOND = 1_000L
    }
}
