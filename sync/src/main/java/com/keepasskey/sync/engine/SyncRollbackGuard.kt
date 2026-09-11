package com.keepasskey.sync.engine

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/**
 * 防回滚裁决（ISSUE-P2-18）。
 */
sealed interface RollbackVerdict {

    /** 与设备侧当前已接受内容逐字节一致（未变化） */
    data object Unchanged : RollbackVerdict

    /** 全新内容：允许接受并记录（其他官方客户端写入亦属此类，不误报） */
    data object Accept : RollbackVerdict

    /** 与设备侧**曾接受过**的历史版本逐字节一致 → 判为回退/重放，拒绝应用 */
    data object ReplayDetected : RollbackVerdict
}

/**
 * 同步防回滚守卫（ISSUE-P2-18，纯 Kotlin，JVM 可测）。
 *
 * ## 威胁模型
 *
 * Assume Breach：云端（WebDAV / S3）不可信，可返回一份「旧的但仍能用主凭据解密的合法 `.kdbx`」。
 * 既有三哈希状态机（baseEtag / baseVersionHash / 内容哈希）只解决并发一致性与数据丢失，
 * **不解决版本回退**——「远端 ≠ base 即下载应用」会使被入侵端点复活已删条目、回退已更新字段。
 *
 * ## 方案：本地认证的「已见内容摘要链」
 *
 * 为每个远端路径维护一份由本地 Keystore 密钥 MAC 认证的状态：
 * - `current`：最近一次被设备接受的远端内容摘要；
 * - `recent`：有界（[MAX_RECENT_DIGESTS] 条）的历史已接受摘要。
 *
 * 裁决：`digest == current` → [RollbackVerdict.Unchanged]；`digest ∈ recent` →
 * [RollbackVerdict.ReplayDetected]；否则 [RollbackVerdict.Accept]。
 *
 * ## 跨端兼容决策（必须留痕）
 *
 * 采用「已见摘要链」而非纯单调序号：其他官方客户端（KeePass 2.x / KeePassDX / KeePassXC）
 * 写入的是**全新内容**（新摘要），永远命中 [RollbackVerdict.Accept]，**不误报**；
 * 仅「与设备侧曾接受过的历史版本逐字节相同」的重放才判 [RollbackVerdict.ReplayDetected]。
 * 用户主动把本地备份回滚到旧版本再上传，会被判回退并提示（属可接受的显式确认代价，已留痕）。
 *
 * 状态文件被篡改 / MAC 校验失败时按「无历史」处理（不产生误报回退）；
 * 本地文件级攻击者不在本威胁模型内（其本可 Hook 进程），远端攻击者无法触碰本地状态文件。
 */
class SyncRollbackGuard(
    private val stateDir: File,
    private val integrityMac: SyncIntegrityMac
) {

    /** 持久化状态快照（`current` 为 null 表示尚无已接受内容） */
    data class State(
        val sequence: Long = 0L,
        val current: String? = null,
        val recent: List<String> = emptyList()
    )

    init {
        if (!stateDir.exists()) {
            stateDir.mkdirs()
        }
    }

    /**
     * 裁决远端内容是否可接受。**不修改**状态；接受后须调用 [recordAccepted] 前移高水位。
     */
    fun inspect(remotePath: String, content: ByteArray): RollbackVerdict {
        val digest = SyncCache.sha256Hex(content)
        val state = load(remotePath)
        return when {
            state.current == null -> RollbackVerdict.Accept
            state.current == digest -> RollbackVerdict.Unchanged
            digest in state.recent -> RollbackVerdict.ReplayDetected
            else -> RollbackVerdict.Accept
        }
    }

    /** 记录一份已被接受的内容：`recent` 有界去重，`current` 前移，`sequence` 自增。 */
    fun recordAccepted(remotePath: String, content: ByteArray) {
        val digest = SyncCache.sha256Hex(content)
        val state = load(remotePath)
        if (state.current == digest) return
        val recent = buildList {
            state.current?.let { add(it) }
            addAll(state.recent)
        }.distinct().take(MAX_RECENT_DIGESTS)
        persist(remotePath, State(state.sequence + 1, digest, recent))
    }

    private fun load(remotePath: String): State {
        val file = stateFile(remotePath)
        if (!file.exists() || !file.isFile) return State()
        val lines = try {
            file.readLines()
        } catch (_: Throwable) {
            return State()
        }
        val macLine = lines.firstOrNull { it.startsWith(PREFIX_MAC) }
        val payloadLines = lines.filterNot { it.startsWith(PREFIX_MAC) }
        val payload = payloadLines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val mac = macLine?.removePrefix(PREFIX_MAC)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (!integrityMac.verify(payload, mac)) return State()

        var sequence = 0L
        var current: String? = null
        var recent: List<String> = emptyList()
        payloadLines.forEach { line ->
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val key = line.substring(0, index)
            val value = line.substring(index + 1)
            when (key) {
                KEY_SEQUENCE -> sequence = value.toLongOrNull() ?: 0L
                KEY_CURRENT -> current = value.ifEmpty { null }
                KEY_RECENT -> recent = value.split(',').filter { it.isNotBlank() }
            }
        }
        return State(sequence, current, recent)
    }

    private fun persist(remotePath: String, state: State) {
        // 载荷不含尾随换行，保证 [load] 以 `readLines` 重组后逐字节一致（MAC 校验依赖此不变式）
        val payloadLines = listOf(
            "$KEY_SEQUENCE=${state.sequence}",
            "$KEY_CURRENT=${state.current.orEmpty()}",
            "$KEY_RECENT=${state.recent.joinToString(",")}"
        )
        val payload = payloadLines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val mac = integrityMac.compute(payload)
        val encodedMac = mac?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()
        val content = (payloadLines.joinToString("\n") + "\n$PREFIX_MAC$encodedMac\n")
            .toByteArray(Charsets.UTF_8)

        val target = stateFile(remotePath)
        val tmp = File(stateDir, "${target.name}.${UUID.randomUUID()}$SUFFIX_TMP")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(content)
                fos.flush()
                fos.fd.sync()
            }
            moveAtomically(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    private fun moveAtomically(tmpFile: File, targetFile: File) {
        if (tmpFile.renameTo(targetFile)) return
        try {
            Files.move(
                tmpFile.toPath(), targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun stateFile(remotePath: String): File =
        File(stateDir, SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8)) + SUFFIX_STATE)

    companion object {
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_CURRENT = "current"
        private const val KEY_RECENT = "recent"
        private const val PREFIX_MAC = "mac="
        private const val SUFFIX_TMP = ".tmp"

        /** 状态文件后缀（与 [SyncCache.clear] 的清理后缀保持一致） */
        const val SUFFIX_STATE = ".rollback"

        /** 历史已接受摘要的有界上限（防状态文件无限增长） */
        private const val MAX_RECENT_DIGESTS = 32
    }
}
