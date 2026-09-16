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
import java.io.FileOutputStream

/**
 * ISSUE-P2-73 AC③：设备侧用例的密码库播种器（instrumented 用例专用，**不属于产品代码**）。
 *
 * ## 时序约束（关键）
 *
 * 库列表由 `VaultDatabaseCatalog.buildDatabaseList()` 在**进程内首次构造
 * `RealVaultRepository` 时**扫描 `filesDir` 的 `*.kdbx` 得出，且 `RealVaultRepository` 是懒加载的
 * Hilt 单例（`MainApplication` 不注入它）。因此只要在「本进程内任何填充/解锁消费者首次触达
 * 仓库之前」把文件写入 `filesDir`，该文件就会被登记为活动库——无需任何测试专用注入通道，
 * 也无需第二次运行。
 *
 * ## 产物
 *
 * 用**生产写入管线**（`KdbxFile.save`，KDBX v4 / AES-KDF 默认档）现场产出真实密码库，
 * 条目 `URL` 采用包名维度绑定形态 `android://<测试客户端包名>`，供「已解锁分支 + 二次确认」
 * 路径使用。全部凭据为**虚构测试值**。
 */
internal object AutofillTestVaultSeeder {

    const val TAG = "AutofillAC3Seed"

    /**
     * 播种密码库并返回产物文件。
     *
     * @param context 被测应用上下文（`targetContext`）
     * @param clientPackage 测试客户端包名（写入条目的 `android://` 绑定）
     */
    fun seed(context: Context, clientPackage: String): File {
        val vault = File(context.filesDir, AutofillSeedContract.VAULT_FILE_NAME)

        // 同一 filesDir 内若已有别的 .kdbx，活动库归属存在歧义（列表按首条标定活动库），
        // 故如实记录而不是静默留下不确定性。
        val siblings = context.filesDir.listFiles { file ->
            file.isFile && file.extension.equals("kdbx", ignoreCase = true)
        }?.map { it.name }.orEmpty()
        Log.i(TAG, "播种前 filesDir 内的 .kdbx: $siblings")

        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to
                    ProtectedString(AutofillSeedContract.ENTRY_TITLE, isProtected = false),
                KdbxConstants.Fields.USER_NAME to
                    ProtectedString(AutofillSeedContract.USERNAME, isProtected = false),
                KdbxConstants.Fields.PASSWORD to
                    ProtectedString(AutofillSeedContract.PASSWORD, isProtected = true),
                KdbxConstants.Fields.URL to
                    ProtectedString(AutofillPackageNames.boundUrl(clientPackage), isProtected = false)
            )
        )
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )

        vault.delete()
        FileOutputStream(vault).use {
            KdbxFile.save(it, database, AutofillSeedContract.MASTER_PASSWORD_TEXT.toCharArray())
        }

        val header = vault.inputStream().use { input ->
            ByteArray(AutofillSeedContract.KDBX_SIGNATURE.size).also { input.read(it) }
        }
        check(vault.isFile && vault.length() > 0L) { "播种失败：密码库未落盘" }
        check(header.contentEquals(AutofillSeedContract.KDBX_SIGNATURE)) {
            "播种失败：产物不是 KDBX（签名前缀不符）"
        }
        Log.i(TAG, "播种完成: ${vault.name} 大小=${vault.length()} 字节 条目绑定=android://$clientPackage")
        return vault
    }
}
