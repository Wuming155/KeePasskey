package com.keepasskey.crypto.cipher

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * CBC + PKCS#7 解密输入流（ISSUE-P3-35）。
 *
 * **错误语义以实测的 `javax.crypto.CipherInputStream` 基线为准**（本类最容易出错、也最容易被
 * 忽略的一点）。ISSUE-P3-35 的验收标准要求「先实测基线再对齐」，`CbcStreamFramingTest`
 * 即以真实 JCE 为参照逐例断言，结论（2026-09-10 于本机 JDK 实测）为：
 *
 * 1. **填充非法** → 基线在读到尾部时抛 `IOException`（cause 为 `BadPaddingException`），
 *    **不是**静默 EOF；
 * 2. **密文长度非分组整数倍 / 空输入** → 基线抛 `IOException`（cause 为
 *    `IllegalBlockSizeException`）；
 * 3. **底层流抛 `IOException`** → 原样上抛。
 *
 * 本类三者一致：均在 `read` 到达尾部时抛出 `IOException`。这一点对既有代码是**必需的**——
 * `KdbxCipherKeyResolver.isPlausibleInnerHeaderPrefix` 正是以
 * `catch (_: java.io.IOException)` 来截断「用首块做解密探针」时的收尾错误并保留已解出前缀；
 * 若本类改为静默 EOF，该探针的行为将随之改变。
 *
 * 实现要点：**恒扣留最后一个整分组**（解密侧链值取密文，故读取过程中即可解出并交付除末块外
 * 的全部明文），仅在真正到达尾部时对该块去填充。
 *
 * 已知与基线的差异（如实声明，方向为**不影响正确性**）：若调用方**未读到尾部**即调用
 * [close]，基线可能仍在 `close` 内触发 `doFinal` 的填充校验并抛异常，本类不做该收尾校验。
 * 该路径下双方都不交付任何未校验明文，故不构成正确性缺口；不做收尾校验是**有意的保守选择**：
 * 对合法库而言「提前 close」意味着解析器已按 GZip 结构自然停止，此时强行校验填充
 * 反而可能对正常数据误报。
 *
 * **双形态注入（`ISSUE-P3-198`）**：[directTransform] 非空 ⇒ 走 **direct 直扣形态**——
 * 密文载荷经「堆→堆外一次 put」后对等容量视图**原地**变换，明文在堆外就地区**交付即归零**
 * （每块 2 次拷贝、零分配；旧形态为 4 次拷贝 + 每 refill 一次 64 KiB 结果数组分配）；
 * 为 `null` ⇒ 走既有 byte[] 形态（JCE 兜底 / 宿主纯 JVM 注入，逻辑逐字保留）。
 * 两种形态的扣留末分组、三类 fail-closed 错误语义与交付次序**完全一致**。
 * （本类自 `CbcStreams.kt` 拆分落位，段内新增 direct 分支随 `ISSUE-P3-198` 批次。）
 */
