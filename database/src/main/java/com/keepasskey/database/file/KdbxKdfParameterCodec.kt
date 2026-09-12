package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.crypto.VariantDictionary
import com.keepasskey.database.exception.KdbxCorruptFileException

/**
 * KDF 参数的变体字典编解码与边界裁决（ISSUE-P3-29：自 `KdbxHeader.kt` 拆出，纯结构性拆分）。
 *
 * 原实现逐字迁移；`KdbxHeader` 保留同名门面委托，既有调用方（含单测）零改动。
 *
 * ## 缺参数语义：fail-closed（对齐官方 `Argon2Kdf.Transform`）
 *
 * 官方 KeePass 2.61.1 `KeePassLib/Cryptography/KeyDerivation/Argon2Kdf.cs:146-160` 对
 * `P` / `M` / `I` / `V` **一律以 0 作默认值**再走范围检查——缺少任一参数必然越界并抛异常。
 * 即官方实现是 fail-closed 的：缺参数 == 文件损坏，绝不用「工厂默认值」凑合派生密钥。
 *
 * 本仓原先为缺失项静默填入 `p=2 / m=64 MiB / i=2 / v=0x13`（即 `KdfParameters.Argon2`
 * 数据类默认值）。该宽容行为有害：
 * - 变体字典被裁剪 / 损坏时本应报「文件损坏」，却被默认值掩盖成「口令错误」；
 * - 以既非文件声明值、也非用户选择的参数派生密钥，破坏「派生参数完全来自文件」这一前提。
 *
 * 故本仓**删除全部默认值**，缺失任一键即在解析期抛 [KdbxCorruptFileException]，错误消息点名缺失的键。
 *
 * ## 边界取值：规范 / 官方语义 vs 本仓封顶
 *
 * 上界校验对照 KeePassDX Limits / KeePassXC 参数封顶语义：文件中的 VariantDictionary 参数
 * 在进入计算前必须先通过边界裁决，否则恶意构造的 KDBX 可声明 1 TB 级 Argon2 内存
 * （分配期 OOM 崩溃）、2^60 级 AES 轮数或迭代数（无限期占用 CPU 线程）造成拒绝服务。
 *
 * | 参数 | 规范 / 官方语义 | 本仓裁决 | 说明 |
 * |------|----------------|----------|------|
 * | Argon2 `M` 下界 | 8192 字节（官方 `Argon2Kdf.MinMemory`） | **8192** | 与官方一致；原为 1 MiB，会误拒合法库 |
 * | Argon2 `M` 上界 | `int.MaxValue`（官方 `MaxMemory`；规范为 `uint.MaxValue * 1024`） | **4 GiB** | 本仓更严（另有 JVM 堆 1/2 动态门槛） |
 * | Argon2 `I` 下界 | 1（`MinIterations`） | **1** | 与官方一致 |
 * | Argon2 `I` 上界 | `uint.MaxValue` | **2^24** | 本仓更严（合法配置通常 ≤ 数千轮） |
 * | Argon2 `P` 下界 | 1（`MinParallelism`） | **1** | 与官方一致 |
 * | Argon2 `P` 上界 | `(1 shl 24) - 1` | **64** | 本仓更严（合法配置通常 ≤ CPU 核数） |
 * | Argon2 `V` 取值集 | `{0x10, 0x13}`（`MinVersion` / `MaxVersion`） | **{0x10, 0x13}** | 与官方一致 |
 * | AES-KDF `R` 下界 | 1 | **1** | 与官方一致 |
 * | AES-KDF `R` 上界 | 无（规范未封顶） | **2^28** | 本仓封顶（合法偏执配置通常 ≤ 1 亿轮） |
 *
 * **凡标注「本仓更严」的封顶均属 fail-closed 加固**：取值宽于一切合法用户配置
 * （合法范围见 KdfBenchmark / 各引擎默认值），正常文件不受影响，仅恶意构造文件被提前裁决。
 */
internal object KdbxKdfParameterCodec {

