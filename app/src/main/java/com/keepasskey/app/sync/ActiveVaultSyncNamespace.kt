package com.keepasskey.app.sync

import java.io.File

/**
 * 当前活动密码库的**同步配置命名空间标识**（`ISSUE-P2-465` AC①）。
 *
 * ## 解决的问题
 *
 * 整改前同步凭据 / 远端路径以**全局扁平键**存于 [SyncCredentialsStore.PREFS_NAME]——
 * 全部库共用一份配置：切库后同步设置页仍回显上一个库的服务器与远端路径，同步周期也可能
 * 指向别的库（用户走查回执 + logcat 时序：切库后仍 `库身份绑定拦截：remotePath 归属另一库`）。
 * 本类给出「本次调用属于哪个库的配置记录」这一个问题的**唯一判据**，供设置页（读写表单）
 * 与同步周期（构建 Provider / 解析远端路径）共同使用。
 *
 * ## 与同步层既有 `vaultScope` 的区别（勿混用）
 *
 * `SyncCycleSetup` / `SyncCache` 的 `vaultScope` 是**根分组 UUID hex**——它只存在于
 * 「库已解锁且内存树在场」时，用于缓存 / 基线 / 防回滚的物理隔离。本命名空间标识取的是
 * **库文件登记身份**（与会话 / 库列表登记同源），因此在**未解锁**的路径上也可解析
 * （云端打开导入须在解锁前落凭据、库选择器须在解锁前预填）；
 * 两套键互不替代：前者隔离同步中间产物，后者隔离用户的同步配置。
 *
 * ## 取值口径（依次回退）与归一化
 *
 * ① 会话路径标识 [sessionPathIdentifier]（SAF 为 `content://`，本地为绝对路径）；
 * ② 会话本地文件绝对路径 [sessionFilePath]；
 * ③ 持久化的活动库 ID [persistedActiveId]（= 库列表页「活动项」，见
 * `ActiveDatabaseIdStore`：外部登记库为路径 / `content://`，**沙盒内扫描条目为裸文件名**）。
 *
 * 裸文件名（③ 的沙盒形态）与 ①/② 的绝对路径指**同一物理文件**，故一律归一为
 * `filesDir/<名>` 的绝对路径——否则同一库在「会话外」与「会话内」会落到两个命名空间，
 * 表现为「解锁后刚配好的同步又读不到」。
 *
 * 三者皆空（无会话且库列表无活动项）时返回 null：调用方退回**旧全局键**
 * （存量兼容与一次性迁移的来源，见 `SyncCredentialKeys.adoptLegacyInto`）。
 */
internal class ActiveVaultSyncNamespace(
    private val filesDir: () -> File?,
    private val sessionPathIdentifier: () -> String?,
    private val sessionFilePath: () -> String?,
    private val persistedActiveId: () -> String?
) {

    /** 当前活动库的命名空间标识；无活动库返回 null（= 旧全局命名空间）。 */
    fun currentId(): String? =
        normalize(sessionPathIdentifier() ?: sessionFilePath() ?: persistedActiveId())

    /**
     * 显式给定库 ID 时的同一归一化口径。
     *
     * 用于**无会话**的写路径：云端打开导入按即将落盘的本地文件路径落凭据
     * （该路径随后即成为库列表登记 ID，故解锁后读得到的仍是同一个命名空间）。
     */
    fun idFor(rawId: String?): String? = normalize(rawId)

    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // SAF 通道：`content://` URI 本身即是登记 ID，与会话路径标识逐字相同
        if (raw.startsWith("content://")) return raw
        if (File(raw).isAbsolute) return raw
        // 裸文件名（沙盒内扫描条目）→ 沙盒内绝对路径；filesDir 不可用（单测替身）时原样返回
        val dir = filesDir() ?: return raw
        return File(dir, raw).absolutePath
    }
}
