package com.keepasskey.app.ui.components

import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 底部导航栏项定义
 */
enum class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    VAULT(
        route = Screen.VaultList.route,
        label = "密码库",
        selectedIcon = Icons.Filled.Key,
        unselectedIcon = Icons.Outlined.Key
    ),
    SETTINGS(
        route = Screen.Settings.route,
        label = "设置",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    );

    companion object {
        val routes = entries.map { it.route }
        fun isTopLevelRoute(route: String?): Boolean = route in routes
    }
}

/**
 * KeePasskey 统一底部导航栏组件
 * 采用 Material 3 Expressive 规范，支持主题自适应及状态高亮
 */
@Composable
fun AppBottomBar(
    currentRoute: String?,
    onNavigateToRoute: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant

    NavigationBar(
        modifier = modifier
            .drawBehind {
                // 顶部 1dp 细致分割线，与页面内容优雅隔离
                drawLine(
                    color = outlineVariantColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp
    ) {
        BottomNavItem.entries.forEach { item ->
            val isSelected = currentRoute == item.route

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigateToRoute(item.route) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Preview(name = "AppBottomBar - Light", showBackground = true)
@Preview(name = "AppBottomBar - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun AppBottomBarPreview() {
    KeePasskeyTheme {
        AppBottomBar(
            currentRoute = Screen.VaultList.route,
            onNavigateToRoute = {}
        )
    }
}
