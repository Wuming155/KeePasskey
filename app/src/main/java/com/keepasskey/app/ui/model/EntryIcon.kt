package com.keepasskey.app.ui.model

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 条目图标投影（ISSUE-P3-02 / TASK-49，承接 TASK-15 残余）。
 *
 * KDBX 自定义图标是存于库 Meta `CustomIcons` 的 PNG 字节，条目经 `CustomIconUUID` 引用。
 * UI 层不允许在 Composable 内做 IO/解码，也不允许为每个条目重复解码同一图标：
 * 「图标 id → 已解码的不可变绘制模型 / 失败占位」的映射统一由状态层（ViewModel 经
 * [EntryIconPresenter]）产出，Composable 只做纯绘制。
 *
 * 泛型 [T] 为渲染载荷：生产为 Compose `ImageBitmap`（[BitmapEntryIcon]），
 * 单测以轻量替身验证投影判定与缓存语义，无需构造 Android 位图。
 *
 * M1 原则：本模型只承载图标字节的解码产物与图标标识，不含任何条目字段明文。
 */
sealed interface EntryIcon<out T : Any> {

    /** 未绑定自定义图标：沿用 KDBX 标准矢量图标（[iconName] 为库内标准图标 id） */
    data class Default(val iconName: String) : EntryIcon<Nothing>

    /**
     * 已绑定库内自定义 PNG 图标。
     * [bitmap] 为已解码绘制载荷；null 表示尚未解码或解码失败，渲染侧按缺图占位处理。
     */
    data class Custom<out T : Any>(val iconId: String, val bitmap: T? = null) : EntryIcon<T>

    /**
     * 绑定的图标 id 不在库内图标池中（图标已被删除、或其他端删除后引用未清理）：
     * 渲染缺图占位，不回退为标准图标，避免向用户谎报图标状态。
     */
    data object Missing : EntryIcon<Nothing>
}

/** 生产渲染载荷：Compose 位图 */
typealias BitmapEntryIcon = EntryIcon<ImageBitmap>

/**
 * 图标投影判定（纯函数、无 Android 依赖、无 IO，可 JVM 直测）。
 */
object EntryIconProjection {

    /**
     * 依据「条目绑定的自定义图标 id + 标准图标名 + 库内图标池现有 id 集合」判定渲染形态。
     * 判定不涉及解码：命中池内图标时由 [EntryIconPresenter] 后续补入已解码载荷。
     */
    fun <T : Any> of(
        customIconId: String?,
        iconName: String,
        availableIconIds: Set<String>
    ): EntryIcon<T> = when {
        customIconId == null -> EntryIcon.Default(iconName)
        customIconId !in availableIconIds -> EntryIcon.Missing
        else -> EntryIcon.Custom(customIconId)
    }
}
