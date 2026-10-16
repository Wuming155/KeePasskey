package com.keepasskey.database.session

import com.keepasskey.database.io.WipableByteArrayOutputStream
import java.io.InputStream

/**
 * `ISSUE-P1-631`：会话**最后确认持久化版本**的库镜像（漂移合并的真实共同基线来源）。
 *
 * 缺陷形态：漂移处置的「合并后保存」把**当前可变树**同时充当 base 与 local，合并器据此
 * 认为本地从未修改——本地新增条目在磁盘缺失时被推断为远端删除、同 ID 本地编辑让磁盘版本
 * 成为唯一修改，随后采用并保存该结果（静默丢失本地修改）。
 *
 * 本类型承载修复所需的真实基线：`revision` 为**会话级持久化修订**（每次确认写盘 / 会话
 * 更换都会前移，清除同样前移——保证「读取基线 → 计算合并 → 提交」窗口内的任意持久化
 * 或会话变化都能被 `adoptDatabaseIfUnchanged(expectedPersistedBaselineRevision = …)`
 * 检出并 fail-closed 中止），`pathIdentifier` 绑定**库身份**（解析基线前必须与当前会话
 * 路径一致，否则视为无可信基线），`bytes` 为该版本**密文快照**（与同步侧 `basecache`
 * 同口径：解析后才成为可合并的树，密文字节本身不承载明文）。
 *
 * 所有权：本对象由 [DatabaseSession.persistedBaselineSnapshot] 返回**克隆副本**，
 * 调用方拥有并负责用毕清零（[wipe]）；不得把其中的字节数组再次外借给缓存或日志。
 */
class PersistedDatabaseBaseline(
    /** 会话持久化修订：确认写盘 / 会话更换 / 基线清除时单调前移 */
    val revision: Long,
    /** 基线所属库路径标识（与当前会话路径一致才可作为合并底版） */
    val pathIdentifier: String,
    /** 该版本的整库密文快照；调用方用毕必须 [wipe] */
    val bytes: ByteArray
) {
    /** 清零密文快照（幂等） */
    fun wipe() {
        bytes.fill(0)
    }
}

/**
 * `ISSUE-P1-631`：打开链路的**旁路捕获流**——将解析器实际消费的字节镜像到内部缓冲，
 * 供开放 / 重载成功后登记为持久化基线。
 *
 * 设计口径：
 * - **旁路不算第二遍读**：字节只被消费一次（解析器读什么就镜像什么），不重读文件、
 *   不重加密；捕获内容与解析内容天然一致（不存在「先解析、后重读」之间的竞态窗口）；
 * - **上限 fail-open（不获取基线，绝不阻断打开）**：[maxCaptureBytes] 是**留存**上限，
 *   不是解析上限——超出即放弃捕获（[capturedBytes] 返回 null），打开本身不受影响，
 *   合并侧按「无可信基线」走保守并集语义；
 * - **失败路径必须 [wipe]**：捕获缓冲是整库密文的第二份副本，与序列化缓冲同属会话派生
 *   敏态数据（见 `WipableByteArrayOutputStream` KDoc），调用方在解析失败 / 取消 / 未采用
 *   路径上都要清零，不得留给 GC；
 * - `skip` 以读代替（保持捕获完整），解析器正常路径不调用 `skip`。
 */
internal class PersistedBaselineCapturingInputStream(
    private val delegate: InputStream,
    private val maxCaptureBytes: Int
) : InputStream() {

    private val sink = WipableByteArrayOutputStream(CAPTURE_INITIAL_BYTES)
    private var overflow = false

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) captureByte(value)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = delegate.read(b, off, len)
        if (count > 0) captureBytes(b, off, count)
        return count
    }

    /** 以读代 skip：保证被跳过的字节同样进入捕获（否则捕获内容不可再解析） */
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val buffer = ByteArray(minOf(n, SKIP_CHUNK_BYTES.toLong()).toInt())
        var remaining = n
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
        return n - remaining
    }

    override fun available(): Int = delegate.available()

    override fun close() {
        delegate.close()
    }

    private fun captureByte(value: Int) {
        if (overflow) return
        if (sink.size() + 1 > maxCaptureBytes) {
            // 超限即整体放弃捕获：不留半份基线（半份字节不可再解析，冒充基线只会误判）
            overflow = true
            sink.wipe()
            return
        }
        sink.write(value)
    }

    private fun captureBytes(b: ByteArray, off: Int, count: Int) {
        if (overflow) return
        if (sink.size() + count > maxCaptureBytes) {
            overflow = true
            sink.wipe()
            return
        }
        sink.write(b, off, count)
    }

    /**
     * 成功路径取回捕获副本（内部缓冲的拷贝；内部缓冲由调用方随后 [wipe] 清零）。
     * 超限 / 无捕获时返回 null（＝本次未登记基线）。
     */
    fun capturedBytes(): ByteArray? = if (overflow || sink.size() == 0) null else sink.toByteArray()

    /** 清零内部缓冲（成功取回后与全部失败路径都必须调用） */
    fun wipe() {
        sink.wipe()
    }

    private companion object {
        /** 初始容量与 ByteArrayOutputStream 默认族一致，按需倍增 */
        const val CAPTURE_INITIAL_BYTES = 32
        const val SKIP_CHUNK_BYTES = 8 * 1024
    }
}

/**
 * `ISSUE-P1-631`：持久化基线留存上限：160 MiB。
 *
 * 取值推导（与 app 侧漂移读取上限 `VaultFileDriftResolve.MAX_DRIFT_READ_BYTES` 同源、独立登记，
 * 两模块不得互相依赖）：`KdbxFile` 解析侧以 128 MiB 解压输出守卫封顶任何可解析库，密文体
 * ≈ 明文体（不压缩）或更小（GZip）+ HMAC 块帧与头部开销（< 1%），故任何可被解析的库其源文件
 * 必远小于 160 MiB。超出即不登记基线（合并侧按「无可信基线」保守并集语义），**绝不阻断打开**。
 */
internal const val MAX_PERSISTED_BASELINE_BYTES: Int = 160 * 1024 * 1024

/**
 * `ISSUE-P1-631`：登记持久化基线的 **best-effort** 收口。
 *
 * 为什么吞：基线是**增强通道**——登记失败（克隆 / 清零时 OOM）的后果是下游按
 * 「无可信基线」走保守并集合并（已定义、可接受的状态），而向上抛出会让一次**已成功**的
 * 打开 / 保存 / 换密被 catch 清理路径回收为失败（用户看到解锁失败、脏树被清），代价远大于收益。
 * 失败不改变调用方结果语义，也不在此伪造成功：基线确实未登记（[SessionCore.persistedBaselineClone]
 * 为 null），该状态对下游可观测。
 */
internal fun SessionCore.retainPersistedBaselineBestEffort(bytes: ByteArray?, pathIdentifier: String?) {
    try {
        retainPersistedBaseline(bytes, pathIdentifier)
    } catch (_: Throwable) {
        // 有意吞掉：见 KDoc。数据库模块无日志面，不引入额外依赖。
    }
}
