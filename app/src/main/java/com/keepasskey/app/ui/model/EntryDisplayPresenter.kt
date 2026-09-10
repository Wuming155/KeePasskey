package com.keepasskey.app.ui.model

import com.keepasskey.core.model.KdbxEntry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * 单个条目的展示装饰（ISSUE-P3-02 / TASK-49）：
 * 自定义图标投影 + Notes/URL 字段引用展开文案。
 *
 * M1 原则：装饰只承载「图标绘制载荷」与「仅公开字段展开后的展示文本」，
 * 受保护字段在展示侧恒为掩码占位，不随本模型物化明文。
 */
data class EntryDecorations(
    val icons: Map<String, BitmapEntryIcon> = emptyMap(),
    val texts: Map<String, EntryTextDisplay> = emptyMap()
) {

    /** 条目图标；未装配时回退标准图标（不谎报自定义图标） */
    fun iconOf(entry: UiVaultEntry): BitmapEntryIcon =
        icons[entry.id] ?: EntryIcon.Default(entry.iconName)

    /** 条目 Notes/URL 展示文案；未装配（条目无引用）时回退条目原文 */
    fun textOf(entry: UiVaultEntry): EntryTextDisplay =
        texts[entry.id] ?: EntryTextDisplay(notes = entry.notes, url = entry.url)

    companion object {
        /** 空装饰：所有条目回退标准图标与原文 */
        val EMPTY = EntryDecorations()
    }
}

/**
 * 列表页与详情页共用的展示装配器（ISSUE-P3-02）。
 *
 * 装配动作含图标池读取与 PNG 解码，属后台工作：调用方须在 `Dispatchers.Default`
 * 上下文执行（各 ViewModel 以 `flowOn(Dispatchers.Default)` 约束）。
 */
class EntryDisplayPresenter(
    loadIconBytes: suspend () -> Map<String, ByteArray>,
    loadEntries: suspend () -> List<KdbxEntry>
) {

    private val icons = EntryIconPresenter.production(loadIconBytes)
    private val texts = EntryReferenceDisplayResolver(loadEntries)

    /** 批量装配（列表页）：整批共用一次图标池快照与一次引用检索快照 */
    suspend fun decorate(entries: List<UiVaultEntry>): EntryDecorations {
        if (entries.isEmpty()) return EntryDecorations.EMPTY
        return EntryDecorations(
            icons = icons.present(entries),
            texts = texts.present(entries)
        )
    }

    /** 单条装配（详情页）：图标与展示文案（Notes/URL 引用展开，受保护字段掩码） */
    suspend fun decorate(entry: UiVaultEntry): EntryDecorations = EntryDecorations(
        icons = mapOf(entry.id to icons.present(entry.customIconId, entry.iconName)),
        texts = mapOf(entry.id to texts.display(entry.notes, entry.url))
    )
}

/** 展示装配（图标 PNG 解码 / 字段引用展开）所用的调度器限定符 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EntryDisplayDispatcher

/**
 * 展示装配调度器绑定：解码与引用展开为 CPU 工作，生产固定 [Dispatchers.Default]
 * （禁止在 Main 上解码 PNG）。
 *
 * 之所以以限定符注入而非在各 ViewModel 内硬编码 `Dispatchers.Default`：
 * 单测可替换为测试调度器，使装饰装配完全落在虚拟时间轴上（断言确定性，无跨线程竞态）。
 * 本模块随展示层文件声明，避免改动 `app/di` 目录（并行工作组文件范围约束）。
 */
@Module
@InstallIn(SingletonComponent::class)
object EntryDisplayModule {

    @Provides
    @EntryDisplayDispatcher
    fun provideEntryDisplayDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
