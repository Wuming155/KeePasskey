package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.sync.model.cleanEtag
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** 锁态元数据探测回执；不保存库字节或任何凭据。 */
internal data class PeriodicSyncProbeRecord(
    val targetIdentity: String,
    val observedEtag: String,
    val needsUnlock: Boolean,
    /** 最近一次完整同步确认的本地 KDBX 密文摘要；null 表示旧版或尚无完整同步确认。 */
    val syncedContentDigest: String? = null,
    /** 防回滚守卫使用的根组 UUID 摘要。 */
    val rollbackVaultScope: String? = null
)

internal sealed interface PeriodicSyncProbeRead {
    data object Missing : PeriodicSyncProbeRead
    data object Corrupt : PeriodicSyncProbeRead
    data class Stored(val record: PeriodicSyncProbeRecord) : PeriodicSyncProbeRead
}

internal enum class PeriodicSyncNeedsUnlockReason {
    ACTIVE_VAULT_UNKNOWN,
    TARGET_UNAVAILABLE,
    TARGET_CHANGED,
    PROBE_STATE_CORRUPT,
    PENDING_UNLOCK,
    INITIAL_BASELINE_REQUIRED,
    ETAG_UNAVAILABLE,
    REMOTE_CHANGED,
    REMOTE_MISSING,
    PROBE_STATE_WRITE_FAILED
}

/** 周期工作结果；元数据探测状态与完整同步结果分开表达。 */
internal sealed interface PeriodicSyncWorkResult {
    data object NotConfigured : PeriodicSyncWorkResult
    data object MetadataUnchanged : PeriodicSyncWorkResult
    data object EncryptedUploadCompleted : PeriodicSyncWorkResult
    data class NeedsUnlock(val reason: PeriodicSyncNeedsUnlockReason) : PeriodicSyncWorkResult
    data object AuthenticationRequired : PeriodicSyncWorkResult
    data object TemporaryNetworkFailure : PeriodicSyncWorkResult
    data object ConfigurationInvalid : PeriodicSyncWorkResult
    data class RemoteRejected(val errorType: String) : PeriodicSyncWorkResult
    data class FullSync(val outcome: SyncOutcome) : PeriodicSyncWorkResult
}

/** 纯决策规则：ETag 仅作为不透明变化信号，不能代表内容版本。 */
internal object PeriodicSyncProbePolicy {
    sealed interface Preflight {
        data class RequestMetadata(val previousEtag: String?) : Preflight
        data class NeedsUnlock(val reason: PeriodicSyncNeedsUnlockReason) : Preflight
    }

    sealed interface Observation {
        data object Unchanged : Observation
        data class NeedsUnlock(val reason: PeriodicSyncNeedsUnlockReason) : Observation
    }

    fun preflight(
        state: PeriodicSyncProbeRead,
        currentTargetIdentity: String
    ): Preflight = when (state) {
        PeriodicSyncProbeRead.Missing -> Preflight.RequestMetadata(previousEtag = null)
        PeriodicSyncProbeRead.Corrupt ->
            Preflight.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT)

        is PeriodicSyncProbeRead.Stored -> when {
            state.record.targetIdentity != currentTargetIdentity ->
                Preflight.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_CHANGED)

            state.record.needsUnlock ->
                Preflight.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PENDING_UNLOCK)

            state.record.observedEtag.isBlank() ->
                Preflight.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT)

            else -> Preflight.RequestMetadata(state.record.observedEtag)
        }
    }

    fun observe(previousEtag: String?, currentEtag: String): Observation {
        val observed = cleanEtag(currentEtag)
        if (observed.isBlank()) {
            return Observation.NeedsUnlock(PeriodicSyncNeedsUnlockReason.ETAG_UNAVAILABLE)
        }
        if (previousEtag == null) {
            return Observation.NeedsUnlock(PeriodicSyncNeedsUnlockReason.INITIAL_BASELINE_REQUIRED)
        }
        return if (cleanEtag(previousEtag) == observed) {
            Observation.Unchanged
        } else {
            Observation.NeedsUnlock(PeriodicSyncNeedsUnlockReason.REMOTE_CHANGED)
        }
    }
}

/**
 * 按库持久化的元数据探测状态。库路径和目标配置仅用于派生偏好键与身份摘要；持久值包括目标
 * 身份、不透明 ETag、待解锁状态、最近一次完整同步的本地 KDBX 密文摘要和非秘密回滚库命名空间。
 * 写入使用 commit，确保 Worker 成功返回后立即进程死亡也能保留结果。
 */
