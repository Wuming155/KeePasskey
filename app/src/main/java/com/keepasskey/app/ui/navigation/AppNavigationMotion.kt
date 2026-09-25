package com.keepasskey.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * KeePasskey 全局导航与转场动效规范（`ISSUE-P3-261` 定标版；`ISSUE-P3-323` 降档复定标；前身 `ISSUE-P3-222`）。
 *
 * ## 定标口径（两栏基准，逐项裁决见 `docs/architecture/产品裁决登记.md` `PD-26` ~ `PD-28`、`PD-44`）
 *
 * 基准分两栏——**设计规范**（滑动 300ms / `cubic-bezier(0.4, 0, 0.2, 1)`；出向淡出 90ms、
 * 入向淡入 210ms 且延迟 90ms；全屏表面 Exit Scale 90% /
 * Enter Scale 110% / 插值器 `(.1, .1, 0, 1)`）与 **AndroidX 实现**
 * （`motionDurationLong1` = 450ms、emphasized 缓动、`DEFAULT_START_SCALE = 0.92f`）。本类的取舍：
 *
 * - **空间段（位移 / 缩放）取「对齐实现」**：走 [MotionScheme] 的 spring，与主题声明的
 *   `MotionScheme.expressive()` 同源，消除「底栏指示器在弹、页面在匀速滑」的同屏两套语言；
 *   官方明确 spring 在被手势打断时保证**速度连续**，而 tween 从零重启。
 *   参考实现的 450ms `motionDurationLong1` 与 legacy 曲线**不采用**（那是实现值，非规范值）。
 *   **`ISSUE-P3-323` 降档复定标**：整屏滑动取官方三档中的 **slow** 档（`slowSpatialSpec`，
 *   200 / 0.8——官方语义即「页面级、更从容」，expressive default 档的 380 用在每一次全页导航
 *   感知过快），视差位移取 **default** 档（`defaultSpatialSpec`，380 / 0.8，替换 800 / 0.6 的
 *   fast 档——小位移走最快且欠阻尼的 spring 会过冲「弹」一下）。
 * - **效果段（透明度）**：下钻 / 返回（共享轴）的淡化是**顺序**的——出向 90ms 内完成、
 *   入向延迟 90ms 后 210ms 完成（总长 300ms），阈值处两屏皆不可见；顺序淡化必须由确定时长表达，
 *   故此处刻意**不用** spring。**`ISSUE-P3-323` 复定标**：顶层 Tab / 解锁页**有意偏离**官方
 *   fade through 的顺序淡化，改为**双向同时交叉淡化**（[TOP_LEVEL_FADE_MS]）——顺序淡化的
 *   空窗被用户实证反馈为「一闪而过」，同时交叉与 NavHost 默认观感一致且无空窗（`PD-44`）。
 *
 * ## 四类转场
 *
 * 1. **下钻前进 / 返回（共享轴 X）**：进入页整屏滑动，退出页按 [PARALLAX_DIVISOR] 视差位移；
 *    前进与返回**逐项镜像**（同一比例、同一时长），不再出现「前进只有内容盖在底板上、
 *    返回才滑满屏」的两副面孔。
 * 2. **顶层同级 Tab 与解锁过渡（交叉淡化 + 涨入）**：不产生横向位移，出入口**同时**淡化
 *    （无空窗），入口自 [FADE_THROUGH_START_SCALE] 涨上来，保持「同级不改变空间层级」的语义。
 * 3. **预测性返回（全屏表面）**：手势驱动段按官方全屏表面规格——退出页缩至 [PREDICTIVE_EXIT_SCALE]、
 *    进入页自 [PREDICTIVE_ENTER_SCALE] 收进来，两者按 [FADE_THROUGH_THRESHOLD_PERCENT] 顺序淡化、
 *    共用系统 UI 插值器 [SYSTEM_UI_EASING]；该段**不含**满屏滑动。
 * 4. **内容层**（列表增删 / 卡片展开等）：各组件直接读 `MaterialTheme.motionScheme` 的 spec，
 *    不经过本类；档位按站点选择（列表 default 档、FAB fast 档，`PD-44`）。
 *
 * `@Immutable` + 构造期一次性算出全部转场 lambda：调用方（`KeePasskeyApp`）以
 * `remember(motionScheme) { AppNavigationMotion.from(motionScheme) }` 取得稳定实例，
 * 使 `NavHost` 的 `remember(route, startDestination, builder)` 不因实例身份漂移而整图重建。
 */
