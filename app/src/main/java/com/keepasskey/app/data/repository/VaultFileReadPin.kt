package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.security.VaultFileBaseline
import com.keepasskey.app.security.VaultFileDriftPolicy
import com.keepasskey.app.security.VaultFileMetadataProbe
import java.io.File
import java.security.MessageDigest

/**
 * `ISSUE-P1-631`（读取版本钉扎）：合并**读取时点**的通道身份与文件身份。
 *
 * 用途：解析 / 合并是长窗口，窗口内磁盘可能被再次外部改写、会话也可能被换库——提交前
 * 复核本钉扎，把用旧版本算出的产物 fail-closed 拦下（`VaultFileDriftResolve.adoptAndPersist`）。
 *
 * **身份构成（专项审查结论，逐项声明而非笼统称「指纹」）**：
 * 1. 路径标识（`pathId`）——换库 / 换文档即判变；
 * 2. 通道类型（本地 `File` / SAF `content://`）——通道切换即判变；
 * 3. **内容摘要**：对**所读取的整份密文**取 SHA-256，提交前按同通道重读并比对
 *    （内容级判据，覆盖「size 与 mtime 都不变但内容已变」与 ABA 复原两类盲区）；
 * 4. 元数据（size / mtime，复用 `VaultFileDriftPolicy.shouldAbortSave`）——仅在**内容不可得**时
 *    退化为该判据，且明确登记为盲区（见已知工程限界）。
 *
 * 早期版本只比对元数据：SAF provider 不暴露 mtime 时判据退化为 size 单维，**同尺寸改内容与 ABA
 * 都能骗过它**。故本版把内容摘要提升为主判据——这不是「更强的同一种东西」，而是把安全保证从
 * 「服务方元数据可信」改为「读取到的字节可信」，不再依赖 provider 的元数据完整度。
 */
internal class VaultFileReadPin private constructor(
    private val pathId: String,
    /** 捕获时是否走本地 `File` 通道（复核时通道类型变化 ⇔ 已换库/换通道） */
    private val fileChannel: Boolean,
    /** 读取时点整份密文的 SHA-256；内容当时不可读出时为 null（退化到元数据判据） */
    private val digest: ByteArray?,
    /** 读取时点的元数据快照（本地 `File` 直读属性；SAF 取文档元数据） */
    private val metadata: VaultFileBaseline?,
    /** 复核时点按**同通道**重读当前字节（本地直读 / SAF 经 ContentResolver） */
    private val reReadContent: () -> ByteArray?
) {

    /**
     * 复核：通道身份未变**且内容摘要未变** ⇒ true（可安全提交）。
     *
     * [currentPathId] / [currentFile] 必须取**复核时点**的会话当前值——换库后与捕获值不同，
     * 直接判拒（并发 barrier 验收里「库 A 的旧任务不得提交到库 B」的第一道闸）。
     *
     * 内容摘要为主判据：只要读取时点拿到过内容，就**只**认内容——元数据一致而内容已变（同尺寸
     * 覆写、ABA 复原）必须判拒；元数据无关变化（如仅 mtime 变动但字节相同）不阻断提交
     * （字节相同即「合并输入未被替换」，此时拒绝只会制造无谓失败）。
     */
    fun isStillCurrent(context: Context?, currentPathId: String, currentFile: File?): Boolean {
        if (pathId != currentPathId) return false
        if (fileChannel != (currentFile != null)) return false
        val expected = digest ?: return isMetadataStillCurrent(context, currentPathId, currentFile)
        val now = reReadContent() ?: return false
        return MessageDigest.isEqual(sha256(now), expected)
    }

    /**
     * `ISSUE-P1-631`：**元数据单维**复核——只比对 size / mtime（不重读内容）。
     *
     * 在提交临界区**内**（不可取消区）调用，作为内容级复核之后的第二道廉价闸门：
     * 内容级复核与采纳之间只剩极短窗口，此闸门不产生 IO，可零成本覆盖「元数据已变」的
     * 那一小类残差。内容当时不可读出（[digest] == null）时，本方法即**唯一**判据
     * ——盲区（同尺寸 + 同 mtime 的改内容）在此情形下不可避免，已在限界中登记。
     */
    fun isMetadataStillCurrent(context: Context?, currentPathId: String, currentFile: File?): Boolean {
        if (pathId != currentPathId) return false
        if (fileChannel != (currentFile != null)) return false
        val current = currentMetadata(context, currentPathId, currentFile)
        val before = metadata ?: return current == null
        val after = current ?: return false
        return !VaultFileDriftPolicy.shouldAbortSave(
            baseline = before.copy(pathIdentifier = currentPathId),
            current = after
        )
    }

    companion object {
        /**
         * 读取时点捕获（本地 `File` 直读属性；SAF 取文档元数据；不可读 ⇒ 元数据为 null）。
         *
         * [content] 为调用方**刚刚读取并用于合并的那份字节**——直接复用，不重读文件：
         * 重读会引入「钉扎的是另一份字节」的新错位（TOCTOU 的镜像面）。
         *
         * [reReadContent] 必须由调用方提供，且**与读取 [content] 走同一实现**（同一上限、
         * 同一失败语义）。不得在本类里另写一套读法：那会让「复核」与「读取」在超限 / 异常
         * 边界上分叉——早期版本正是在此处用了一个无上限的读取，导致任何正常库的复核都取不到
         * 内容、`isStillCurrent` 恒为 false（合并被整体误拒）。
         */
        fun capture(
            context: Context?,
            pathId: String,
            localFile: File?,
            content: ByteArray?,
            reReadContent: () -> ByteArray?
        ): VaultFileReadPin = VaultFileReadPin(
            pathId = pathId,
            fileChannel = localFile != null,
            digest = content?.let { sha256(it) },
            metadata = currentMetadata(context, pathId, localFile),
            reReadContent = reReadContent
        )

        /** 当前通道的元数据快照（本地 `File` 直读；SAF 取文档元数据；不可读为 null） */
        private fun currentMetadata(
            context: Context?,
            pathId: String,
            localFile: File?
        ): VaultFileBaseline? =
            if (localFile != null) {
                VaultFileBaseline.fromFile(localFile)
            } else {
                VaultFileMetadataProbe.baselineFor(context, pathId)
            }

        /** 整份密文的 SHA-256（内容级身份；不物化明文、不留额外明文副本） */
        private fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

