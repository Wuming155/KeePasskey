package com.keepasskey.app.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 首载骨架占位（`ISSUE-P3-360` AC⑤）：库列表与条目详情在解密 / 投影完成前渲染静态占位块，
 * 取列表内容层同族的 **MotionScheme 淡入**（`defaultEffectsSpec`，与 `animateItem` 同源动效族），
 * 替代「硬切进度圈 / 空态闪现」。
 *
 * 设计取舍：不做逐块脉冲 shimmer（不引入第三方 shimmer 库；静态占位块 + 整体一次淡入即可），
 * 形状与真实行布局同构（圆形图标占位 + 文本条 / 卡片块），降低内容落位时的视觉跳变。
 */

/** 单个占位块（灰阶取 surface 层级色，随主题自动适配深浅色）。 */
@Composable
internal fun SkeletonBlock(
    width: Dp,
    height: Dp,
    shape: Shape = RoundedCornerShape(6.dp),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}

/** 骨架整体淡入（AC⑤「取 MotionScheme 淡入」）：首帧 alpha=0 → 1，规格与列表动效同族。 */
@Composable
private fun Modifier.appeared(): Modifier {
    // Animatable 而非 animateFloatAsState：后者首帧直接取 targetValue（无 0→1 过程）
    val alpha = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(Unit) { alpha.animateTo(1f, spec) }
    return this.alpha(alpha.value)
}

/** 库列表首载骨架（一行 = 图标占位 + 标题 / 副行文本条）。 */
@Composable
fun VaultListLoadingSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .appeared()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(width = 148.dp, height = 14.dp)
            SkeletonBlock(width = 96.dp, height = 10.dp)
        }
    }
}

/**
 * 条目详情首载骨架（Hero 图标 + 标题条 + 两张字段卡）。
 * 由详情页 `isLoading` 分支调用（读屏加载语义的 contentDescription 挂在入参 modifier 上）。
 */
@Composable
fun EntryDetailLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.appeared().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(width = 180.dp, height = 16.dp)
                SkeletonBlock(width = 120.dp, height = 12.dp)
            }
        }
        SkeletonBlock(width = 300.dp, height = 96.dp, shape = RoundedCornerShape(14.dp))
        SkeletonBlock(width = 300.dp, height = 72.dp, shape = RoundedCornerShape(14.dp))
    }
}

/**
 * 库列表首载骨架的 LazyListScope 段（`items` 只能声明在 Lazy 作用域内）。
 * 稳定 key 保证加载→内容切换时不复用占位节点。
 */
fun LazyListScope.vaultLoadingSkeletonItems(rowCount: Int = 6) {
    items(count = rowCount, key = { "loading_skeleton_$it" }) {
        VaultListLoadingSkeleton()
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "加载骨架 - 列表行", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "加载骨架 - 列表行（深色）", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListLoadingSkeletonPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Surface { VaultListLoadingSkeleton() }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "加载骨架 - 详情", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "加载骨架 - 详情（深色）", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun EntryDetailLoadingSkeletonPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Surface { EntryDetailLoadingSkeleton() }
    }
}
