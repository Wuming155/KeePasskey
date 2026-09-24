package com.keepasskey.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultRemovalKind
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.file.KdbxKdfStrengthAssessment
import com.keepasskey.database.file.KdbxKdfStrengthAssessor
import com.keepasskey.database.file.KdbxKeyFileGenerator
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 密码库生命周期协调器（ISSUE-P3-31 批次 B 自 `RealVaultRepository` 拆出，纯结构性改动）。
 *
 * 职责单一：解锁（含 `content://` 流式通道与文件不存在时的初始化建库）、显式密钥文件因子建库、
 * 移除已知库、登记外部库，以及会话级凭据轮换 / 锁定 / 锁定态查询（ISSUE-P3-305 并入）。
 * 所有列表刷新与活动库切换经构造参数回调回仓库，
 * 不改变任何既有顺序语义（如「先 selectDatabase 再 refreshDatabases」）。
 */
internal class VaultLifecycleCoordinator(
    private val context: Context,
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val catalog: VaultDatabaseCatalog,
    private val refresh: suspend () -> Unit,
    private val selectDatabase: suspend (String) -> Unit
) {

    /**
     * 解锁当前活动库；[candidates] 为仓库持有的当前库列表快照，
     * 取「活动项」失败时回退首项，二者皆空即失败（与拆分前一致）。
     */
    suspend fun unlockActiveDatabase(
        candidates: List<VaultDatabaseInfo>,
        passwordChars: CharArray,
        keyFileData: ByteArray?,
        readOnly: Boolean
    ): KdbxResult<Unit> {
        val activeDb = candidates.firstOrNull { it.isActive }
            ?: candidates.firstOrNull()
            ?: return KdbxResult.Failure(
                IllegalStateException("无活动数据库"),
                strings.get(R.string.repo_no_active_database)
            )

        val result = if (activeDb.path.startsWith("content://")) {
            val uri = Uri.parse(activeDb.path)
            databaseSession.openStream(
                pathIdentifier = activeDb.path,
                inputStreamProvider = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IOException("无法打开数据库文件流: ${activeDb.name}")
                },
                saveWriter = SafVaultCreation.saveWriter(context, uri, activeDb.name),
                passwordChars = passwordChars,
                keyFileData = keyFileData,
                readOnly = readOnly
            )
        } else {
            val targetFile = File(activeDb.path)
            if (!targetFile.exists()) {
                if (keyFileData != null) {
                    return KdbxResult.Failure(
                        IllegalArgumentException("数据库文件不存在: ${targetFile.absolutePath}"),
                        strings.get(R.string.repo_file_missing_with_keyfile)
                    )
                }
                // 文件尚不存在时初始化创建。
                // ISSUE-P2-85：本入口**无用户预设**（库已在目录中登记、只是文件缺失），
                // 故显式取 KDBX4 官方默认算法 AES-256-CBC + Argon2id；
                // 带预设的建库路径见 [createDatabaseWithKeyFile]（必须显式传算法）。
                val createResult = databaseSession.create(
                    file = targetFile,
                    name = activeDb.name.removeSuffix(".kdbx"),
                    passwordChars = passwordChars,
                    useArgon2 = true,
                    cipherUuid = KdbxConstants.Cipher.AES_256_CBC
                )
                refresh()
                return createResult
            }
            databaseSession.open(targetFile, passwordChars, keyFileData, readOnly)
        }

        if (result is KdbxResult.Success) {
            refresh()
        }
        return result
    }

    /**
     * 以显式密钥文件因子建库——复合密钥第二因子真实落地。
     *
     * 生成型密钥文件由 database 模块的**唯一生成器** [KdbxKeyFileGenerator] 产出
     * （app 层严禁重写 KeyFile 规范实现），随 [DatabaseSession.create] 的 `keyFileData`
     * 形参进入 `KdbxFile.save → deriveKeys` 的官方解析梯子（`KdbxKeyFile`，internal）；
     * 会话成功建库后按借用语义克隆持有该因子，使后续 `save()` 以同一复合密钥重加密、
     * 且既有导出通道 `exportKeyFileBytes()` 能把这份密钥文件交付用户。
     *
     * ISSUE-P2-85：[preset] 为**类型化的整组选择**（外层算法 + KDF），两者都真实落到文件头。
     * 此前它只是字符串且仅用 `contains("AES-KDF")` 反推 KDF，外层算法被整段丢弃。
     *
     * ISSUE-P2-229：[targetUri] 非空即「经系统文件选择器自选位置」建库（此前新建一律静默落在
     * 应用私有目录，用户既看不到也无法选）；为 null 时维持既有的 `filesDir` 路径与全部行为。
     */
    suspend fun createDatabaseWithKeyFile(
        name: String,
        masterPassword: CharArray,
        keyFileFactor: CreateKeyFileFactor,
        preset: CreateVaultPreset,
        targetUri: String? = null
    ): KdbxResult<Unit> {
        val generatedKeyFile = if (keyFileFactor is CreateKeyFileFactor.Generate) {
            try {
                KdbxKeyFileGenerator.generate()
            } catch (t: Throwable) {
                // 禁止静默失败：生成失败时**不创建任何库**（否则会留下无第二因子的库）
                return KdbxResult.Failure(t, strings.get(R.string.repo_keyfile_generate_failed))
            }
        } else {
            null
        }
        val keyFileData: ByteArray? = when (keyFileFactor) {
            is CreateKeyFileFactor.Existing -> keyFileFactor.bytes
            is CreateKeyFileFactor.Generate -> generatedKeyFile
            CreateKeyFileFactor.None -> null
        }

        return try {
            val uri = targetUri?.let { Uri.parse(it) }
            if (uri == null) {
                createInFilesDir(name, masterPassword, keyFileData, preset)
            } else {
                SafVaultCreation.create(
                    context = context,
                    strings = strings,
                    databaseSession = databaseSession,
                    name = name,
                    masterPassword = masterPassword,
                    keyFileData = keyFileData,
                    preset = preset,
                    targetUri = uri
                ) { dbName, path -> importExternalDatabase(dbName, path, SYNC_TYPE_FILE_PICKER) }
            }
        } finally {
            // 生成副本用毕即擦：会话已克隆自己的副本供保存与导出复用
            // （Existing 分支的字节归调用方所有，本层不得擦除）
            generatedKeyFile?.fill(0)
        }
    }

    /** 既有内部存储建库通道（ISSUE-P2-229 起为 `targetUri == null` 分支，行为逐字未改） */
    private suspend fun createInFilesDir(
        name: String,
        masterPassword: CharArray,
        keyFileData: ByteArray?,
        preset: CreateVaultPreset
    ): KdbxResult<Unit> {
        val filesDir = context.filesDir ?: return KdbxResult.Failure(
            IllegalStateException("No filesDir"),
            strings.get(R.string.repo_files_dir_unavailable)
        )
        val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
        val result = databaseSession.create(
            file = File(filesDir, fileName),
            name = name.removeSuffix(".kdbx"),
            passwordChars = masterPassword,
            useArgon2 = preset.useArgon2,
            keyFileData = keyFileData,
            cipherUuid = preset.cipherUuid
        )
        if (result is KdbxResult.Success) {
            selectDatabase(fileName)
        }
        refresh()
        return result
    }


    /**
     * 移除已知库：摘除注册表条目、**仅当条目确为应用私有库时**删除沙盒内文件、
     * 必要时关闭会话并清空活动库 ID。
     *
     * `ISSUE-P1-241`：[kind] 是界面按 `VaultRemovalKind.of(条目 path, filesDir)` 判定出的
     * **真实对象**，与确认弹窗所用文案同一枚判据。此前本方法按 `id` 形状反推——
     * `File(filesDir, id)` 存在即删，于是「外部登记的 id 恰是裸文件名」（用户在「打开已有」里
     * 把本地路径填成相对文件名）会**误删应用私有库里的同名库文件**，而弹窗文案承诺的是
     * 「不会删除物理文件」。现改为由调用方显式声明；`kind` 与 `id` 不一致时最坏结果是
     * 「该删的没删」（卡片仍在列表里），**绝不会**反向变成「不该删的删了」。
     *
     * 另：注册表条目的匹配收紧为 `id` / `path`（去掉按 `name` 匹配）——外部登记的
     * `name` 只是展示名，与其它库的文件名撞车时会摘掉**另一个库**的登记。
     */
    suspend fun removeDatabase(id: String, kind: VaultRemovalKind): KdbxResult<Unit> {
        return try {
            val known = catalog.loadKnownDatabases()
            val matchExternal = known.find { it.id == id || it.path == id }
            if (matchExternal != null) {
                catalog.saveKnownDatabases(known.filter { it.id != matchExternal.id && it.path != matchExternal.path })
            }

            if (kind == VaultRemovalKind.PRIVATE_FILE) {
                val filesDir = context.filesDir
                if (filesDir != null) {
                    val targetFile = File(filesDir, id)
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                }
            }

            if (databaseSession.currentFile?.name == id || databaseSession.currentPathIdentifier == id) {
                databaseSession.close()
            }
            if (catalog.loadActiveDbId() == id) {
                catalog.saveActiveDbId(null)
            }
            refresh()
            KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            KdbxResult.Failure(t, strings.get(R.string.repo_remove_failed, t.message ?: ""))
        }
    }

    /** 登记外部库（`content://` 或文件路径），并立即置为活动库 */
    suspend fun importExternalDatabase(
        name: String,
        path: String,
        syncType: String
    ): KdbxResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (t: Throwable) {
                    // 部分外部 Provider 不支持持久化授权，容错继续（不得改为硬失败，见 ISSUE-P3-230 AC③）；
                    // 但**不得静默**：本次会话结束后该 uri 将失去读权限（重启后库打不开），
                    // 故落脱敏告警留痕，并由列表状态 / 解锁页一次性提示向用户归因（AC①）
                    AppLog.w(TAG, "持久化 URI 授权失败，重启后该库可能需要重新选择文件", t)
                }
            }
            val sanitizedName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
            val entry = VaultDatabaseCatalog.KnownDatabaseEntry(
                id = path,
                name = sanitizedName,
                path = path,
                isRemote = syncType != SYNC_TYPE_LOCAL_DEVICE && syncType != SYNC_TYPE_FILE_PICKER,
                syncType = syncType,
                lastOpenedAt = ""
            )
            val updated = catalog.loadKnownDatabases().filter { it.path != path && it.id != entry.id } + entry
            catalog.saveKnownDatabases(updated)
            catalog.saveActiveDbId(entry.id)
            refresh()
            KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            KdbxResult.Failure(t, strings.get(R.string.repo_import_failed, t.message ?: ""))
        }
    }

    /**
     * 评估某个密码库来源的工作因子是否**低于本应用建库默认强度**（ISSUE-P2-87，非阻断提示）。
     *
     * 读取的是 KDBX **外层明文头部**：按规范，头部位于认证之前、承载 KDF 参数（Argon2 `M / I / P`
     * 或 AES-KDF `R`），故本方法**不需要任何凭据**，也不解密载荷、不接触库内容。
     * 解析走数据库模块的**唯一**头部解析实现 [KdbxHeader.deserialize]（与解锁、保存同一条路径，
     * 不新开旁路），并同样受其认证前加固闸门（字段长度 / 累计字节 / 字段数）约束。
     *
     * **任何失败一律降级为「未评估」（返回 null），绝不外抛**：本方法只服务于导入成功后的一条
     * 提示，`content://` 提供方拒绝、远端 URL 不是本地文件、第三方构造的损坏头部等情况都不得
     * 反过来影响已成功的导入，也不得据此谎报「低于基线」。
     *
     * 判据与文案口径见 [KdbxKdfStrengthAssessor]（结论只能表述为「低于本应用建库默认强度」，
     * 不得解读为「不安全」；且**不修改任何 KDF 参数**）。
     */
    suspend fun assessKdfStrength(path: String): KdbxKdfStrengthAssessment? = withContext(Dispatchers.IO) {
        try {
            openVaultSourceStream(path)?.use { stream ->
                KdbxKdfStrengthAssessor.assess(KdbxHeader.deserialize(stream).first.kdfParameters)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 打开密码库来源的读取流：`content://` 走 SAF，其余按本地文件路径。
     * 来源不可用（非本地文件的远端地址 / 提供方拒绝 / 文件不存在）返回 null。
     */
    /**
     * 更换主凭据：会话层重加密写盘（`DatabaseSession.changeCredentials`），
     * **仅成功时**刷新库列表（ISSUE-P3-305 自 `RealVaultRepository` 逐行搬出）。
     */
    suspend fun changeMasterPassword(newPassword: CharArray): KdbxResult<Unit> {
        val result = databaseSession.changeCredentials(newPassword)
        if (result is KdbxResult.Success) {
            refresh()
        }
        return result
    }

    /** 锁定当前库并刷新库列表（顺序与拆分前一致：先 `lock()` 再刷新）。 */
    suspend fun lockDatabase() {
        databaseSession.lock()
        refresh()
    }

    /** 当前会话是否**非** `OPENED`（锁定 / 关闭 / 未打开一律为 true）。 */
    fun isLocked(): Boolean {
        return databaseSession.state.value != DatabaseSession.SessionState.OPENED
    }

    private fun openVaultSourceStream(path: String): InputStream? =
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            File(path).takeIf { it.isFile }?.inputStream()
        }

    companion object {
        private const val TAG = "VaultLifecycle"

        /**
         * `syncType` 的两个「本机」取值（ISSUE-P2-229 收敛为常量：此前它们是散落在
         * `isRemote` 判定里的裸字面量，新增分支时极易写成第三个变体而被判成远端库）。
         * 值与设置页 / 打开对话框的既有字符串保持一致，**不得**改动字面量本身。
         */
        const val SYNC_TYPE_LOCAL_DEVICE = "本地设备存储"
        const val SYNC_TYPE_FILE_PICKER = "系统文件选择器"
    }
}
