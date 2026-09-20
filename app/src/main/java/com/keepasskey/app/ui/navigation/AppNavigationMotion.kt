package com.keepasskey.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation.NavBackStackEntry

/**
 * KeePasskey 全局导航与转场动效规范（ISSUE-P3-222：基于 Material 3 Motion System 与 Predictive Back）。
 *
 * 1. 下钻/层级导航（Shared Axis X）：
 *    - 前进（Push）：目标页从右侧滑入并淡入，当前页向左微移视差并淡出；
 *    - 返回（Pop / 预测性返回）：当前页向右滑出并缩放淡出（联动系统手势进度驱动），上一页从左侧视差恢复并淡入。
 * 2. 顶层同级 Tab 切换与解锁过渡（Fade Through）：
 *    - 避免同级导航发生横向位移造成的空间层级混淆，采用 Material 3 标准贯穿淡入淡出。
 */
object AppNavigationMotion {
    const val ENTER_DURATION_MS: Int = 300
    const val EXIT_DURATION_MS: Int = 250
    const val FADE_THROUGH_ENTER_MS: Int = 220
    const val FADE_THROUGH_EXIT_MS: Int = 150
    private const val PARALLAX_FACTOR: Int = 4

    /** 全局默认：下钻前进进入（从右侧滑入 + 淡入） */
    val defaultEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(ENTER_DURATION_MS, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(ENTER_DURATION_MS, easing = FastOutSlowInEasing))
    }

    /** 全局默认：下钻前进离开（向左侧视差微移 + 淡出） */
    val defaultExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            targetOffset = { fullWidth -> -fullWidth / PARALLAX_FACTOR },
            animationSpec = tween(EXIT_DURATION_MS, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(EXIT_DURATION_MS, easing = FastOutSlowInEasing))
    }

    /** 全局默认：返回上一级时上一页恢复（从左侧视差还原 + 淡入） */
    val defaultPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            initialOffset = { fullWidth -> -fullWidth / PARALLAX_FACTOR },
            animationSpec = tween(ENTER_DURATION_MS, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(ENTER_DURATION_MS, easing = FastOutSlowInEasing))
    }

    /** 全局默认：返回上一级时当前页退出（向右滑出 + 缩放至 0.9f + 淡出，支持预测性返回进度驱动） */
    val defaultPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = tween(EXIT_DURATION_MS, easing = FastOutSlowInEasing)
        ) + scaleOut(
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(0.5f, 0.5f),
            animationSpec = tween(EXIT_DURATION_MS, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(EXIT_DURATION_MS, easing = FastOutSlowInEasing))
    }

    /** 顶层 Tab / 解锁页：Fade Through 进入 */
    val topLevelEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = tween(FADE_THROUGH_ENTER_MS, easing = FastOutSlowInEasing)) +
            scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(FADE_THROUGH_ENTER_MS, easing = FastOutSlowInEasing)
            )
    }

    /** 顶层 Tab / 解锁页：Fade Through 退出 */
    val topLevelExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = tween(FADE_THROUGH_EXIT_MS, easing = FastOutSlowInEasing))
    }
}
