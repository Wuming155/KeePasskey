package com.keepasskey.sync.engine

import com.keepasskey.core.security.SecureFileWrite
import java.io.File

/**
 * 同步缓存的**文件层原语**（ISSUE-P3-188 自 [SyncCache] 下沉为同包协作类）。
 *
 * 职责边界：只负责「按远端路径规范键定位文件、唯一临时名、fsync 写入、原子替换、
 * 仅属主权限收敛、有界重试删除」；三哈希语义（cache / version / basecache / meta）
 * 与防回滚状态例外（F-23）仍由 [SyncCache] 裁决。
 *
 * `ISSUE-P3-582`：唯一临时名 / fsync 写入 / 原子替换 / 仅属主权限收敛四项**通用原语**
 * 已下沉到 core 的 [SecureFileWrite]（使权限位与唯一临时名口径全仓单一，不再有第二份拷贝
 * 可漂移）；本类退化为对它的薄委托，并保留缓存域专有逻辑（规范键定位、通配清理、
 * 有界重试删除）。**行为与权限口径零变更**。
 */
internal class SyncCacheFiles(private val cacheDir: File) {

    /** 按规范键（`SHA-256(remotePath)`）定位缓存目录内的文件 */
    fun fileFor(remotePath: String, suffix: String): File {
        val key = SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8))
        return File(cacheDir, "$key$suffix")
    }

    /**
     * 生成唯一临时文件路径（`ISSUE-P3-582` 起委托 core [SecureFileWrite.uniqueTmpFor]）。
     * 固定名 tmp 在并发写同一 remotePath 时会互相覆盖，造成 A 的 rename 交付 B 的
     * 内容（交叉污染）；对齐 Wave 9 WebDAV uploadAtomic 临时名唯一化的同类整改语义。
     * 当前 SyncCoordinator 以 mutex 串行化同步周期，唯一名作为并发防御纵深兜底。
     */
    fun tmpFileFor(targetFile: File): File = SecureFileWrite.uniqueTmpFor(targetFile, SUFFIX_TMP)

    /** 写入 tmp 文件并 fsync 落盘 + 收敛 0600（不含 rename 交付步骤，供多文件合并原子写复用） */
    fun writeTmpSynced(tmpFile: File, bytes: ByteArray) = SecureFileWrite.writeSynced(tmpFile, bytes)

    /** 唯一临时名写入 + 原子替换交付（tmp 文件无论如何处置都被删除） */
    fun writeStringSafely(targetFile: File, content: String) {
        val tmpFile = tmpFileFor(targetFile)
        try {
            writeTmpSynced(tmpFile, content.toByteArray(Charsets.UTF_8))
            moveAtomically(tmpFile, targetFile)
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * 原子替换移动（委托 core [SecureFileWrite.moveAtomically]）：POSIX rename 对已存在目标
     * 执行原子替换（无窗口、无半写状态）；`renameTo` 失败的平台（如桌面 Windows 调试环境）
     * 回退 `Files.move`，优先 ATOMIC_MOVE，不支持时降级 REPLACE_EXISTING，绝不做非原子 copyTo。
     */
    fun moveAtomically(tmpFile: File, targetFile: File) {
        SecureFileWrite.moveAtomically(tmpFile, targetFile)
    }

    /**
     * 收敛文件权限为「仅属主可访问」（`ISSUE-P1-07` 验收标准 2；委托 core
     * [SecureFileWrite.restrictToOwnerOnly]，口径注释见彼处）。
     */
    fun restrictToOwnerOnly(file: File, isDirectory: Boolean) =
        SecureFileWrite.restrictToOwnerOnly(file, isDirectory)

    /**
     * 通配清理本规范键的全部残留 tmp 文件（ISSUE-P3-308）。
     * tmp 名含随机成分后，固定名清单不再完备；以「缓存键哈希前缀 + tmp 后缀」通配兜底，
     * 防止进程崩溃残留的 tmp 文件累积泄漏磁盘。
     *
     * [key] 语义与 [fileFor] 一致：**调用方已派生的规范键**（含库身份命名空间），
     * 本方法只做 `SHA-256` 命名派生，不自持命名空间（键派生唯一实现在 [SyncCache.scopedKey]）。
     * `ISSUE-P2-291` 之前本参数是裸 `remotePath`，scoped 实例下前缀恒不匹配、通配永不命中。
     */
    fun deleteOrphanTmpFiles(key: String) {
        val keyHash = SyncCache.sha256Hex(key.toByteArray(Charsets.UTF_8))
        cacheDir.listFiles { file -> file.name.startsWith(keyHash) && file.name.endsWith(SUFFIX_TMP) }
            ?.forEach { it.delete() }
    }

    /**
     * 删除单个缓存子项（ISSUE-P2-82）。
     *
     * **幂等契约**：`File.delete()` 对**已不存在**的目标返回 `false`，故「先列目录、再逐个删除」
     * 的清理在**并发或连续两次**执行时会误判失败——后一次 `delete()` 因目标已被先者删除而返回
     * `false`，调用方 [`com.keepasskey.app.sync.SyncCacheEvictor`] 据此记录
     * **「残留 0 项，锁定后密文可能仍可恢复」这一自相矛盾的 WARN**，并把 `false` 一路传回
     * `MainApplication.purgeVolatileCachesBeforeExit()`（该入口的返回值被当作「两个清理面是否都成功」）。
     * 故此处以「删除动作结束后目标是否仍存在」为准：**已不存在即视为成功**。
     *
     * 实测形态（2026-09-15）：`SyncCacheEvictorTest` 的 F-23 用例在 `session.lock()` 的观察者回调与
     * 用例内显式 `evictAll()` 竞态时偶发红，失败信息同时出现 `INFO 已随会话终止销毁` 与
     * `WARN 残留 0 项` 两条互相矛盾的日志——即本缺陷的可观测形态。
     */
    fun deleteChild(child: File): Boolean {
        if (!child.exists()) return true
        var removed = deleteOnce(child)
        // ISSUE-P3-108：平台层「句柄尚未释放」会**瞬时**让 delete() 返回 false
        // （Windows 桌面调试环境与写入方刚结束时的固有延迟；Android 上同理但概率更低）。
        // 有界重试把「稍后即可删除」的瞬时失败与「真的删不掉」区分开：
        // 前者不再被当作清理失败上报（也不必因此放宽任何断言），后者仍如实返回 false。
        var attempt = 0
        while (!removed && child.exists() && attempt < DELETE_RETRIES) {
            attempt++
            Thread.sleep(DELETE_RETRY_GAP_MS)
            removed = deleteOnce(child)
        }
        return removed || !child.exists()
    }

    private fun deleteOnce(child: File): Boolean =
        if (child.isDirectory) child.deleteRecursively() else child.delete()

    companion object {
        /**
         * 临时文件后缀（唯一临时名与通配清理共用；[SyncCache] 的后缀清单同引此处）。
         * `ISSUE-P3-582`：取值收敛到 core [SecureFileWrite.SUFFIX_TMP] 单一真相源。
         */
        internal const val SUFFIX_TMP = SecureFileWrite.SUFFIX_TMP

        /** ISSUE-P3-108：瞬时删除失败的有界重试次数与间隔（见 [deleteChild]） */
        private const val DELETE_RETRIES = 3
        private const val DELETE_RETRY_GAP_MS = 15L
    }
}
