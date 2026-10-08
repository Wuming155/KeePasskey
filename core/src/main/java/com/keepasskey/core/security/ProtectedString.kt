package com.keepasskey.core.security

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * 保护字符串模型。
 * 用于保存主密码、字段密码等敏感信息。
 * 内部优先采用字节数组承载，支持显式清零，杜绝常驻 GC 堆字符串池。
 *
 * P3 整改（对齐 KeePassDX `protectInMemory`）：`isProtected = true` 的实例在堆内存中以
 * **密文形态驻留**（经 [InMemoryCipher] 随机化加密，IV 随实例存储），内存 dump 与字符串扫描
 * 无法直接读出明文；仅 [readChars]/[readUtf8]/[readString] 读取的瞬间解密出临时副本，
 * 副本用毕立即擦除。
 *
 * 2026-09 加解密审查整改：等值语义与加密解耦——加密每次使用随机 IV（同明文两次密封的
 * 密文不同，堆中不存在可跨实例关联的确定性密文）；equals/hashCode 改为比较
 * **HMAC 等值标签**（常时时间比较），同步变更检测与三方合并的比较路径依然不解密、
 * 不物化明文。
 */
class ProtectedString(
    val isProtected: Boolean = true,
    bytes: ByteArray
) : Closeable {

    /** 驻留态：isProtected 时为密文，否则为明文副本 */
    private val data: ByteArray

    /** 驻留解密 IV（与密文一并驻留；非保护或空值时为 null） */
    private val memoryIv: ByteArray?

    /** 等值标签：HMAC-SHA256(eqKey, 明文)（非保护或空值时为 null，equals/hashCode 使用） */
    private val memoryTag: ByteArray?

    /**
     * 已清零标志。
     *
     * `ISSUE-P2-534`：`@Volatile` 是**必需**的，不是优化——本实例是跨线程共享的可变引用
     * （会话层在主线程/Default/IO 上擦除，展示面在 `Dispatchers.Default` 上读取），
     * 无 `volatile` 时读侧可能长期看不到写侧置位，`cleared` 观测与展示读口的降级判据都会失效。
     * 注意它**不能**单独构成读取的原子性保证：并发擦除下仍可能读到「正在被填零」的缓冲，
     * 故 [plainBytes] 另有等值标签复核（fail-closed）。
     */
    @Volatile
    private var isCleared = false

    /**
     * 是否已清零（只读观测，不含明文）。
     * ISSUE-P2-287：调用方据此对已擦实例**降级**（如改提示「请重新生成」），
     * 而非依赖 fail-fast 异常做流程控制（异常仍保留，作为消费已擦实例的最后防线）。
     */
    val cleared: Boolean
        get() = isCleared

    init {
        if (isProtected && bytes.isNotEmpty()) {
            val sealed = InMemoryCipher.seal(bytes)
            memoryIv = sealed.iv
            memoryTag = sealed.tag
            data = sealed.data
        } else {
            memoryIv = null
            memoryTag = null
            data = bytes.clone()
        }
    }

    constructor(text: String, isProtected: Boolean = true) : this(
        isProtected = isProtected,
        bytes = text.toByteArray(StandardCharsets.UTF_8),
        owned = true
    )

    constructor(chars: CharArray, isProtected: Boolean = true) : this(
        isProtected = isProtected,
        bytes = charsToUtf8(chars),
        owned = true
    )

    /**
     * P0-7 整改：自有明文中间量构造通道。
     * String / CharArray 便捷构造在内部生成的 UTF-8 明文副本归本构造通道所有——
     * 经主构造完成密文驻留（或明文克隆）后在此立即清零，杜绝明文副本滞留堆内等待 GC。
     * 主构造的 bytes 入参保持既有借用语义（归调用方所有，不由本类清零）。
     */
    private constructor(isProtected: Boolean, bytes: ByteArray, owned: Boolean) : this(
        isProtected = isProtected,
        bytes = bytes
    ) {
        check(owned) { "仅自有中间量允许走此构造通道" }
        Arrays.fill(bytes, 0.toByte())
    }

    val length: Int
        get() {
            checkNotCleared()
            // CTR 无填充：密文长度 == 明文长度
            return data.size
        }

    val isEmpty: Boolean
        get() = length == 0

    /**
     * 读取字符数组副本，调用方需在使用完毕后显式清零该 CharArray
     */
    fun readChars(): CharArray {
        checkNotCleared()
        val plain = plainBytes()
        return try {
            val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain))
            val chars = CharArray(cb.remaining())
            cb.get(chars)
            // P0-7 整改：解码器内部 CharBuffer 同样承载过明文，一并清零，
            // 明文副本仅存活于返回给调用方的 CharArray
            if (cb.hasArray()) Arrays.fill(cb.array(), '0')
            chars
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }

    /**
     * 读取 UTF-8 字节数组副本，调用方需在使用完毕后显式清零该 ByteArray
     */
    fun readUtf8(): ByteArray {
        checkNotCleared()
        return plainBytes()
    }

    /**
     * [readUtf8] 的**展示面**安全变体（`ISSUE-P0-531`）：实例已清零时返回 null（＝「不可读」），
     * 不抛异常、也**不**返回空数组——空数组会被下游误判为「字段存在但内容为空」。
     *
     * 使用边界同 [readStringForDisplay]：仅限非持久化的展示 / 即时计算面（如列表页 TOTP 出码）。
     */
    fun readUtf8ForDisplay(): ByteArray? =
        try {
            readUtf8()
        } catch (_: IllegalStateException) {
            null
        }

    /**
     * 将保护值转为 String。注意：一旦调用，明文字符串将驻留 JVM 堆内存，请仅在必要交互边界使用。
     */
    fun readString(): String {
        checkNotCleared()
        val plain = plainBytes()
        return try {
            String(plain, StandardCharsets.UTF_8)
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }

    /**
     * 展示面安全读取（`ISSUE-P0-531`）：实例**已清零**时返回 [fallback]（默认空串），不抛异常。
     *
     * **使用边界（违反即事故）**：仅允许**非持久化的展示 / 投影消费面**（UI 列表与详情投影、卡面字段、
     * 条目即时出码、检索 / 差异展示）使用。写路径（序列化 / 保存 / 合并 / 导出 / 加解密 / 凭据下发）
     * **必须**继续走 [readString] 的 fail-fast —— 「读到已擦即失败」在那里是数据完整性的最后防线，
     * 就地降级会把空值写进用户的库。
     *
     * 机检 `tools/doc/check_projection_read_safety.py` **双向**把关：① 登记在案的投影面文件不得出现裸
     * fail-fast 读（含 `KdbxEntry.title` 等 getter 与 `useChars` / `useUtf8`）；② 本读口的**每一个调用点
     * 所在文件都必须在白名单内登记**（fail-closed：新调用点未登记即红）。清单**不外推**——它只覆盖
     * 登记过的文件与调用点，不构成「全仓已无裸读」的证明。
     *
     * 存在理由：本类是**可变的共享引用**，会话层在整树替换时会对「被替换下线」的实例就地清零
     * （`KdbxGroup.clearSupersededSensitiveData`，身份集合判定）。而 UI 投影链
     * （`databaseFlow.map { ... }.flowOn(Dispatchers.Default)`）与该擦除点**不共享锁**，存在
     * 「旧值已分发 → 擦除发生 → 投影才执行」的调度窗口；此时抛异常会逃逸到协程根（全仓无全局
     * `CoroutineExceptionHandler`）直接杀进程（`ISSUE-P0-531` 真机实测：`DefaultDispatcher-worker` 上
     * `KdbxEntry.getUserName` → 本方法 → 进程闪退）。
     * 降级后的表现是「短暂显示空值」，语义上**正确**：被替换下线的数据本就不该再展示。
     */
    fun readStringForDisplay(fallback: String = ""): String =
        try {
            readString()
        } catch (_: IllegalStateException) {
            // ISSUE-P2-534：判据**只有** readString 内部那一次 checkNotCleared（外加 plainBytes 的
            // 等值标签复核）。整改前是「先查 isCleared → 再 readString」的两步式，两步之间不是原子的：
            // 标志尚不可见时仍会走到 readString 抛异常（承诺的「不抛」不成立）；更糟的是标志不可见
            // 而缓冲已被并发填零时，`unseal` 会解出**垃圾明文**且不报错。故此处以 try/catch 包住唯一
            // 判据点，与 plainBytes 的标签复核共同保证「要么读到正确明文，要么降级」——绝不返回垃圾、绝不抛。
            fallback
        }

    /**
     * 安全闭包使用 CharArray，并在退出时自动清零
     */
    inline fun <R> useChars(block: (CharArray) -> R): R {
        val chars = readChars()
        try {
            return block(chars)
        } finally {
            Arrays.fill(chars, '0')
        }
    }

    /**
     * 安全闭包使用 UTF-8 ByteArray，并在退出时自动清零
     */
    inline fun <R> useUtf8(block: (ByteArray) -> R): R {
        val bytes = readUtf8()
        try {
            return block(bytes)
        } finally {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    /**
     * 显式擦除敏感内存（密文、IV 与等值标签一并清零）。
     * P3-10 整改：[EMPTY] 为全局共享单例，对其 clear 一律 no-op——
     * 防止任一调用方把共享空实例置为已清零态后污染后续引用者（equals/close 语义异常）。
     */
    fun clear() {
        if (this === EMPTY) return
        if (!isCleared) {
            Arrays.fill(data, 0.toByte())
            memoryIv?.fill(0)
            memoryTag?.fill(0)
            isCleared = true
        }
    }

    override fun close() {
        clear()
    }

    private fun checkNotCleared() {
        check(!isCleared) { "ProtectedString 已经清零，禁止继续访问" }
    }

    /**
     * 解密 / 克隆出明文新副本（调用方用毕负责清零）。
     *
     * `ISSUE-P2-534`：受保护实例在解密后**复核等值标签**。驻留加密是 AES/CTR（**无认证标签**），
     * 而 [clear] 是就地清零（先填零 `data`、后置 [isCleared]）；并发下读取者可能通过 `checkNotCleared`
     * 却读到**正在被填零**的密文 —— CTR 只会把它解成密钥流垃圾明文而不报错。不复核标签就会
     * ① 静默返回垃圾（污染展示面）；② 绕过 fail-fast（写路径可能据此写出错误数据）。
     * 复核不通过即清零该缓冲并按「已清零 / 已损坏」如实 fail-closed，使**所有**读路径（含展示读口
     * 的 try/catch）收敛到唯一判据。代价：每次读取多一次 HMAC-SHA256（与已有的 CTR 解密同量级）。
     *
     * 残余面（如实声明）：非保护实例（`memoryIv == null`，如外部工具写入的明文自定义字段）**没有**标签，
     * 并发填零只会读到「部分明文」而非垃圾串；该形态由 [isCleared] 的 `@Volatile` 可见性覆盖，
     * 属已接受的小窗口，不再叠加同步原语（读路径性能优先）。
     */
    private fun plainBytes(): ByteArray {
        val iv = memoryIv ?: return data.clone()
        val plain = InMemoryCipher.unseal(iv, data)
        val tag = memoryTag
        if (tag == null || !InMemoryCipher.tagsEqual(InMemoryCipher.equalityTag(plain), tag)) {
            Arrays.fill(plain, 0.toByte())
            throw IllegalStateException("ProtectedString 已经清零或密文已损坏，禁止继续访问")
        }
        return plain
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtectedString) return false
        if (isCleared || other.isCleared) return false
        if (isProtected != other.isProtected) return false
        // 等值标签（HMAC）常时时间比较：相同明文恒得相同标签，不解密、不物化明文；
        // 空值/非保护实例走明文副本路径（数据为空或非敏感，直接内容比较）
        val a = memoryTag
        val b = other.memoryTag
        return if (a != null && b != null) {
            InMemoryCipher.tagsEqual(a, b)
        } else {
            data.contentEquals(other.data)
        }
    }

    override fun hashCode(): Int {
        if (isCleared) return 0
        var result = isProtected.hashCode()
        // 等值标签为 HMAC 伪随机输出，直接作为哈希输入安全且分布均匀
        result = 31 * result + (memoryTag ?: data).contentHashCode()
        return result
    }

    override fun toString(): String {
        // P2-4 整改：toString 绝不返回明文——无论 isProtected 与否仅返回类型与长度描述。
        // 防止日志、字符串模板、数据类 toString 等隐式转换物化敏感内容（含
        // MemoryProtection 允许 ProtectPassword=False 的非保护字段）；
        // 明文一律经显式 readChars()/readUtf8()/readString() 按需读取
        return "ProtectedString(protected=$isProtected, len=${if (isCleared) 0 else data.size})"
    }

    companion object {
        val EMPTY = ProtectedString(isProtected = false, bytes = ByteArray(0))

        private fun charsToUtf8(chars: CharArray): ByteArray {
            val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
            val bytes = ByteArray(bb.remaining())
            bb.get(bytes)
            // P0-7 整改：编码器内部 ByteBuffer 同样承载过明文，一并清零
            if (bb.hasArray()) Arrays.fill(bb.array(), 0.toByte())
            return bytes
        }
    }
}
