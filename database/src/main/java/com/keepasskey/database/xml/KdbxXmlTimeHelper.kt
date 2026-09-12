package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.time.Instant
import java.util.Base64

/**
 * KDBX XML 时间戳（`<Times>` 子树与 Meta 时间字段）编解码工具类。
 *
 * 官方标准（KDBX 4 规范 `kdbx_4.html`「Page History 0.2」）：
 * "Base64 string of the Int64 number of **seconds** elapsed since 0001-01-01 00:00 UTC"。
 * - **写出**：Base64(Int64 秒) + Little-Endian 8 字节；数值 = 自 0001-01-01T00:00:00Z 起的秒数，
 *   等价官方 `long lSec = dt.Ticks / TimeSpan.TicksPerSecond`（KeePass 2.61.1 `KdbxFile.Write.cs:799`），
 *   亚秒部分官方直接丢弃，本实现同样丢弃；
 * - **读入**：官方按秒还原（`new DateTime(lSec * TimeSpan.TicksPerSecond, DateTimeKind.Utc)`，
 *   `KdbxFile.Read.Streamed.cs:934-935`）；KeePassXC `KdbxXmlReader.cpp` 用 `addSecs(secs)`，
 *   KeePassDX `DatabaseOutputKDBX.kt` 用 `toDotNetSeconds()`——三者一致。
 *
 * 读侧额外兼容两类**非官方**历史表示（写出侧一律只产官方秒）：
 * 1. 本仓早期版本误写的 .NET Ticks（100ns，量级 6.4×10^17）——见 [TICKS_THRESHOLD] 分支，
 *    **历史遗留兼容，非官方格式**；
 * 2. KDBX 3 风格的 ISO-8601 字符串。
 *
 * 严格解析：元素存在但内容损坏时抛出 [KdbxCorruptFileException]。
 *
 * ⚠️ 历史事故（已整改）：本类曾把 .NET Ticks 当作「官方格式」写出（官方值的 10^7 倍），
 * 官方 KeePass 打开本仓产物时 `lSec * TicksPerSecond` 立即溢出——约 5/6 的文件根本无法打开，
 * 其余得到荒谬日期。**秒**才是官方格式；`KdbxTimesTest` 以「[formatDate] 产出的数值 < 2^40」
 * 作为防回归 golden 断言。
 */
object KdbxXmlTimeHelper {

    /**
     * KDBX/.NET 时间纪元（0001-01-01T00:00:00Z）与 Unix 纪元之间的秒数
     * （`Instant.parse("0001-01-01T00:00:00Z").epochSecond` 的相反数）。
     */
    private const val EPOCH_OFFSET_SECONDS = 62135596800L

    /** .NET 1 tick = 100ns，1 秒 = 10^7 ticks；**仅**供历史 Ticks 兼容分支使用。 */
    private const val TICKS_PER_SECOND = 10_000_000L

    /** 纪元原点在 .NET Ticks 标度下的取值；**仅**供历史 Ticks 兼容分支使用。 */
    private const val EPOCH_OFFSET_TICKS = EPOCH_OFFSET_SECONDS * TICKS_PER_SECOND

    /**
     * 「官方秒级时间戳」与「历史遗留 .NET Ticks」的判别阈值。
     *
     * 官方秒级时间戳的合法上界（公元 9999 年）约 3.2×10^11 < 10^13；
     * 而本仓历史误写的 Ticks 即便取 1970 年也达 6.2×10^17 ≥ 10^13。
     * 两侧量级相差 4 个数量级以上，以 10^13 分界不存在歧义。
     */
    private const val TICKS_THRESHOLD = 10_000_000_000_000L

    /**
     * 纪元原点时刻 0001-01-01T00:00:00Z（epochSecond = -62,135,596,800）。
     *
     * 语义：作为「时间戳缺失」的缺省值——官方对未初始化的时间取 .NET `DateTime.MinValue`，
     * 即本时刻。**绝不能改用 now()**：三方合并按 `lastModificationTime` 越新越胜出，
     * now() 会让缺 `<Times>` 的一侧被误判为「刚刚修改」而虚假覆盖对端；
     * 本时刻早于任何真实密码库时间戳（早于 1970），恒不可能虚假胜出。
     * 写回时它编码为 8 个零字节（秒数 0），与官方写出 `DateTime.MinValue` 的结果完全一致。
     */
    val ANCIENT_INSTANT: Instant = Instant.parse("0001-01-01T00:00:00Z")