internal class CbcDecryptingInputStream(
    private val source: InputStream,
    private val key: ByteArray,
    iv: ByteArray,
    /** byte[] 形态变换（与 [directTransform] **二选一**，见 [CbcBlockTransform] KDoc）。 */
    private val transform: CbcBlockTransform?,
    private val chunkSize: Int = CBC_STREAM_BUFFER_SIZE,
    /**
     * 由本流**自持所有权**的秘密缓冲：原生路径传入的 `key.copyOf()`，[close] 时确定性擦除。
     *
     * **契约背景（§147 整改，实测回归）**：`KdbxFile` / `KdbxCipherKeyResolver` 在建立解密流
     * 之后**立即擦除**调用方持有的旧派生密钥数组（其依据是「`SecretKeySpec` 构造时已克隆密钥」，
     * 该假设只对 JCE 路径成立）。原生路径的分组变换**惰性**读取密钥（每块一次 JNI 调用），
     * 沿用同一时序将以全零密钥解密——实测表现为末块 PKCS#7 校验失败 → `IOException`。
     * 故原生路径必须把 `key.copyOf()` 的**所有权**交给本流，由本流负责擦除；
     * 调用方原数组不在本流所有权内，**不得**在此擦除。
     */
    private val ownedSecrets: List<ByteArray> = emptyList(),
    /** direct 形态变换（与 [transform] **二选一**，见 [CbcDirectBlockTransform] KDoc）。 */
    private val directTransform: CbcDirectBlockTransform? = null
) : InputStream() {

    init {
        require(chunkSize > Pkcs7.BLOCK_SIZE && chunkSize % Pkcs7.BLOCK_SIZE == 0) {
            "chunkSize 必须为分组长度的整数倍且大于一个分组: $chunkSize"
        }
        require(iv.size == Pkcs7.BLOCK_SIZE) { "IV 必须为 ${Pkcs7.BLOCK_SIZE} 字节" }
        require((transform == null) != (directTransform == null)) {
            "transform 与 directTransform 必须二选一（不得同时为空或同时非空）"
        }
    }

    /** CBC 链值：随每次分组变换原地推进（由变换实现写回）。 */
    private val chain: ByteArray = iv.copyOf()

    /**
     * `ISSUE-P3-177`：读取缓冲**实例级复用**。布局：
     * `[0, pendingLength)` = 上一轮扣留的末分组（密文），紧随其后是本轮读入的载荷。
     *
     * 原实现每块都新建 `chunk`（64 KiB）与 `concat` 的合并数组（≈64 KiB+16）——一个 10 MiB 库
     * 约 160 个块 ⇒ 约 320 次 64 KiB 分配 + 全部整块拷贝。
     */
    private val buffer = ByteArray(chunkSize + Pkcs7.BLOCK_SIZE)

    /** 扣留于 [buffer] 头部的末分组长度（0 或一个分组） */
    private var pendingLength = 0

    /**
     * `ISSUE-P3-177`：交给变换的**复用**输入缓冲，长度恒为 [chunkSize]（稳态满块载荷等长）。
     *
     * 契约说明：byte[] 形态的变换**必须返回独立结果数组**（不得原地返回入参）——这一点
     * 既有实现本已依赖（[transformBlocks] 的 `finally` 会对入参切片清零；若变换原地返回，
     * 明文会被自己抹掉），本类据此复用该输入缓冲，并以 [requireDistinctResult] 显式校验，
     * 避免未来出现原地实现时静默产出零明文。**direct 形态不适用该契约**（原地变换正是
     * 其设计，见 [CbcDirectBlockTransform] KDoc）。
     */
    private val bodyScratch = ByteArray(chunkSize)

    /**
     * `ISSUE-P3-198`：direct 形态的**明文就地区**（堆外）。容量 = [chunkSize] + 分组
     * （EOF 收尾的尾段最长为 chunkSize + 扣留分组）。单次分配、整流复用，交付即归零、
     * close 整段兜底归零（擦除责任在本流，堆外不受 GC 管辖）。byte[] 形态恒为 `null`。
     */
    private val direct: ByteBuffer? =
        if (directTransform != null) ByteBuffer.allocateDirect(chunkSize + Pkcs7.BLOCK_SIZE) else null

    /** `[0, plainLength)` 为 [direct] 内的待交付明文区间，`plainPos` 为已交付前缀。 */
    private var plainLength = 0
    private var plainPos = 0

    /** 待交付明文缓冲（byte[] 形态的热路径与两种形态的 EOF 收尾段共用）。 */
    private var out: ByteArray = EMPTY
    private var outPos = 0
    private var eofDone = false
    private var closed = false

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        if (count <= 0) return -1
        val value = single[0].toInt() and 0xFF
        Arrays.fill(single, 0)
        return value
    }

    override fun read(data: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        require(off >= 0 && len <= data.size - off) { "非法的读取区间 off=$off len=$len" }
        while (true) {
            if (plainPos < plainLength) {
                // direct 形态热路径：从堆外就地区交付，交付即归零
                val d = direct!!
                val count = minOf(len, plainLength - plainPos)
                d.position(plainPos)
                d.get(data, off, count)
                d.wipeRange(plainPos, plainPos + count)
                plainPos += count
                return count
            }
            if (outPos < out.size) {
                val count = minOf(len, out.size - outPos)
                System.arraycopy(out, outPos, data, off, count)
                outPos += count
                return count
            }
            if (closed) throw IOException("流已关闭")
            if (eofDone || !refill()) return -1
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        wipeBuffers()
        Arrays.fill(buffer, 0)
        Arrays.fill(bodyScratch, 0)
        // 堆外明文区整段兜底归零（残留未交付明文在此确定性擦除）
        direct?.let { d ->
            d.wipeRange(0, d.capacity())
        }
        pendingLength = 0
        Arrays.fill(chain, 0)
        // 自持密钥副本（原生路径）随之确定性擦除；调用方原数组不在所有权内（不受影响）
        ownedSecrets.forEach { Arrays.fill(it, 0) }
        source.close()
    }

    /** 尝试产出一段明文；返回 `false` 表示流已结束（含上述三类 fail-closed 情形）。 */
    private fun refill(): Boolean {
        if (eofDone) return false
        wipeBuffers()

        val read = readUpTo(pendingLength)
        val filled = pendingLength + read

        if (read < chunkSize) {
            eofDone = true
            pendingLength = 0
            return finishAtEof(filled)
        }

        // 尾部未到：扣留最后一个整分组，其余立即解密交付
        val keep = filled - Pkcs7.BLOCK_SIZE
        if (directTransform != null) {
            deliverAlignedDirect(keep)
        } else {
            deliverAlignedHeap(keep)
        }
        pendingLength = Pkcs7.BLOCK_SIZE
        // 原地的末分组前移，并把本块载荷区的密文清零（保留既有清零纪律；不得覆盖前移后的末分组）
        System.arraycopy(buffer, filled - Pkcs7.BLOCK_SIZE, buffer, 0, Pkcs7.BLOCK_SIZE)
        Arrays.fill(buffer, Pkcs7.BLOCK_SIZE, filled, 0)
        return true
    }

    /** direct 形态（`ISSUE-P3-198`）：`[0, keep)` 拷入堆外后原地变换（put 路径统一覆盖
     *  稳态与非稳态，原 bodyScratch 双分支在此形态下不需要），明文经堆外就地区交付。 */
    private fun deliverAlignedDirect(keep: Int) {
        val d = direct!!
        val transformDirect = requireNotNull(directTransform) { "两种变换形态必须二选一" }
        d.clear()
        d.put(buffer, 0, keep)
        d.flip()
        transformDirect(key, chain, d.slice())
        plainLength = keep
        plainPos = 0
    }

    /** byte[] 形态（逐字保留原逻辑）：稳态复用 [bodyScratch]，非等长沿用一次分配路径。 */
    private fun deliverAlignedHeap(keep: Int) {
        val byteTransform = requireNotNull(transform) { "两种变换形态必须二选一" }
        out = if (keep == chunkSize) {
            // 稳态满块（已扣留一个分组）⇒ 载荷长度恒为 chunkSize：复用输入缓冲，零分配
            System.arraycopy(buffer, 0, bodyScratch, 0, chunkSize)
            requireDistinctResult(byteTransform(key, chain, bodyScratch))
        } else {
            // 首块（尚未扣留，载荷为 chunkSize - 16）等非等长情形：沿用一次分配路径
            transformBlocks(buffer, 0, keep)
        }
        outPos = 0
    }

    /** EOF 收尾：三类 fail-closed 判定后按形态解密末段。 */
    private fun finishAtEof(tailLength: Int): Boolean {
        if (tailLength == 0) {
            // 基线：CipherInputStream 在空输入上由 doFinal 抛 IllegalBlockSizeException → IOException
            throw IOException("CBC 解密流：密文为空，无法校验 PKCS#7 填充")
        }
        if (tailLength % Pkcs7.BLOCK_SIZE != 0) {
            // 基线：长度非分组整数倍 → IllegalBlockSizeException → IOException
            Arrays.fill(buffer, 0, tailLength, 0)
            throw IOException("CBC 解密流：密文长度 $tailLength 非 ${Pkcs7.BLOCK_SIZE} 的整数倍")
        }
        val keep = tailLength - Pkcs7.BLOCK_SIZE
        return if (directTransform != null) {
            finishTailDirect(tailLength, keep)
        } else {
            finishTailHeap(tailLength, keep)
        }
    }

    /** direct 形态收尾（`ISSUE-P3-198`）：头段 `[0, keep)` 与末块 `[keep, tailLength)`
     *  各自原地变换；头段经堆外就地区交付，末块明文拷出（16 字节，一次性）去填充。 */
    private fun finishTailDirect(tailLength: Int, keep: Int): Boolean {
        val d = direct!!
        val transformDirect = requireNotNull(directTransform) { "两种变换形态必须二选一" }
        val lastPlain = ByteArray(Pkcs7.BLOCK_SIZE)
        val stripped: ByteArray?
        try {
            d.clear()
            d.put(buffer, 0, tailLength)
            d.flip()
            if (keep > 0) {
                val headDup = d.duplicate()
                headDup.clear()
                headDup.limit(keep)
                transformDirect(key, chain, headDup.slice())
            }
            val lastDup = d.duplicate()
            lastDup.clear()
            lastDup.position(keep)
            lastDup.limit(tailLength)
            transformDirect(key, chain, lastDup.slice())
            d.position(keep)
            d.get(lastPlain, 0, Pkcs7.BLOCK_SIZE)
            d.wipeRange(keep, tailLength)
            stripped = Pkcs7.unpad(lastPlain)
        } finally {
            Arrays.fill(lastPlain, 0)
            Arrays.fill(buffer, 0, tailLength, 0)
        }
        if (stripped == null) {
            // 基线：填充非法 → BadPaddingException → IOException（先归零堆外残留明文再抛）
            d.wipeRange(0, keep)
            throw IOException("CBC 解密流：PKCS#7 填充非法（密钥错误或数据被篡改）")
        }
        plainLength = keep
        plainPos = 0
        out = stripped
        outPos = 0
        return keep > 0 || stripped.isNotEmpty()
    }

    /** byte[] 形态收尾（逐字保留原实现）。 */
    private fun finishTailHeap(tailLength: Int, keep: Int): Boolean {
        val head = transformBlocks(buffer, 0, keep)
        val lastPlain = transformBlocks(buffer, keep, tailLength)
        val stripped = Pkcs7.unpad(lastPlain)
        Arrays.fill(lastPlain, 0)
        Arrays.fill(buffer, 0, tailLength, 0)

        if (stripped == null) {
            // 基线：填充非法 → BadPaddingException → IOException
            Arrays.fill(head, 0)
            throw IOException("CBC 解密流：PKCS#7 填充非法（密钥错误或数据被篡改）")
        }
        out = if (head.isEmpty()) stripped else head + stripped
        outPos = 0
        return out.isNotEmpty()
    }

    /** 变换结果不得与复用输入别名（见 [bodyScratch] 的契约说明；仅 byte[] 形态适用）。 */
    private fun requireDistinctResult(result: ByteArray): ByteArray {
        check(result !== bodyScratch) {
            "CBC 变换实现必须返回独立结果数组（不得原地返回入参）：复用输入缓冲会在下一块被覆盖"
        }
        return result
    }

    /** 变换 `[from, to)` 区间（长度须为分组整数倍），返回新数组；空区间直接返回空（byte[] 形态）。 */
    private fun transformBlocks(source: ByteArray, from: Int, to: Int): ByteArray {
        if (to <= from) return EMPTY
        val slice = source.copyOfRange(from, to)
        return try {
            requireNotNull(transform) { "两种变换形态必须二选一" }(key, chain, slice)
        } finally {
            Arrays.fill(slice, 0)
        }
    }

    /**
     * 读取至缓冲填满「载荷区」或流结束；返回实际读取字节数（`< chunkSize` 即已到尾部）。
     * `start` 为扣留末分组占用的头部长度，载荷最多读 [chunkSize] 字节。
     */
    private fun readUpTo(start: Int): Int {
        var total = 0
        while (total < chunkSize) {
            val count = source.read(buffer, start + total, chunkSize - total)
            if (count <= 0) {
                // 规范上 len > 0 时不得返回 0；真出现则按结束处理（fail-closed，防自旋）
                break
            }
            total += count
        }
        return total
    }

    private fun wipeBuffers() {
        if (plainPos < plainLength) {
            direct!!.wipeRange(plainPos, plainLength)
        }
        plainLength = 0
        plainPos = 0
        if (out.isNotEmpty()) Arrays.fill(out, 0)
        out = EMPTY
        outPos = 0
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
