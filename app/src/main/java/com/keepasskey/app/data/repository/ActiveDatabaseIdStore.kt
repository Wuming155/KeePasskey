package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.core.log.AppLog

/**
 * 「活动密码库 ID」的唯一持久化落点（`ISSUE-P2-465` 自 [VaultDatabaseCatalog] 的私有实现抽出）。
 *
 * ## 为什么独立成类
 *
 * 该记录此前只被库列表（`VaultDatabaseCatalog`）读写。按库命名空间整改后，同步配置
 * （`ActiveVaultSyncNamespace`）也要读同一份记录来回答「当前活动库是谁」——
 * 若各写一份 prefs 名与键名，立刻会出现两种口径（本仓在 `ISSUE-P2-43` 的默认值问题上
 * 已踩过同型坑）。故：**文件名与键名在此单点定义**，两个消费方都引用本类。
 *
 * ## 容错口径（与抽出前逐字一致）
 *
 * 读写失败（无 SharedPreferences / 平台异常）一律按「未知」处理并落 `AppLog` 告警，
 * 绝不向上抛异常：活动库 ID 缺失只会让调用方退回默认命名空间 / 首项，不会阻断主流程。
 */
internal class ActiveDatabaseIdStore(private val context: Context) {

    /** 读取活动库 ID；未登记或读取失败返回 null。 */
    fun load(): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_ACTIVE_DATABASE_ID, null)
        } catch (t: Throwable) {
            AppLog.w(TAG, "读取活动库 ID 失败，按未知处理", t)
            null
        }
    }

    /** 登记 / 清除活动库 ID（null = 清除）。写入失败只留痕，不影响调用方结果。 */
    fun save(id: String?) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
            if (id == null) {
                sp.edit().remove(KEY_ACTIVE_DATABASE_ID).apply()
            } else {
                sp.edit().putString(KEY_ACTIVE_DATABASE_ID, id).apply()
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "写入活动库 ID 失败", t)
        }
    }

    companion object {
        const val TAG = "ActiveDbIdStore"

        /** 库列表（活动项 / 已知库登记表）偏好文件名 */
        const val PREFS_NAME = "keepasskey_vault_meta"

        /** 活动库 ID 键（值 = `VaultDatabaseInfo.id`：`content://` / 绝对路径 / 沙盒裸文件名） */
        const val KEY_ACTIVE_DATABASE_ID = "active_database_id"
    }
}
