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
 * ## 状态生命周期（F-23 整改后必须如实声明）
 *
 * [stateDir] 由调用方注入，**生产路径固定为 `filesDir/<STATE_DIR_NAME>`**（跨会话锁定保留的
 * 持久目录），**绝不可指向 `cacheDir` 下的可丢弃缓存**。整改前状态与同步缓存同目录，而
 * `SyncCache.clear()` 把 [SUFFIX_STATE] 列入删除清单、该方法又由 `SyncCacheEvictor.onSessionLocked()`
 * 在每次锁库 / 关库 / 同步凭据清空时调用——状态一空 [inspect] 即因 `current == null` 返回
 * [RollbackVerdict.Accept]，云侧只要等到用户**正常锁定一次**便可重放旧库，重放防线被降级为
 * 「当前这次未锁定会话」。现由两侧共同保证该不变式：
 * 1. 状态落在 [STATE_DIR_NAME]（`filesDir` 下），仅「卸载应用 / 清除应用数据」才会消失；
 * 2. `SyncCache.clear` / `SyncCache.clearAll` **永不触碰** [SUFFIX_STATE] 命名的文件
 *    （即便状态目录被误配到缓存目录，缓存清理也不得摧毁安全状态）。
 *
 * 状态文件内容为 SHA-256 摘要 + Keystore HMAC，**不含任何明文**，无逆推价值；
 * 目录位于应用私有 `filesDir`，不随缓存回收策略被系统回收。
 *
 * **保留策略（刻意为之）**：状态不随会话锁定、同步凭据清空（换服务器 / 退出同步）而删除——
 * 清理它等于重开重放窗口，而少量陈旧状态（按 remotePath 摘要键控）只会多占几百字节，
 * 不会造成误判（只有与**曾接受过的**内容逐字节相同时才判回退）。
 * 完整清除仅发生在卸载应用 / 清除应用数据。
 *
 * ## 状态缺失 / 被篡改时的裁决（fail-open，取舍已留痕）
 *
 * 状态文件缺失或 MAC 校验失败时一律按「无历史」处理（[load] 返回空 [State]）并继续接受远端内容：
 * - 缺失：首次运行、以及**升级迁移后的首轮同步**——历史状态落在旧的 `cacheDir/sync`，本批
 *   整改**不做搬运**（`inspect` 对旧目录一无所知），故升级后首轮按「无历史」放行，
 *   由 [recordAccepted] 重新建立高水位；
 * - MAC 失效：被篡改、Keystore 密钥轮换、或调用方注入 `NoopSyncIntegrityMac`（禁用防回滚）。
 *
 * 取舍理由：宁可漏判一次重放，也不制造**无法自愈的误报回退**把用户永久锁在同步之外
 * （状态不可信时若判回退，用户将没有任何恢复路径）。代价是升级后首轮 / 状态被删时存在一次
 * 重放窗口；本地文件级攻击者不在本威胁模型内（其本可 Hook 进程），远端攻击者无法触碰本地状态文件。
 *
 * ## `sequence` 字段现状（本批未启用，留作后续单调性依据）
 *
 * [State.sequence] 已随状态持久化（每次 [recordAccepted] 自增），但**当前不参与任何裁决**：
 * [inspect] 只比对内容摘要，序号的单调性尚未作为回退判据（单看序号无法判定「未知但更旧」的版本，
 * 故不能直接启用）。保留该字段以便后续接入序号单调校验；**启用前不得据此推断防回滚强度**。
 *
 * ## 跨端兼容决策（必须留痕）
 *
 * 采用「已见摘要链」而非纯单调序号：其他官方客户端（KeePass 2.x / KeePassDX / KeePassXC）
 * 写入的是**全新内容**（新摘要），永远命中 [RollbackVerdict.Accept]，**不误报**；
 * 仅「与设备侧曾接受过的历史版本逐字节相同」的重放才判 [RollbackVerdict.ReplayDetected]。
 * 用户主动把本地备份回滚到旧版本再上传，会被判回退并提示（属可接受的显式确认代价，已留痕）。
 */
class SyncRollbackGuard(
    /** 防回滚状态目录；生产由调用方注入 `filesDir/<STATE_DIR_NAME>`（跨锁定保留），见类 KDoc */
    private val stateDir: File,
    private val integrityMac: SyncIntegrityMac
) {

    /**
     * 持久化状态快照（`current` 为 null 表示尚无已接受内容）。
     *
     * [sequence] 为已持久化但**尚未参与裁决**的单调序号（现状与后续用途见类 KDoc）。
     */
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
     *
     * 状态文件缺失 / MAC 校验失败时按「无历史」处理 → 返回 [RollbackVerdict.Accept]
     * （fail-open，取舍与代价见类 KDoc「状态缺失 / 被篡改时的裁决」）。
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

    /**
     * 记录一份已被接受的内容：`recent` 有界去重，`current` 前移，`sequence` 自增。
     *
     * [State.sequence] 当前仅被持久化、不参与裁决（见类 KDoc「`sequence` 字段现状」）。
     */
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

        /**
         * 状态文件后缀（`<SHA-256(remotePath)>.rollback`）。
         *
         * ⚠ 该后缀标识**跨会话安全状态**，不是缓存产物：`SyncCache.clear` / `SyncCache.clearAll`
         * 的删除清单与通配清理**一律不得包含**它（F-23 整改前它被列入 `clear()` 的删除清单，
         * 而 `clear()` 由锁库 / 凭据清空触发，导致「锁定一次即清零」、重放防护失效）。
         */
        const val SUFFIX_STATE = ".rollback"

        /**
         * 防回滚状态目录名（app 侧 `filesDir` 下的相对路径）。
         *
         * 由 DI（`DatabaseModule.provideRollbackStateDir`）与 `SyncCycleRunner` 共用，
         * 确保状态目录不与 `cacheDir` 下的可丢弃缓存混居——这是 F-23 的根因约束。
         */
        const val STATE_DIR_NAME = "rollback"

        /** 历史已接受摘要的有界上限（防状态文件无限增长） */
        private const val MAX_RECENT_DIGESTS = 32
    }
}
