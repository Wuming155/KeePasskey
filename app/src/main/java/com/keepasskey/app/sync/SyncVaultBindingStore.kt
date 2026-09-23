package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.sync.engine.SyncCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步目标 ↔ 密码库身份绑定登记表（`ISSUE-P2-291` AC①）。
 *
 * ## 解决的问题
 *
 * 同步凭据 / 缓存 / 基线 / 防回滚原本只按 `remotePath` 键控——同一台服务器与路径下，
 * 换库后新库会继承另一库的全部同步状态并整库覆盖其云端副本（跨库数据丢失）。
 * 本表为每个 `remotePath` 登记「创建同步关系时绑定的库身份」（根分组 UUID hex，
 * 建库随机生成、跨保存稳定），同步周期据此裁决：
 * - 未登记：当前库即绑定者（登记 + 迁移旧键），正常进行；
 * - 登记与当前库一致：正常进行；
 * - 登记与当前库**不同**：中止于任何网络写之前（`SyncOutcome.VaultBindingMismatch`），
 *   由用户显式确认整库覆盖（`SyncCycleRunner.takeoverVaultBinding` 才改绑）。
 *
 * ## 落盘口径（与凭据同生命周期）
 *
 * 刻意与 [SyncCredentialsStore] **共用同一 prefs 文件**（[SyncCredentialsStore.PREFS_NAME]）：
 * `SyncCredentialsStore.clear()` 的 `prefs.edit().clear()` 会连同绑定一并销毁——
 * 同步关系终止（换服务器 / 退出同步）后，残留绑定会让新配置继承「旧库归属」而错误拦截
 * 或放行。键形态 `bound_vault_<SHA-256(remotePath)>`，值为根分组 UUID hex（非敏感）。
 */
@Singleton
class SyncVaultBindingStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(SyncCredentialsStore.PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取 `remotePath` 当前绑定的库身份（根分组 UUID hex）；未绑定返回 null。 */
    fun loadBinding(remotePath: String): String? = prefs.getString(keyFor(remotePath), null)

    /** 登记 / 改绑 `remotePath` 的归属库（[vaultUuidHex] 为 `KdbxUuid.toHexString()` 形态）。 */
    fun saveBinding(remotePath: String, vaultUuidHex: String) {
        prefs.edit().putString(keyFor(remotePath), vaultUuidHex).apply()
    }

    private fun keyFor(remotePath: String): String =
        "bound_vault_" + SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8))
}
