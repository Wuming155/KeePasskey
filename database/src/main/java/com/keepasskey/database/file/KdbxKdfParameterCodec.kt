package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.crypto.VariantDictionary
import com.keepasskey.database.exception.KdbxCorruptFileException

/**
 * KDF 参数的变体字典编解码与边界裁决（ISSUE-P3-29：自 `KdbxHeader.kt` 拆出，纯结构性拆分）。
 *
 * 原实现逐字迁移，行为零变更；`KdbxHeader` 保留同名门面委托，既有调用方（含单测）零改动。
 *
 * 上界校验对照 KeePassDX Limits / KeePassXC 参数封顶语义：文件中的 VariantDictionary 参数
 * 在进入计算前必须先通过边界裁决，否则恶意构造的 KDBX 可声明 1TB 级 Argon2 内存（分配期 OOM 崩溃）、
 * 2^60 级 AES 轮数或迭代数（无限期占用 CPU 线程）造成拒绝服务。
 * 上界取值宽于一切合法用户配置（合法范围见 KdfBenchmark / 各引擎默认值），正常文件不受影响。
 */
internal object KdbxKdfParameterCodec {

    /** Argon2 内存下界：官方最小合法工作区（1 MB） */
    private const val ARGON2_MIN_MEMORY_BYTES = 1024L * 1024

    /** Argon2 内存上界：远超一切合法用户配置的绝对封顶（4 GiB），防恶意文件分配期 OOM */
    private const val ARGON2_MAX_MEMORY_BYTES = 4L * 1024 * 1024 * 1024

    /** Argon2 迭代上界（合法配置通常 ≤ 数千轮） */
    private const val ARGON2_MAX_ITERATIONS = 1L shl 24

    /** Argon2 并行度上界（合法配置通常 ≤ CPU 核数） */
    private const val ARGON2_MAX_PARALLELISM = 64

    /** AES-KDF 轮数上界：合法偏执配置通常 ≤ 1 亿轮，此处封顶 2^28 防无限期占用 CPU */
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
                // 对齐 KeePassDX / 官方规范：P 与 V 在 KDBX4 变体字典中以 UInt32 类型写出，按 UInt32 读取
                val p = vd.getUInt32("P")?.toInt() ?: 2
                val m = vd.getUInt64("M") ?: (64L * 1024 * 1024)
                val i = vd.getUInt64("I") ?: 2L
                val v = vd.getUInt32("V")?.toInt() ?: KdfParameters.Argon2.ARGON2_VERSION_13
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
     * KDF 参数上界校验（对照 KeePassDX Limits / KeePassXC 参数封顶语义）。
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
            throw KdbxCorruptFileException("不支持的 Argon2 版本: 0x${version.toString(16)}")
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