    /**
     * Argon2 内存下界：对齐官方 `Argon2Kdf.MinMemory = 1024 * 8`（8192 字节）。
     * 属规范语义，**不得上调**——上调会误拒官方客户端写出的合法库（如 P=1 的小内存配置）。
     */
    private const val ARGON2_MIN_MEMORY_BYTES = 8192L

    /** Argon2 内存上界：远超一切合法用户配置的绝对封顶（4 GiB），防恶意文件分配期 OOM。本仓更严封顶。 */
    private const val ARGON2_MAX_MEMORY_BYTES = 4L * 1024 * 1024 * 1024

    /** Argon2 迭代上界（合法配置通常 ≤ 数千轮）。本仓更严封顶。 */
    private const val ARGON2_MAX_ITERATIONS = 1L shl 24

    /** Argon2 并行度上界（合法配置通常 ≤ CPU 核数）。本仓更严封顶。 */
    private const val ARGON2_MAX_PARALLELISM = 64

    /** AES-KDF 轮数上界：合法偏执配置通常 ≤ 1 亿轮，此处封顶 2^28 防无限期占用 CPU。本仓封顶。 */
    private const val AES_KDF_MAX_ROUNDS = 1L shl 28

    fun serialize(params: KdfParameters): VariantDictionary {
        val vd = VariantDictionary()
        vd.setByteArray("\$UUID", params.kdfUuid.toByteArray())
        when (params) {
            is KdfParameters.Aes -> {
                vd.setByteArray("S", params.seed)
                vd.setUInt64("R", params.rounds)
            }
            is KdfParameters.Argon2 -> {
                vd.setByteArray("S", params.salt)
                // ISSUE-P1-13：KDBX4 规范规定 Argon2 `P`（Parallelism）以 UInt32 写出——
                // 官方 KeePass / KeePassXC 按严格 uint 读取，UInt64 编码会让官方客户端
                // 以默认值派生密钥而无法解锁。I / M 保持 UInt64，V 与 P 同为 UInt32。
                vd.setUInt32("P", params.parallelism.toLong())
                vd.setUInt64("M", params.memoryInBytes)
                vd.setUInt64("I", params.iterations)
                vd.setUInt32("V", params.version.toLong())
                val secret = params.secretKey
                if (secret != null) vd.setByteArray("K", secret)
                val assoc = params.associatedData
                if (assoc != null) vd.setByteArray("A", assoc)
            }
        }
        return vd
    }

    fun deserialize(bytes: ByteArray): KdfParameters {
        val vd = VariantDictionary.deserialize(bytes)
        val uuidBytes = vd.getByteArray("\$UUID") ?: throw KdbxCorruptFileException("KDF 参数中缺失 \$UUID")
        val uuid = KdbxUuid(uuidBytes)

        return when (uuid) {
            KdbxConstants.Kdf.AES_KDF -> {
                val seed = vd.getByteArray("S") ?: throw KdbxCorruptFileException("AES-KDF 缺少 S 参数")
                val rounds = vd.getUInt64("R") ?: throw KdbxCorruptFileException("AES-KDF 缺少 R 参数")
                validateAesKdfBounds(rounds)
                KdfParameters.Aes(seed = seed, rounds = rounds)
            }
            KdbxConstants.Kdf.ARGON2D, KdbxConstants.Kdf.ARGON2ID -> {
                val type = if (uuid == KdbxConstants.Kdf.ARGON2D)
                    KdfParameters.Argon2.Argon2Type.ARGON2D
                else
                    KdfParameters.Argon2.Argon2Type.ARGON2ID
                val salt = vd.getByteArray("S") ?: throw KdbxCorruptFileException("Argon2 缺少 S 参数")
                // 官方语义（KeePass 2.61.1 Argon2Kdf.cs:146-160）：P/M/I/V 一律以 0 为默认再走范围检查，
                // 故缺参数必然越界抛异常 —— 本仓与官方同为 fail-closed，**不填任何工厂默认值**。
                // 对齐 KeePassDX / 官方规范：P 与 V 在 KDBX4 变体字典中以 UInt32 类型写出，
                // 按 UInt32 读取（类型宽容：UInt64 编码的 P/V 亦按 UInt32 语义窄化，
                // 既有契约由 KdbxKdfParameterCodecWriteContractTest 锁定，不得回退）。
                val p = vd.getUInt32("P")?.toInt()
                    ?: throw KdbxCorruptFileException("Argon2 缺少 P 参数（并行度）")
                val m = vd.getUInt64("M")
                    ?: throw KdbxCorruptFileException("Argon2 缺少 M 参数（内存字节数）")
                val i = vd.getUInt64("I")
                    ?: throw KdbxCorruptFileException("Argon2 缺少 I 参数（迭代次数）")
                val v = vd.getUInt32("V")?.toInt()
                    ?: throw KdbxCorruptFileException("Argon2 缺少 V 参数（算法版本）")
                val k = vd.getByteArray("K")
                val a = vd.getByteArray("A")
                validateArgon2Bounds(m, i, p, v)
                KdfParameters.Argon2(
                    type = type,
                    salt = salt,
                    parallelism = p,
                    memoryInBytes = m,
                    iterations = i,
                    version = v,
                    secretKey = k,
                    associatedData = a
                )
            }
            else -> throw KdbxCorruptFileException("未知的 KDF 算法: $uuid")
        }
    }

