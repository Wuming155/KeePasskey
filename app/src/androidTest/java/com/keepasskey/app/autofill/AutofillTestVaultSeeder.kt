package com.keepasskey.app.autofill

import android.content.Context
import android.util.Log
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * ISSUE-P2-73 AC③ / ISSUE-P3-610：设备侧用例的密码库播种器（instrumented 用例专用，
 * **不属于产品代码**）。
 *
 * ## 时序约束（关键）
 *
 * 库列表由 `VaultDatabaseCatalog.buildDatabaseList()` 在**进程内首次构造
 * `RealVaultRepository` 时**扫描 `filesDir` 的 `*.kdbx` 得出，且 `RealVaultRepository` 是懒加载的
 * Hilt 单例（`MainApplication` 不注入它）。因此只要在「本进程内任何填充/解锁消费者首次触达
 * 仓库之前」把文件写入 `filesDir`，该文件就会被登记——无需任何测试专用注入通道。
 *
 * ## ISSUE-P3-610 加固（DYN-04）
 *
 * ①**身份校验**：写入后当场以生产解密路径读回，核对能否解开、条目数与会话标题——落盘成功
 * 不等于内容正确。②**不无条件删除同名库**：仅当既有同名文件被确认**归本测试所有**
 * （[isOwnedSynthetic]：可用测试主密码解开且含测试条目）时才覆盖；否则显式失败，绝不误删
 * 用户数据。③**干扰项**：[seedDecoy] 造一个**不同主密码**的合成库，供「已有旧活动库」矩阵；
 * 日志只记非敏感库身份与文件名，**绝不打印主密码/凭据**。
 */
internal object AutofillTestVaultSeeder {

    const val TAG = "AutofillAC3Seed"

    /**
     * 播种主测试库并返回产物文件。已存在同名库时，仅当确认归本测试所有才覆盖。
     *
     * @param context 被测应用上下文（`targetContext`）
     * @param clientPackage 测试客户端包名（写入条目的 `android://` 绑定）
     */
    fun seed(context: Context, clientPackage: String): File = seedVault(
        context = context,
        fileName = AutofillSeedContract.VAULT_FILE_NAME,
        masterPassword = AutofillSeedContract.MASTER_PASSWORD_TEXT,
        entryTitle = AutofillSeedContract.ENTRY_TITLE,
        username = AutofillSeedContract.USERNAME,
        password = AutofillSeedContract.PASSWORD,
        boundUrl = AutofillPackageNames.boundUrl(clientPackage)
    )

    /**
     * ISSUE-P3-610：造一个**不同主密码**的合成干扰库（模拟「设备上已有别的活动库」）。
     * 条目 URL 绑定同一客户端包名，使其在填充 UI 中同样可被列举——用于证明选择前提生效后
     * 打开的是播种库而非干扰库。
     */
    fun seedDecoy(context: Context): File = seedVault(
        context = context,
        fileName = AutofillSeedContract.DECOY_FILE_NAME,
        masterPassword = AutofillSeedContract.DECOY_MASTER_PASSWORD_TEXT,
        entryTitle = AutofillSeedContract.DECOY_ENTRY_TITLE,
        username = AutofillSeedContract.DECOY_USERNAME,
        password = AutofillSeedContract.DECOY_PASSWORD,
        boundUrl = AutofillPackageNames.boundUrl("com.keepasskey.decoy")
    )

    private fun seedVault(
        context: Context,
        fileName: String,
        masterPassword: String,
        entryTitle: String,
        username: String,
        password: String,
        boundUrl: String
    ): File {
        val vault = File(context.filesDir, fileName)

        // 同一 filesDir 内若已有别的 .kdbx，活动库归属存在歧义——如实记录而非静默。
        val siblings = context.filesDir.listFiles { file ->
            file.isFile && file.extension.equals("kdbx", ignoreCase = true)
        }?.map { it.name }.orEmpty()
        Log.i(TAG, "播种前 filesDir 内的 .kdbx: $siblings")

        // ISSUE-P3-610 ②：同名既有库只在确认归本测试所有时才覆盖，绝不无条件删除
        if (vault.exists() && !isOwnedSynthetic(vault, masterPassword)) {
            throw IllegalStateException("同名库 $fileName 非本测试合成产物，拒绝覆盖以保护数据")
        }

        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(entryTitle, isProtected = false),
                KdbxConstants.Fields.USER_NAME to ProtectedString(username, isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString(password, isProtected = true),
                KdbxConstants.Fields.URL to ProtectedString(boundUrl, isProtected = false)
            )
        )
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )

        vault.delete()
        FileOutputStream(vault).use {
            KdbxFile.save(it, database, masterPassword.toCharArray())
        }

        verifySynthetic(vault, masterPassword, entryTitle)
        Log.i(TAG, "播种完成: ${vault.name} 大小=${vault.length()} 字节 条目=${entryTitle}")
        return vault
    }

    /** 删除本测试拥有的合成库（**只删自己的**；非本测试产物保持不变）。 */
    fun deleteOwned(vault: File, masterPassword: String): Boolean {
        if (!vault.exists()) return true
        if (!isOwnedSynthetic(vault, masterPassword)) {
            Log.w(TAG, "跳过删除非本测试产物: ${vault.name}")
            return false
        }
        return vault.delete()
    }

    /** 文件内容 SHA-256（十六进制）；用于「旧库哈希不变」断言。只读、不打印内容。 */
    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    /** 既有同名库是否归本测试所有：可用 [masterPassword] 解开且含 [entryTitle]。 */
    private fun isOwnedSynthetic(file: File, masterPassword: String): Boolean = try {
        val db = FileInputStream(file).use { KdbxFile.load(it, masterPassword.toCharArray()) }
        val titles = db.rootGroup.allEntries().map { it.title }
        db.clearSensitiveData()
        Log.i(TAG, "同名文件可判归属: ${file.name} 条目数=${titles.size}")
        titles.size == 1
    } catch (t: Throwable) { // cancel-n/a: 保护段为阻塞式解密探测（无挂起点）
        Log.i(TAG, "同名文件不归本测试所有（无法以测试主密码解开）: ${file.name}")
        false
    }

    /** ISSUE-P3-610 ①：写后当场校验——签名前缀 + 可解 + 条目标题/数量一致。 */
    private fun verifySynthetic(vault: File, masterPassword: String, entryTitle: String) {
        val header = vault.inputStream().use { input ->
            ByteArray(AutofillSeedContract.KDBX_SIGNATURE.size).also { input.read(it) }
        }
        check(vault.isFile && vault.length() > 0L) { "播种失败：密码库未落盘" }
        check(header.contentEquals(AutofillSeedContract.KDBX_SIGNATURE)) {
            "播种失败：产物不是 KDBX（签名前缀不符）"
        }
        val db = FileInputStream(vault).use { KdbxFile.load(it, masterPassword.toCharArray()) }
        val titles = db.rootGroup.allEntries().map { it.title }
        db.clearSensitiveData()
        check(titles == listOf(entryTitle)) {
            "播种失败：内容校验不符（期望 [$entryTitle]，实际 $titles）"
        }
    }
}
