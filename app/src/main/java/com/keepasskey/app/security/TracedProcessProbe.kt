package com.keepasskey.app.security

import com.keepasskey.core.log.AppLog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `/proc/self/status` 的 `TracerPid` 解析（**ISSUE-P3-83**，纯函数，JVM 可测）。
 *
 * ## 为什么需要它
 *
 * 既有 [RuntimeIntegrityDetector] 只看 `Debug.isDebuggerConnected()` / `waitingForDebugger()`、
 * `/proc/self/maps` 特征串与磁盘路径。而 `ptrace` 系（`process_vm_readv` / `/proc/<pid>/mem`
 * 注入、Frida 的 inject 模式）**不产生新映射、也不置 JDWP 调试位**——上述探测对其无感。
 * `TracerPid` 是内核为**每个被 ptrace 的进程**在 `status` 中如实填写的字段，
 * 因此「非 0 即有人正在 trace 本进程」是**平台直接给出**的信号，不需要任何权限。
 *
 * ## 边界（如实声明，不得读作「阻断承诺」）
 *
 * 本信号属**提高成本**项：它拦不住不依赖 ptrace 的攻击（同 UID 读取、root 直读内存、
 * 内核模块），且攻击者可通过 hook `open`/`read` 伪造一行 `TracerPid:\t0`。
 * 与 `ISSUE-P3-83` 的定位一致——**不改变设计边界**，只是让「最省事的那条注入路径」留下痕迹。
 */
object ProcTracerPid {

    /** `status` 中承载 tracer 进程号的字段名（内核格式为 `TracerPid:\t<N>`） */
    private const val FIELD = "TracerPid:"

    /**
     * 从 `/proc/self/status` 内容解析 `TracerPid`。
     *
     * @return `0` = 未被 trace；`> 0` = 正被该 pid trace；**null = 无法判定**
     *   （字段缺失 / 值非数字）。调用方必须显式决定「无法判定」的处置，
     *   不得把 null 静默等同于 `0`。
     */
    fun parse(statusContent: String): Int? {
        val line = statusContent.lineSequence()
            .firstOrNull { it.startsWith(FIELD) }
            ?: return null
        return line.substring(FIELD.length).trim().toIntOrNull()
    }
}

/**
 * 实时 tracer 探测抽象（ISSUE-P3-83）。
 *
 * 生产实现 [ProcStatusTracedProcessProbe] 每次调用**同步**读取 `/proc/self/status`；
 * JVM 单测注入固定值实现。之所以做成接口：门控路径需要可测的「被 trace」分支。
 */
interface TracedProcessProbe {
    /** @return `> 0` 表示正被 trace；`0` 表示未；null 表示无法判定 */
    fun tracerPid(): Int?
}

/**
 * [TracedProcessProbe] 的生产实现：同步读取 `/proc/self/status`（ISSUE-P3-83）。
 *
 * ## 为什么是「同步读」而不是纳入周期重扫
 *
 * `TracerPid` 是**时变且瞬时**的信号：周期性扫描（[RuntimeIntegrityDetector] 的重扫间隔）
 * 完全可能错过「附加 → 读取 → 脱离」这一窗口。AC① 因此要求**关键解密路径前后同步读取**，
 * 本实现即那一次同步读取的原语。单次读取成本为一次 `/proc` 小文件读（KB 级），
 * 施加在解锁 / 填充这类低频入口上可接受。
 *
 * ## 读取失败与「无法判定」的处置（**fail-open，明示取舍**）
 *
 * `/proc/self/status` 对进程自身恒可读（SELinux `proc_self`），故失败属异常 ROM / 受限环境。
 * 此时返回 `null`，由调用方按「未检测到」处理并落脱敏日志——**不 fail-closed**：
 * 本项是提高成本项而非阻断承诺（见 [ProcTracerPid] KDoc），若因读不到就禁用生物快速解锁与
 * 自动填充，等于用一个「纸面加固」换掉整机可用性。该取舍与 `ISSUE-P3-83` 的「非阻断承诺」
 * 定级一致，并由测试锁定。
 */
@Singleton
class ProcStatusTracedProcessProbe @Inject constructor() : TracedProcessProbe {

    override fun tracerPid(): Int? = try {
        ProcTracerPid.parse(readStatusBounded())
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取 /proc/self/status 失败，TracerPid 按无法判定处理", t)
        null
    }

    /**
     * 有界读取：`/proc` 伪文件 `length()` 恒为 0，**不能**据此分配缓冲区，
     * 故固定上限读取（`status` 实际约 1–2 KB，8 KiB 留有充分余量），避免任何无界读风险。
     */
    private fun readStatusBounded(): String {
        val file = File(STATUS_PATH)
        return file.inputStream().use { input ->
            val buffer = ByteArray(MAX_STATUS_BYTES)
            val read = input.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
        }
    }

    private companion object {
        const val TAG = "TracedProcessProbe"
        const val STATUS_PATH = "/proc/self/status"

        /** 有界读取上限（8 KiB；`status` 实际约 1–2 KB） */
        const val MAX_STATUS_BYTES = 8 * 1024
    }
}