    /**
     * 将 [Instant] 格式化为官方 KDBX 4 标准 Base64(Int64 秒 自 0001-01-01 UTC)。
     *
     * 亚秒部分按官方语义丢弃，故「写出 → 读回」是截断到秒的恒等，而非纳秒级恒等。
     */
    fun formatDate(instant: Instant): String {
        val bytes = LittleEndianUtil.longTo8Bytes(instantToDotNetSeconds(instant))
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 将 [Instant] 编码为官方「自 0001-01-01T00:00:00Z 起的秒数」（= .NET Ticks / 10^7），
     * 等价官方 `dt.Ticks / TimeSpan.TicksPerSecond`；亚秒部分丢弃。
     *
     * 命名沿革（API 变更）：本方法原名为 `instantToTicks` 且返回 .NET Ticks（官方值的 10^7 倍），
     * 名称与量级都会诱导调用方把 Ticks 当官方格式写出，故按官方语义改为秒并更名。
     */
    fun instantToDotNetSeconds(instant: Instant): Long {
        return EPOCH_OFFSET_SECONDS + instant.epochSecond
    }

    /**
     * 将 .NET Ticks 数值还原为 [Instant]。
     *
     * **历史遗留兼容专用**（本仓早期误写 Ticks 的产物）；.NET Ticks 不是官方 KDBX 4 时间格式，
     * 官方读侧只认秒（见类 KDoc）。新代码请勿调用本方法编码，写出请用 [formatDate]。
     */
    fun ticksToInstant(ticks: Long): Instant {
        val netTicks = ticks - EPOCH_OFFSET_TICKS
        val epochSec = Math.floorDiv(netTicks, TICKS_PER_SECOND)
        val nanoTicks = Math.floorMod(netTicks, TICKS_PER_SECOND)
        return Instant.ofEpochSecond(epochSec, nanoTicks * 100)
    }

    /**
     * `<Times>` 元素**整体缺失**时的缺省时间元数据：全部时刻取 [ANCIENT_INSTANT]，
     * `expires = false`、`usageCount = 0`（官方字段缺省语义）。
     *
     * 与 [parseDate] 的缺省参数同源，保证「整个 `<Times>` 缺失」与「`<Times>` 子元素缺失」
     * 两条路径给出同一个远古缺省，绝不退化为 now()（合并误判说明见 [ANCIENT_INSTANT] KDoc）。
     */
    fun ancientTimes(): KdbxTimes = KdbxTimes(
        creationTime = ANCIENT_INSTANT,
        lastModificationTime = ANCIENT_INSTANT,
        lastAccessTime = ANCIENT_INSTANT,
        expiryTime = ANCIENT_INSTANT,
        expires = false,
        usageCount = 0L,
        locationChanged = ANCIENT_INSTANT
    )

    /**
     * ISO-8601 日历日期嗅探模式（形如 "2024-01-01T..." 或 "2024-01-01..."）。
     * 必须以「4 位数字 + 连字符」开头——标准 Base64 字母表（A-Za-z0-9+/=）不含连字符，
     * 因此 Base64 编码的时间值绝不可能命中本模式，杜绝含大写 "T" 的 Base64
     * 时间值被误判为 ISO-8601 的随机性缺陷。
     */
    private val iso8601Pattern = Regex("^\\d{4}-\\d{2}-\\d{2}")

    /**
     * 从 XML 字符串解析时间戳。
     *
     * - 元素缺失（null / blank）→ 返回 [defaultInstant]（缺省 [ANCIENT_INSTANT]：该值恒早于
     *   真实时间戳，不会在「越新越胜出」的三方合并中被误判为「刚修改」）；
     * - 元素存在但内容损坏 → 抛 [KdbxCorruptFileException]，禁止静默降级为缺省值。
     *
     * 支持三类表示：
     * 1. 官方 KDBX 4 秒级 Base64(Int64 LE)，判据为 `value < TICKS_THRESHOLD`（见 [TICKS_THRESHOLD]）；
     * 2. **历史遗留兼容**：本仓早期误写的 .NET Ticks，判据为 `value >= TICKS_THRESHOLD`，非官方格式；
     * 3. KDBX 3 风格 ISO-8601 字符串（严格嗅探，见 [iso8601Pattern]）。
     */
    fun parseDate(dateStr: String?, defaultInstant: Instant = ANCIENT_INSTANT): Instant {
        if (dateStr.isNullOrBlank()) return defaultInstant
        val clean = dateStr.trim()

        // 兼容 KDBX 3 格式的 ISO-8601 字符串（严格模式嗅探，见 iso8601Pattern KDoc）
        if (iso8601Pattern.containsMatchIn(clean)) {
            return try {
                Instant.parse(clean)
            } catch (e: Exception) {
                throw KdbxCorruptFileException("非法的 ISO-8601 时间格式: $clean", e)
            }
        }

        val bytes = try {
            Base64.getDecoder().decode(clean)
        } catch (e: Exception) {
            throw KdbxCorruptFileException("非法的 Base64 时间字符串: $clean", e)
        }

        if (bytes.size != 8) {
            throw KdbxCorruptFileException("非法的二进制时间数据长度: ${bytes.size}，期望 8 字节")
        }

        val value = LittleEndianUtil.bytesToLong(bytes)
        return if (value >= TICKS_THRESHOLD) {
            // 历史遗留兼容：本仓早期版本误写 .NET Ticks（100ns）；官方格式是秒，此分支仅为读回旧产物
            try {
                ticksToInstant(value)
            } catch (e: Exception) {
                throw KdbxCorruptFileException("超出合法范围的 Ticks 时间戳: $value", e)
            }
        } else {
            // 官方语义：自 0001-01-01T00:00:00Z 起的秒数
            val epochSec = value - EPOCH_OFFSET_SECONDS
            try {
                Instant.ofEpochSecond(epochSec)
            } catch (e: Exception) {
                throw KdbxCorruptFileException("超出合法范围的秒级时间戳: $value", e)
            }
        }
    }
}
