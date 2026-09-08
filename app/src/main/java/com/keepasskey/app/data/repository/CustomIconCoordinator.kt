package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first

/**
 * KDBX 自定义图标协调器（TASK-15，拆分自 RealVaultRepository 职责）。
 *
 * 自定义图标存储于库 Meta `CustomIcons`（KDBX 2.x 规范），条目经 `CustomIconUUID`
 * 引用；PNG 字节随库文件同步，KeePass 2.x / KeePassXC 互认。
 * 上传侧防线：PNG 魔数校验（fail-closed 拒绝非 PNG）与单图体积上限；
 * 相同字节内容去重，避免图标池无谓膨胀。
 */
internal class CustomIconCoordinator(
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    /**
     * 上传 PNG 字节为库级自定义图标，返回其 UUID（hex）。
     * 内容去重：与既有图标字节完全一致时直接返回既有 UUID，不重复落盘。
     */
    suspend fun addCustomIcon(pngBytes: ByteArray): KdbxResult<String> {
        if (!isPngBytes(pngBytes)) {
            return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.icon_invalid_not_png)),
                strings.get(R.string.icon_invalid_not_png)
            )
        }
        if (pngBytes.size > MAX_ICON_BYTES) {
            return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.icon_too_large)),
                strings.get(R.string.icon_too_large)
            )
        }
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        if (databaseSession.isReadOnly) {
            return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.readonly_save_rejected)),
                strings.get(R.string.readonly_save_rejected)
            )
        }

        val duplicate = db.customIcons.firstOrNull { it.data.contentEquals(pngBytes) }
        if (duplicate != null) {
            return KdbxResult.Success(duplicate.uuid.toHexString())
        }

        val icon = CustomIcon(uuid = KdbxUuid.random(), data = pngBytes.copyOf())
        databaseSession.updateDatabaseMeta { current ->
            current.copy(customIcons = current.customIcons + icon)
        }
        val persistResult = persistSession()
        return when (persistResult) {
            is KdbxResult.Success -> KdbxResult.Success(icon.uuid.toHexString())
            is KdbxResult.Failure -> persistResult
        }
    }

    /** 库内自定义图标池快照（UUID hex → PNG 字节），供 UI 解码渲染 */
    fun snapshotIconBytes(db: com.keepasskey.database.file.KdbxDatabase): Map<String, ByteArray> =
        db.customIcons.associate { it.uuid.toHexString() to it.data }

    companion object {
        /** PNG 文件魔数：\x89PNG\r\n\x1a\n */
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        /** 单个图标体积上限：KDBX 生态约定图标为小尺寸 PNG（KeePassXC 默认 128px） */
        const val MAX_ICON_BYTES: Int = 256 * 1024

        private fun isPngBytes(bytes: ByteArray): Boolean =
            bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
    }
}
