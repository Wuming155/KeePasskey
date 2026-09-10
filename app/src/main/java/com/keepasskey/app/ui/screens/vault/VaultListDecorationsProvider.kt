package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.EntryIconPresenter
import com.keepasskey.app.ui.model.EntryReferenceDisplayResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 密码库列表页的**展示装饰装配**（ISSUE-P3-29：自 `VaultListViewModel.kt` 拆出）。
 *
 * ISSUE-P3-02（TASK-49）/ ISSUE-P3-22：条目与分组**共用同一个** [EntryIconPresenter]——
 * 因而共用同一图标池快照、同一解码缓存（IconBitmapCache）与同一失败登记表，
 * 分组与条目引用同一自定义图标时只解码一次。
 *
 * 注：`EntryDisplayPresenter` 内部私有持有自己的投影器，无法让分组与条目共用缓存，
 * 故此处按「一个投影器 + 一个引用文案解析器」自行装配 [EntryDecorations]（公共数据类）。
 * 原实现逐字迁移，行为零变更。
 */
internal class VaultListDecorationsProvider(
    private val vaultRepository: VaultRepository,
    private val displayDispatcher: CoroutineDispatcher
) {

    private val iconPresenter = EntryIconPresenter.production { vaultRepository.getCustomIconBytes() }

    // 注：EntryReferenceDisplayResolver 不是 fun interface，且 protectedPlaceholder 为末位形参，
    // 故必须用具名参数装配（尾随 lambda 会被绑定到 protectedPlaceholder 上）
    private val entryTexts = EntryReferenceDisplayResolver(
        loadEntries = { vaultRepository.getKdbxEntries() }
    )

    /** 条目图标投影 + 引用展开文案 */
    val entryDecorations: Flow<EntryDecorations> = vaultRepository.getEntries()
        .map { entries ->
            EntryDecorations(
                icons = iconPresenter.present(entries),
                texts = entryTexts.present(entries)
            )
        }
        .flowOn(displayDispatcher)

    /** ISSUE-P3-22：分组图标投影（同一 presenter → 同一解码缓存，不重复解码） */
    val groupIcons: Flow<Map<String, BitmapEntryIcon>> = vaultRepository.getGroups()
        .map { groups -> iconPresenter.presentGroups(groups) }
        .flowOn(displayDispatcher)
}