@Singleton
class PeriodicSyncProbeStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    internal fun read(vaultIdentity: String): PeriodicSyncProbeRead {
        if (vaultIdentity.isBlank()) return PeriodicSyncProbeRead.Corrupt
        val keys = keysFor(vaultIdentity)
        return try {
            val values = preferences.all
            val keySet = keys.requiredSet()
            if (keySet.none(values::containsKey)) return PeriodicSyncProbeRead.Missing
            if (keySet.any { !values.containsKey(it) }) return PeriodicSyncProbeRead.Corrupt

            val targetIdentity = values[keys.targetIdentity] as? String
                ?: return PeriodicSyncProbeRead.Corrupt
            val etag = values[keys.etag] as? String
                ?: return PeriodicSyncProbeRead.Corrupt
            val needsUnlock = values[keys.needsUnlock] as? Boolean
                ?: return PeriodicSyncProbeRead.Corrupt
            if ((values.containsKey(keys.syncedContentDigest) && values[keys.syncedContentDigest] !is String) ||
                (values.containsKey(keys.rollbackVaultScope) && values[keys.rollbackVaultScope] !is String)
            ) return PeriodicSyncProbeRead.Corrupt
            val syncedDigest = values[keys.syncedContentDigest] as? String
            val rollbackVaultScope = values[keys.rollbackVaultScope] as? String
            if (!isTargetIdentity(targetIdentity) || !isSafeEtag(etag) || (etag.isBlank() && !needsUnlock)) {
                return PeriodicSyncProbeRead.Corrupt
            }
            if ((syncedDigest != null && !isDigest(syncedDigest)) ||
                (rollbackVaultScope != null && !isRollbackVaultScope(rollbackVaultScope))
            ) return PeriodicSyncProbeRead.Corrupt
            if ((syncedDigest == null) != (rollbackVaultScope == null)) return PeriodicSyncProbeRead.Corrupt
            PeriodicSyncProbeRead.Stored(
                PeriodicSyncProbeRecord(
                    targetIdentity,
                    cleanEtag(etag),
                    needsUnlock,
                    syncedContentDigest = syncedDigest,
                    rollbackVaultScope = rollbackVaultScope
                )
            )
        } catch (_: Exception) {
            PeriodicSyncProbeRead.Corrupt
        }
    }

    internal fun write(vaultIdentity: String, record: PeriodicSyncProbeRecord): Boolean {
        if (vaultIdentity.isBlank() || !isTargetIdentity(record.targetIdentity) ||
            !isSafeEtag(record.observedEtag) || (record.observedEtag.isBlank() && !record.needsUnlock) ||
            (record.syncedContentDigest != null && !isDigest(record.syncedContentDigest)) ||
            (record.rollbackVaultScope != null && !isRollbackVaultScope(record.rollbackVaultScope)) ||
            ((record.syncedContentDigest == null) != (record.rollbackVaultScope == null))
        ) {
            return false
        }
        val keys = keysFor(vaultIdentity)
        return try {
            preferences.edit()
                .putString(keys.targetIdentity, record.targetIdentity)
                .putString(keys.etag, cleanEtag(record.observedEtag))
                .putBoolean(keys.needsUnlock, record.needsUnlock)
                .apply {
                    if (record.syncedContentDigest != null) {
                        putString(keys.syncedContentDigest, record.syncedContentDigest)
                    } else {
                        remove(keys.syncedContentDigest)
                    }
                    if (record.rollbackVaultScope != null) {
                        putString(keys.rollbackVaultScope, record.rollbackVaultScope)
                    } else {
                        remove(keys.rollbackVaultScope)
                    }
                }
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    internal fun clear(vaultIdentity: String): Boolean {
        if (vaultIdentity.isBlank()) return false
        val keys = keysFor(vaultIdentity)
        return try {
            preferences.edit()
                .remove(keys.targetIdentity)
                .remove(keys.etag)
                .remove(keys.needsUnlock)
                .remove(keys.syncedContentDigest)
                .remove(keys.rollbackVaultScope)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun keysFor(vaultIdentity: String): ProbeKeys {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(vaultIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val prefix = "$KEY_PREFIX$digest"
        return ProbeKeys(
            "${prefix}_target",
            "${prefix}_etag",
            "${prefix}_pending",
            "${prefix}_synced_digest",
            "${prefix}_rollback_scope"
        )
    }

    private fun isTargetIdentity(value: String): Boolean = TARGET_IDENTITY_PATTERN.matches(value)

    private fun isDigest(value: String): Boolean = DIGEST_PATTERN.matches(value)

    private fun isRollbackVaultScope(value: String): Boolean = ROLLBACK_VAULT_SCOPE_PATTERN.matches(value)

    private fun isSafeEtag(value: String): Boolean = value.length <= MAX_ETAG_LENGTH &&
        value.none { it.code < ASCII_SPACE || it.code == ASCII_DELETE }

    private data class ProbeKeys(
        val targetIdentity: String,
        val etag: String,
        val needsUnlock: String,
        val syncedContentDigest: String,
        val rollbackVaultScope: String
    ) {
        fun requiredSet(): Set<String> = setOf(targetIdentity, etag, needsUnlock)
    }

    private companion object {
        const val PREFERENCES_NAME = "keepasskey_periodic_sync_probe"
        const val KEY_PREFIX = "probe_v1_"
        const val MAX_ETAG_LENGTH = 4_096
        const val ASCII_SPACE = 0x20
        const val ASCII_DELETE = 0x7f
        val TARGET_IDENTITY_PATTERN = Regex("[0-9a-f]{64}")
        val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
        val ROLLBACK_VAULT_SCOPE_PATTERN = Regex("[0-9A-Fa-f]{32}")
    }
}
