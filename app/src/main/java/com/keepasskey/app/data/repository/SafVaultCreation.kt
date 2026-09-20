package com.keepasskey.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.SafDocumentCleanup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 「自选位置」建库通道（**ISSUE-P2-229**，自 `VaultLifecycleCoordinator` 抽出为独立件）。
 *
 * ## 为什么单独成件
 *
 * SAF 建库的全部复杂度都来自一个事实：**`DatabaseSession.create` 只收 `File`，
 * 而 SAF 文档没有可原子重命名的同目录临时件**。于是这条路径必须自带
 * 「临时中转 → 搬运 → 读回复核 → 失败回滚」的完整编排，与内部存储那条一次性 `create` 的通道
 * 没有任何共享代码；留在协调器内会让该文件越过单文件行数阈值（工程规则「约 400 行即拆」）。
 *
 * ## 顺序即安全边界（不得重排，`CreateVaultLocationWiringTest` 逐条锁定）
 *
 * 1. **持久化授权先行**：拿不到 `takePersistableUriPermission` 就在动手建库**之前**显式失败，
 *    绝不留下「重启后打不开」的半成品库（打开已有库侧的同一静默 `catch` 已转登 `ISSUE-P3-230`）；
 * 2. **临时件必删**：`cacheDir` 中转库在 `finally` 删除；
 * 3. **失败即回滚**：三条失败出口都经 [SafDocumentCleanup] 删除本次 `CreateDocument` 产出的文档，
 *    不在用户选定位置留下空库；
 * 4. **读回复核**：写完后以 [DatabaseSession.openStream] 重新绑定该文档——既证明「写得进也读得回」，
 *    又让用户停留在已解锁态（与内部路径体验一致）。
 *
 * ## 两条已登记降级（不得对外承诺为等价能力）
 *
 * SAF 文档上无原子 rename ⇒ 后续写回是 `"rwt"` 截断式**非原子**写、无 `.bak` 滚动备份；
 * 且同步层硬依赖本地 `File` ⇒ 这类库**不支持** WebDAV / S3 同步。
 * 详见 `docs/architecture/已知工程限界.md` §24；向导内已如实告知用户。
 */
internal object SafVaultCreation {

    /**
     * 在 [targetUri] 指向的 SAF 文档建库。
     *
     * @param keyFileData 已解析好的复合密钥第二因子字节（生成型副本由调用方负责用毕擦除）
     * @param register 登记回调（复用仓库既有的 `importExternalDatabase`：写目录 + 置活动 + 刷新列表）
     */
    suspend fun create(
        context: Context,
        strings: StringsProvider,
        databaseSession: DatabaseSession,
        name: String,
        masterPassword: CharArray,
        keyFileData: ByteArray?,
        preset: CreateVaultPreset,
        targetUri: Uri,
        register: suspend (name: String, path: String) -> KdbxResult<Unit>
    ): KdbxResult<Unit> = withContext(Dispatchers.IO) {
        val displayName = name.removeSuffix(".kdbx")

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                targetUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { t ->
            return@withContext KdbxResult.Failure(
                t,
                strings.get(R.string.repo_saf_persist_consent_failed)
            )
        }

        val tempFile = File(context.cacheDir, "vault-create-${System.currentTimeMillis()}.kdbx")
        try {
            val created = databaseSession.create(
                file = tempFile,
                name = displayName,
                passwordChars = masterPassword,
                useArgon2 = preset.useArgon2,
                keyFileData = keyFileData,
                cipherUuid = preset.cipherUuid
            )
            if (created !is KdbxResult.Success) {
                SafDocumentCleanup.deleteCreatedDocument(context, targetUri)
                return@withContext created
            }

            val bytes = tempFile.readBytes()
            val written = runCatching {
                context.contentResolver.openOutputStream(targetUri, "w")?.use { os ->
                    os.write(bytes)
                    os.flush()
                } ?: throw IOException("无法写入所选位置")
            }
            bytes.fill(0)
            if (written.isFailure) {
                databaseSession.close()
                SafDocumentCleanup.deleteCreatedDocument(context, targetUri)
                return@withContext KdbxResult.Failure(
                    written.exceptionOrNull() ?: IOException("无法写入所选位置"),
                    strings.get(R.string.repo_saf_write_failed)
                )
            }

            databaseSession.close()
            val opened = databaseSession.openStream(
                pathIdentifier = targetUri.toString(),
                inputStreamProvider = {
                    context.contentResolver.openInputStream(targetUri)
                        ?: throw IOException("无法打开数据库文件流: $displayName")
                },
                saveWriter = saveWriter(context, targetUri, displayName),
                passwordChars = masterPassword,
                keyFileData = keyFileData,
                readOnly = false
            )
            if (opened !is KdbxResult.Success) {
                SafDocumentCleanup.deleteCreatedDocument(context, targetUri)
                return@withContext opened
            }
            register(name, targetUri.toString())
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    /**
     * `content://` 库的写回通道（解锁与新建两条路径共用，ISSUE-P2-229 收敛为单点）。
     *
     * `"rwt"` 截断式写是 SAF 文档的能力上限（无原子 rename / 无 `.bak`），已登记于限界 §24。
     */
    fun saveWriter(context: Context, uri: Uri, displayName: String): suspend (ByteArray) -> Unit =
        { bytes ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "rwt")?.use { os ->
                    os.write(bytes)
                    os.flush()
                } ?: throw IOException("无法写入目标数据库文件: $displayName")
            }
        }
}
