package com.keepasskey.app.security

import android.content.Context
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.files.NewVaultNames
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.AtomicFileWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 冷启动发现并处置恢复副本的编排（`ISSUE-P1-592` 步骤 5 / 6）。
 *
 * 流程（方案口径）：冷启动发现副本 → 让用户以**对应凭据**解锁 → 预览与正式库的差异 →
 * 选择**恢复为正式库**或**另存为新库**；**绝不**在无提示下覆盖可能已被外部修改的正式库。
 * 成功恢复 / 另存并确认后才删除副本（步骤 6）。凭据轮换的旧密文清理见
 * [VaultRecoveryStore.delete] 的调用点（`VaultLifecycleCoordinator` 换密成功路径）。
 *
 * 凭据来源：主密码由用户在对话框输入；密钥文件复用 `ISSUE-P3-448` 的应用私有收编副本
 * （[KeyFileVaultCopyStore]，按库 id 取），避免让用户再次挑选文件。
 */
@Singleton
class VaultRecoveryCoordinator @Inject constructor(
    @ApplicationContext private val context: Context?,
    private val store: VaultRecoveryStore,
    private val alertStore: RecoveryAlertStore,
    private val keyFileCopyStore: KeyFileVaultCopyStore,
    private val debugLog: DebugLogBuffer
) {

    /** 待处置副本 + 其正式库的当前状态（供 UI 提示「正式库已外改」）。 */
    data class Pending(
        val entry: VaultRecoveryStore.Entry,
        val officialFile: File?,
        val officialFileExists: Boolean
    )

    /** 预览结果（非敏感读数）：条目数与相对正式库的增 / 删 / 改计数。 */
    data class Preview(
        val vaultName: String,
        val snapshotEntryCount: Int,
        val officialEntryCount: Int?,
        val addedEntries: Int?,
        val removedEntries: Int?,
        val modifiedEntries: Int?
    ) {
        /** 正式库缺失或与副本条目集合一致时视为「无差异」。 */
        val officialUnreadable: Boolean get() = officialEntryCount == null
        val differs: Boolean get() = addedEntries != 0 || removedEntries != 0 || modifiedEntries != 0
    }

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    /** 单测注入的另存根目录覆盖（生产恒 null → `context.filesDir`）。 */
    @androidx.annotation.VisibleForTesting
    internal var filesDirOverride: File? = null

    private val filesDir: File?
        get() = filesDirOverride ?: context?.filesDir

    /** 扫描恢复目录，刷新待处置列表（冷启动 / 处置完成后调用）。 */
    fun refresh() {
        _pending.value = store.list().map { toPending(it) }
    }

    /** 一次性取走「上次未能保全」告警（冷启动展示；第二次返回 null）。 */
    fun consumePreservationAlert(): RecoveryAlertStore.Alert? = alertStore.consume()

    /**
     * 以用户输入的凭据解锁副本并预览差异。副本损坏（完整性校验失败）如实失败。
     *
     * @param passwordChars 主密码（调用方所有；本方法只读、不擦除）
     */
    suspend fun preview(target: Pending, passwordChars: CharArray): KdbxResult<Preview> =
        withContext(Dispatchers.Default) {
            val snapshotBytes = store.readPayload(target.entry)
                ?: return@withContext failure(KdbxError.RECOVERY_PAYLOAD_CORRUPT)
            val keyFile = keyFileCopyStore.load(target.entry.vaultId)?.bytes
            var snapshotDb: KdbxDatabase? = null
            var officialDb: KdbxDatabase? = null
            try {
                snapshotDb = loadDatabase(snapshotBytes, passwordChars, keyFile)
                officialDb = readOfficialDatabase(target, passwordChars, keyFile)
                KdbxResult.Success(buildPreview(target.entry.vaultName, snapshotDb, officialDb))
            } catch (t: Throwable) { // cancel-n/a: 保护段为阻塞式解密（KdbxFile.load 内部无挂起点）
                debugLog.warn(TAG, "恢复副本解锁失败: ${t.javaClass.simpleName}")
                failure(KdbxError.RECOVERY_UNLOCK_FAILED)
            } finally {
                // 预览只取条目计数；解出的明文树与凭据副本用毕即擦（不等待 GC）
                snapshotDb?.clearSensitiveData()
                officialDb?.clearSensitiveData()
                keyFile?.fill(0)
                snapshotBytes.fill(0)
            }
        }

    /**
     * 恢复为正式库（仅限本地文件库）。
     *
     * **覆盖保护**：正式库当前存在且 [forceOverwrite] 为 false 时拒绝，返回
     * [KdbxError.RECOVERY_OVERWRITE_BLOCKED]，由 UI 展示差异并请用户显式确认后再以
     * `forceOverwrite = true` 重试。成功后才删除副本。
     */
    suspend fun restore(target: Pending, forceOverwrite: Boolean): KdbxResult<Unit> =
        withContext(Dispatchers.IO) {
            val file = target.officialFile
                ?: return@withContext failure(KdbxError.RECOVERY_LOCAL_ONLY)
            if (file.exists() && !forceOverwrite) {
                return@withContext failure(KdbxError.RECOVERY_OVERWRITE_BLOCKED)
            }
            val bytes = store.readPayload(target.entry)
                ?: return@withContext failure(KdbxError.RECOVERY_PAYLOAD_CORRUPT)
            try {
                AtomicFileWriter.writeAtomic(file, createBackup = true) { it.write(bytes) }
            } catch (t: Throwable) { // cancel-n/a: 保护段为阻塞式写盘（无挂起点）
                debugLog.warn(TAG, "恢复副本写回正式库失败: ${t.javaClass.simpleName}")
                return@withContext failure(KdbxError.RECOVERY_WRITE_FAILED)
            } finally {
                bytes.fill(0)
            }
            store.delete(target.entry)
            refresh()
            KdbxResult.Success(Unit)
        }

    /** 另存为新库（落在应用私有目录，名称去重）；成功后才删除副本。返回新库文件名。 */
    suspend fun saveAs(target: Pending): KdbxResult<String> = withContext(Dispatchers.IO) {
        val dir = filesDir
            ?: return@withContext failure(KdbxError.RECOVERY_WRITE_FAILED)
        val bytes = store.readPayload(target.entry)
            ?: return@withContext failure(KdbxError.RECOVERY_PAYLOAD_CORRUPT)
        val suggested = suggestedName(target.entry)
        val destination = try {
            NewVaultNames.target(dir, NewVaultNames.suggest(dir, suggested))
        } catch (t: Throwable) { // cancel-n/a: 保护段为阻塞式路径规范化（无挂起点）
            bytes.fill(0)
            return@withContext failure(KdbxError.RECOVERY_WRITE_FAILED)
        }
        try {
            AtomicFileWriter.writeAtomic(destination, createBackup = false) { it.write(bytes) }
        } catch (t: Throwable) { // cancel-n/a: 保护段为阻塞式写盘（无挂起点）
            debugLog.warn(TAG, "恢复副本另存失败: ${t.javaClass.simpleName}")
            return@withContext failure(KdbxError.RECOVERY_WRITE_FAILED)
        } finally {
            bytes.fill(0)
        }
        store.delete(target.entry)
        refresh()
        KdbxResult.Success(destination.name)
    }

    /** 放弃处置：仅关闭提示，保留副本（下次冷启动仍可恢复）。 */
    fun keep() { /* 无副作用：副本保留在磁盘 */ }

    /** 删除副本（用户已确认无需恢复）。 */
    suspend fun discard(target: Pending) = withContext(Dispatchers.IO) {
        store.delete(target.entry)
        refresh()
    }

    // ===== 内部实现 =====

    private fun toPending(entry: VaultRecoveryStore.Entry): Pending {
        val file = localFileFor(entry.pathIdentifier)
        return Pending(entry = entry, officialFile = file, officialFileExists = file?.exists() == true)
    }

    /** 只有裸文件路径（非 `content://`）可以原地读写；SAF 库原地恢复不支持。 */
    private fun localFileFor(pathIdentifier: String): File? =
        if (pathIdentifier.startsWith(SAF_SCHEME)) null else File(pathIdentifier)

    private fun readOfficialDatabase(
        target: Pending,
        passwordChars: CharArray,
        keyFile: ByteArray?
    ): KdbxDatabase? {
        val file = target.officialFile ?: return null
        if (!file.exists()) return null
        return try {
            loadDatabase(file.readBytes(), passwordChars, keyFile)
        } catch (t: Throwable) { // cancel-n/a: 保护段为阻塞式解密（无挂起点）
            debugLog.warn(TAG, "正式库读取失败: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun loadDatabase(bytes: ByteArray, passwordChars: CharArray, keyFile: ByteArray?): KdbxDatabase {
        val pwdClone = passwordChars.clone()
        val keyClone = keyFile?.clone()
        try {
            return KdbxFile.load(ByteArrayInputStream(bytes), pwdClone, keyClone)
        } finally {
            pwdClone.fill('0')
            keyClone?.fill(0)
        }
    }

    private fun buildPreview(vaultName: String, snapshot: KdbxDatabase, official: KdbxDatabase?): Preview {
        val snap = snapshot.rootGroup.allEntries().associateBy { it.id }
        if (official == null) {
            return Preview(vaultName, snap.size, null, null, null, null)
        }
        val off = official.rootGroup.allEntries().associateBy { it.id }
        val added = snap.keys.count { it !in off.keys }
        val removed = off.keys.count { it !in snap.keys }
        val modified = snap.keys.count { id -> off[id]?.let { !sameFingerprint(snap.getValue(id), it) } == true }
        return Preview(vaultName, snap.size, off.size, added, removed, modified)
    }

    /** 比较条目实际内容；受保护字段由领域模型按内容等值比较，不解密成普通字符串。 */
    private fun sameFingerprint(a: KdbxEntry, b: KdbxEntry): Boolean =
        a.fieldsContentEquals(b) &&
            a.customFields.size == b.customFields.size &&
            a.customFields.zip(b.customFields).all { (left, right) ->
                left.key == right.key &&
                    left.isProtected == right.isProtected &&
                    left.value.contentEquals(right.value)
            } &&
            a.attachments == b.attachments &&
            a.history.size == b.history.size &&
            a.history.zip(b.history).all { (left, right) -> sameFingerprint(left, right) } &&
            a.tags == b.tags &&
            a.autoType == b.autoType &&
            a.backgroundColor == b.backgroundColor &&
            a.foregroundColor == b.foregroundColor &&
            a.overrideUrl == b.overrideUrl &&
            a.qualityCheck == b.qualityCheck &&
            a.customData == b.customData &&
            a.parentGroupId == b.parentGroupId &&
            a.iconId == b.iconId &&
            a.customIconId == b.customIconId

    private fun suggestedName(entry: VaultRecoveryStore.Entry): String {
        val base = entry.vaultName.takeIf { it.isNotBlank() } ?: entry.pathIdentifier.substringAfterLast('/')
        val stem = base.removeSuffix(NewVaultNames.EXTENSION).ifBlank { RECOVERED_STEM }
        return "$stem$RECOVERED_SUFFIX${NewVaultNames.EXTENSION}"
    }

    private fun failure(code: String) =
        KdbxResult.Failure(IllegalStateException(code), userText = null, code = code)

    private companion object {
        const val TAG = "VaultRecovery"
        const val SAF_SCHEME = "content://"
        const val RECOVERED_STEM = "recovered"
        const val RECOVERED_SUFFIX = " (恢复)"
    }
}