    /**
     * KDF 参数边界校验（对照 KeePassDX Limits / KeePassXC 参数封顶语义）。
     *
     * 各参数取值与规范 / 官方语义的逐项对照见本对象类 KDoc 的对照表。
     * 要点：`M` 下界为**官方语义值 8192 字节**（非 1 MiB）；`M` / `I` / `P` 上界与 AES `R` 上界
     * 为本仓**更严的 fail-closed 防 DoS 封顶**，均宽于一切合法用户配置，正常文件不受影响。
     * 另有动态堆门槛：请求内存超过 JVM 堆一半时按损坏文件拒绝（分配发生在 Java 堆上）。
     */
    fun validateArgon2Bounds(memoryInBytes: Long, iterations: Long, parallelism: Int, version: Int) {
        if (memoryInBytes < ARGON2_MIN_MEMORY_BYTES || memoryInBytes > ARGON2_MAX_MEMORY_BYTES) {
            throw KdbxCorruptFileException(
                "Argon2 内存参数越界: $memoryInBytes 字节（允许 $ARGON2_MIN_MEMORY_BYTES ~ $ARGON2_MAX_MEMORY_BYTES）"
            )
        }
        if (iterations < 1 || iterations > ARGON2_MAX_ITERATIONS) {
            throw KdbxCorruptFileException("Argon2 迭代参数越界: $iterations（允许 1 ~ $ARGON2_MAX_ITERATIONS）")
        }
        if (parallelism < 1 || parallelism > ARGON2_MAX_PARALLELISM) {
            throw KdbxCorruptFileException("Argon2 并行度越界: $parallelism（允许 1 ~ $ARGON2_MAX_PARALLELISM）")
        }
        if (version != KdfParameters.Argon2.ARGON2_VERSION_10 && version != KdfParameters.Argon2.ARGON2_VERSION_13) {
            // V 由 UInt32 窄化到 Int，高位非零时为负数；按无符号 32 位渲染，
            // 否则日志会打印 "0x-1" 这类不可读的错误消息
            throw KdbxCorruptFileException(
                "不支持的 Argon2 版本: 0x${(version.toLong() and 0xFFFFFFFFL).toString(16)}"
            )
        }
        // 动态内存门槛：请求内存超过 JVM 堆一半时按损坏文件拒绝（分配发生在 Java 堆上）
        val heapCap = Runtime.getRuntime().maxMemory() / 2
        if (memoryInBytes > heapCap) {
            throw KdbxCorruptFileException("Argon2 内存参数超出本设备可用内存上限")
        }
    }

    fun validateAesKdfBounds(rounds: Long) {
        if (rounds < 1 || rounds > AES_KDF_MAX_ROUNDS) {
            throw KdbxCorruptFileException("AES-KDF 轮数越界: $rounds（允许 1 ~ $AES_KDF_MAX_ROUNDS）")
        }
    }
}
