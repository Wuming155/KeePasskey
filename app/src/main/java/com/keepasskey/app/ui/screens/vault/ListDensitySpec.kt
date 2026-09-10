package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.screens.settings.ListDensity

/**
 * 列表行密度规格（ISSUE-P3-17）。
 *
 * 以纯数值（dp / sp 的数值部分）表达，使「偏好 → 行规格」映射可在 JVM 单测直接断言，
 * 不把 Compose 单位类型带进测试；UI 侧只在绘制边界转成 `Dp` / `sp`。
 * 所有数值集中在本文件，禁止在行组件里再写字面量。
 */
data class ListDensitySpec(
    /** 行内容区纵向内边距（dp）：直接决定行高 */
    val rowVerticalPaddingDp: Int,
    /** 行内容区横向内边距（dp） */
    val rowHorizontalPaddingDp: Int,
    /** 图标容器边长（dp） */
    val iconContainerSizeDp: Int,
    /** 图标绘制边长（dp，恒小于容器以留出内衬） */
    val iconContentSizeDp: Int,
    /** 主标题（条目/分组名）字号（sp） */
    val titleFontSizeSp: Int,
    /** 次级信息（用户名 / URL / 备注 / 分组路径）字号（sp） */
    val secondaryFontSizeSp: Int
)

/**
 * 密度 → 行规格的唯一映射点（三档密度只在 [specOf] 定义一次）。
 */
object ListDensityPresenter {

    /** 紧凑：同屏可见条目更多，触控目标仍不低于 34dp */
    private val COMPACT = ListDensitySpec(
        rowVerticalPaddingDp = 6,
        rowHorizontalPaddingDp = 10,
        iconContainerSizeDp = 34,
        iconContentSizeDp = 18,
        titleFontSizeSp = 14,
        secondaryFontSizeSp = 11
    )

    /** 默认：与整改前标准行版式一致（12/10 内边距、38dp 图标、15sp 标题） */
    private val NORMAL = ListDensitySpec(
        rowVerticalPaddingDp = 10,
        rowHorizontalPaddingDp = 12,
        iconContainerSizeDp = 38,
        iconContentSizeDp = 20,
        titleFontSizeSp = 15,
        secondaryFontSizeSp = 12
    )

    /** 宽松：更大字号与更大触控面积 */
    private val COMFORTABLE = ListDensitySpec(
        rowVerticalPaddingDp = 14,
        rowHorizontalPaddingDp = 16,
        iconContainerSizeDp = 44,
        iconContentSizeDp = 24,
        titleFontSizeSp = 17,
        secondaryFontSizeSp = 13
    )

    /** 密度枚举 → 行规格（穷尽 when：新增密度档必须同时补齐规格） */
    fun specOf(density: ListDensity): ListDensitySpec = when (density) {
        ListDensity.COMPACT -> COMPACT
        ListDensity.NORMAL -> NORMAL
        ListDensity.COMFORTABLE -> COMFORTABLE
    }
}
