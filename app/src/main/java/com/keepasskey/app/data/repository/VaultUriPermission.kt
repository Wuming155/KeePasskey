package com.keepasskey.app.data.repository

import android.content.Context

/**
 * SAF 持久化读授权的判定（`ISSUE-P3-230`）。
 *
 * ## 缺陷背景
 *
 * `VaultLifecycleCoordinator.importExternalDatabase` 对 `takePersistableUriPermission` 一直是
 * **静默 `catch`**（理由是「部分外部 Provider 不支持持久化授权，容错继续」）。临时授权在**本次会话内**
 * 有效，故打开与保存都能成功；但进程重启后，未拿到持久化授权的 uri 会失去读权限
 * ⇒ 该库**从列表里可见却永远打不开**，且应用不给任何归因提示。同日的新建路径（§233）已改为
 * 「授权失败即在建库前显式失败」，本文件收口的是**已有库**这一侧的残留面。
 *
 * ## 为什么把判据独立成文件
 *
 * 「是否缺持久化授权」有**两处**消费方（列表投影 `VaultDatabaseCatalog` 与解锁页提示
 * `UnlockViewModel`），若各写一份查询立刻会出现两种口径（本仓在 ISUUE-P2-43 的默认值问题上
 * 已踩过同型坑）。故：**判据本体是纯函数**（[lacksPersistedReadPermission]，JVM 可测），
 * 平台查询只在 [persistedReadUriStrings] 一处。
 *
 * ## 口径（AC③：不得改为硬失败）
 *
 * 「缺授权」**不是**错误：provider 不支持持久化授权时，本次会话仍必须能正常打开与保存。
 * 本判据只驱动**提示与状态展示**，绝不参与放行 / 拒绝决策。
 */

/**
 * `path` 是否**缺少**持久化读授权（纯函数）。
 *
 * - 非 `content://` 路径（应用私有目录 / 本地文件）：恒 false——它们不涉及 SAF 授权；
 * - `content://` 路径：仅当它**不在** [grantedUriStrings] 中时为 true。
 *
 * @param grantedUriStrings 当前已持久化读授权的 uri 字符串集合（来自 [persistedReadUriStrings]）
 */
internal fun lacksPersistedReadPermission(path: String, grantedUriStrings: Set<String>): Boolean =
    path.startsWith("content://") && path !in grantedUriStrings

/**
 * 当前进程可见的**已持久化读授权** uri 字符串集合；查询失败返回 `null` 表示「未知」。
 *
 * 返回 `null` 而非空集是刻意的：空集会被判为「缺授权」并触发提示，把一个**查询失败**
 * 变成对用户的**虚假告警**（本仓纪律：不得谎报风险）。调用方遇 `null` 必须**不提示**。
 */
internal fun persistedReadUriStrings(context: Context): Set<String>? = try {
    context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()
} catch (t: Throwable) {
    null
}