@Immutable
class AppNavigationMotion private constructor(
    /** 整屏滑动的空间动画（位移大 → `slowSpatialSpec`，页面级从容档，`ISSUE-P3-323`） */
    private val fullSlideSpec: FiniteAnimationSpec<IntOffset>,
    /** 视差位移的空间动画（位移小 → `defaultSpatialSpec`，去 fast 档欠阻尼过冲，`ISSUE-P3-323`） */
    private val parallaxSpec: FiniteAnimationSpec<IntOffset>
) {

    /** 全局默认：下钻前进进入（自右整屏滑入 + 延迟淡入） */
    val defaultEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = fullSlideSpec
        ) + fadeIn(animationSpec = fadeInSpec())
    }

    /** 全局默认：下钻前进离开（向左视差位移 + 快淡出，与返回方向镜像） */
    val defaultExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            targetOffset = { fullWidth -> -fullWidth / PARALLAX_DIVISOR },
            animationSpec = parallaxSpec
        ) + fadeOut(animationSpec = fadeOutSpec())
    }

    /** 全局默认：返回上一级时上一页恢复（自左视差还原 + 延迟淡入） */
    val defaultPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            initialOffset = { fullWidth -> -fullWidth / PARALLAX_DIVISOR },
            animationSpec = fullSlideSpec
        ) + fadeIn(animationSpec = fadeInSpec())
    }

    /** 全局默认：返回上一级时当前页退出（向右视差位移 + 快淡出；与前进方向镜像） */
    val defaultPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            targetOffset = { fullWidth -> fullWidth / PARALLAX_DIVISOR },
            animationSpec = parallaxSpec
        ) + fadeOut(animationSpec = fadeOutSpec())
    }

    /**
     * 预测性返回：进入页按**全屏表面**规格自 [PREDICTIVE_ENTER_SCALE] 收进来 + 阈值后淡入。
     *
     * 与共享轴段的分工：手势驱动段走「缩放 + 顺序淡化」，不叠加满屏滑动
     * （滑动与缩放并发会把位移量与缩放量混成一团，读不出层级）。
     */
    val predictivePopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.(swipeEdge: Int) -> EnterTransition =
        { _ ->
            scaleIn(
                initialScale = PREDICTIVE_ENTER_SCALE,
                transformOrigin = TransformOrigin.Center,
                animationSpec = predictiveSpec()
            ) + fadeIn(animationSpec = fadeInSpec(easing = SYSTEM_UI_EASING))
        }

    /** 预测性返回：退出页按**全屏表面**规格缩至 [PREDICTIVE_EXIT_SCALE] + 阈值前淡出 */
    val predictivePopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.(swipeEdge: Int) -> ExitTransition =
        { _ ->
            scaleOut(
                targetScale = PREDICTIVE_EXIT_SCALE,
                transformOrigin = TransformOrigin.Center,
                animationSpec = predictiveSpec()
            ) + fadeOut(animationSpec = fadeOutSpec(easing = SYSTEM_UI_EASING))
        }

    /**
     * 顶层 Tab / 解锁页：交叉淡化进入（自 [FADE_THROUGH_START_SCALE] 涨上来，与退出**同时**进行）。
     *
     * `ISSUE-P3-323` / `PD-44`：有意偏离官方 fade through 的顺序淡化——顺序淡化的空窗
     * （旧屏 90ms 内瞬消、新屏延迟 90ms 才开始）被用户实证反馈为「一闪而过」；
     * 现双向同用 [TOP_LEVEL_FADE_MS] **同时**淡化（与 NavHost 默认观感同族、无空窗），
     * 保留 `0.92f` 涨入以维持「同级不改变空间层级」的语义。
     */
    val topLevelEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = topLevelFadeInSpec()) +
            scaleIn(
                initialScale = FADE_THROUGH_START_SCALE,
                transformOrigin = TransformOrigin.Center,
                animationSpec = topLevelFadeInSpec()
            )
    }

    /** 顶层 Tab / 解锁页：交叉淡化退出（仅淡化，不产生位移与缩放；与进入**同时**进行） */
    val topLevelExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = topLevelFadeOutSpec())
    }

    private fun fadeInSpec(easing: Easing = FADE_IN_EASING): FiniteAnimationSpec<Float> =
        tween(durationMillis = FADE_IN_MS, delayMillis = FADE_IN_DELAY_MS, easing = easing)

    private fun fadeOutSpec(easing: Easing = FADE_OUT_EASING): FiniteAnimationSpec<Float> =
        tween(durationMillis = FADE_OUT_MS, easing = easing)

    /** 顶层交叉淡化的入向：**无延迟**（延迟即空窗，`PD-44`），与出向同时起跑。 */
    private fun topLevelFadeInSpec(): FiniteAnimationSpec<Float> =
        tween(durationMillis = TOP_LEVEL_FADE_MS, easing = FADE_IN_EASING)

    private fun topLevelFadeOutSpec(): FiniteAnimationSpec<Float> =
        tween(durationMillis = TOP_LEVEL_FADE_MS, easing = FADE_OUT_EASING)

    private fun predictiveSpec(): FiniteAnimationSpec<Float> =
        tween(durationMillis = SHARED_AXIS_SLIDE_MS, easing = SYSTEM_UI_EASING)

    companion object {
        /**
         * 共享轴滑动的总时长（官方设计规范：shared axis 滑动 300ms）。
         *
         * 空间段已改走 spring（`ISSUE-P3-323` 后整屏滑动取 slow 档，感知时长长于本值），
         * 本值作为**淡化时间轴**的总长参与阈值推导：
         * 出向淡出与入向淡入之和恰等于它，故整段转场「淡化结束先于滑动落定」。
         */
        const val SHARED_AXIS_SLIDE_MS: Int = 300

        /**
         * 顶层 Tab / 解锁页交叉淡化的单侧时长（`ISSUE-P3-323` / `PD-44` 新立）。
         *
         * 取官方 shared axis 同档总时长 300ms（[SHARED_AXIS_SLIDE_MS] 同源），但**双向同时**
         * 淡化、无延迟——与 NavHost 默认（`tween(700)` 纯交叉淡化）观感同族而无其拖沓，
         * 依据用户实证反馈「顺序淡化的空窗一闪而过」（`PD-44` 有意偏离官方 fade through 顺序淡化）。
         */
        const val TOP_LEVEL_FADE_MS: Int = 300

        /**
         * 共享轴 / 预测性返回的进度阈值（官方原文「At the 35% mark, neither screen is showing」）。
         *
         * 规范句是「两屏皆不可见」的**约束**而非某个具体毫秒数，故本条以不变式表达：
         * `FADE_OUT_MS ≤ SHARED_AXIS_SLIDE_MS × FADE_THROUGH_THRESHOLD_PERCENT / 100`
         * （出向淡化必须在阈值前结束，否则阈值处旧屏仍半透明）**且**
         * `FADE_IN_DELAY_MS ≥ FADE_OUT_MS`（入向不早于出向结束开始）。
         * 两条不变式由 `AppNavigationMotionTest` 锁定。
         */
        const val FADE_THROUGH_THRESHOLD_PERCENT: Int = 35

        /** 出向淡化时长（官方 shared axis 规格：淡出 90ms，`cubic-bezier(0.4, 0, 1, 1)`） */
        const val FADE_OUT_MS: Int = 90

        /** 入向淡化时长（官方 shared axis 规格：淡入 210ms） */
        const val FADE_IN_MS: Int = 210

        /** 入向淡化延迟（官方 shared axis 规格：延迟 90ms，与出向首尾相接） */
        const val FADE_IN_DELAY_MS: Int = 90

        /**
         * 共享轴退出页的视差除数：位移 = 容器尺寸 ÷ 该值。
         *
         * `ISSUE-P3-261` 偏差表第 6 / 7 项：原实现只有返回方向有视差、比例 4 亦无出处。
         * 现前进与返回**共用同一比例**（具名 token，非魔数），两侧读得出「共享同一根轴」。
         */
        const val PARALLAX_DIVISOR: Int = 4

    /**
     * 同级顶层入口的起始缩放。
     *
     * `ISSUE-P3-261` AC③ 裁决：在 fade through 的 `0.92f`（AndroidX
     * `MaterialFadeThrough.DEFAULT_START_SCALE`，由小涨上来）与全屏表面的 `110% → 100%`
     * （由大收进来）之间**按槽位分工**——同级顶层页面取前者（本值），手势驱动的预测性返回
     * 进入页取后者（[PREDICTIVE_ENTER_SCALE]）。原 `0.96f` 两个来源都对不上，已删除。
     * `ISSUE-P3-323` 后顶层淡化虽改为交叉淡化（`PD-44`），本缩放语义不变、继续沿用。
     */
        const val FADE_THROUGH_START_SCALE: Float = 0.92f

        /** 预测性返回退出页终止缩放（官方全屏表面：Exit Scale `100% → 90%`） */
        const val PREDICTIVE_EXIT_SCALE: Float = 0.9f

        /** 预测性返回进入页起始缩放（官方全屏表面：Enter Scale `110% → 100%`） */
        const val PREDICTIVE_ENTER_SCALE: Float = 1.1f

        /** fade through 淡出缓动（官方 `cubic-bezier(0.4, 0, 1, 1)`） */
        val FADE_OUT_EASING: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

        /** fade through 淡入缓动（官方 `cubic-bezier(0, 0, 0.2, 1)`） */
        val FADE_IN_EASING: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

        /**
         * 预测性返回插值器（官方全屏表面：`(.1, .1, 0, 1)`，原文 "to match the interpolator
         * used for the SystemUI animations"）。
         */
        val SYSTEM_UI_EASING: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

    /**
     * 以主题的 [MotionScheme] 构造转场规范。
     *
     * 必须在**可组合**上下文取得 [MotionScheme] 后调用——`NavHost` 的 `enterTransition`
     * 等参数是普通函数类型（非 `@Composable`），lambda 内无法读 `MaterialTheme`。
     *
     * 档位选择（`ISSUE-P3-323` / `PD-44`）：整屏滑动取 **slow** 档（`slowSpatialSpec`，
     * 200 / 0.8——页面级转场要从容）；视差位移取 **default** 档（`defaultSpatialSpec`，
     * 380 / 0.8——替换 fast 档的 800 / 0.6，小位移不再欠阻尼过冲）。
     */
    fun from(scheme: MotionScheme): AppNavigationMotion = AppNavigationMotion(
        fullSlideSpec = scheme.slowSpatialSpec(),
        parallaxSpec = scheme.defaultSpatialSpec()
    )
    }
}
