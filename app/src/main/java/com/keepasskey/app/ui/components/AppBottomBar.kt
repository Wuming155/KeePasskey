package com.keepasskey.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 底部导航栏项定义 (支持动态开关验证码与生成器入口)
 */
enum class BottomNavItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    VAULT(
        route = Screen.VaultList.route,
        labelRes = R.string.nav_vault,
        selectedIcon = Icons.Filled.Key,
        unselectedIcon = Icons.Outlined.Key
    ),
    AUTHENTICATOR(
        route = Screen.Authenticator.route,
        labelRes = R.string.nav_authenticator,
        selectedIcon = Icons.Filled.Security,
        unselectedIcon = Icons.Outlined.Security
    ),
    GENERATOR(
        route = Screen.Generator.route,
        labelRes = R.string.nav_generator,
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome
    ),
    SETTINGS(
        route = Screen.Settings.route,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    );

    companion object {
        val routes = entries.map { it.route }
        fun isTopLevelRoute(route: String?): Boolean = route in routes

        fun getVisibleItems(showAuthenticator: Boolean, showGenerator: Boolean): List<BottomNavItem> {
            return entries.filter { item ->
                when (item) {
                    AUTHENTICATOR -> showAuthenticator
                    GENERATOR -> showGenerator
                    else -> true
                }
            }
        }
    }
}

/**
 * KeePasskey 统一底部导航栏组件 (手机视口)
 */
@Composable
fun AppBottomBar(
    currentRoute: String?,
    visibleItems: List<BottomNavItem>,
    onNavigateToRoute: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant

    NavigationBar(
        modifier = modifier
            .drawBehind {
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
        visibleItems.forEach { item ->
            val isSelected = currentRoute == item.route
            val itemLabel = stringResource(item.labelRes)

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigateToRoute(item.route) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = itemLabel
                    )
                },
                label = {
                    Text(
                        text = itemLabel,
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

/**
 * 自适应宽屏/平板侧边导航栏 (NavigationRail)
 */
@Composable
fun AppNavigationRail(
    currentRoute: String?,
    visibleItems: List<BottomNavItem>,
    onNavigateToRoute: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant

    NavigationRail(
        modifier = modifier
            .fillMaxHeight()
            .drawBehind {
                drawLine(
                    color = outlineVariantColor,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        visibleItems.forEach { item ->
            val isSelected = currentRoute == item.route
            val itemLabel = stringResource(item.labelRes)

            NavigationRailItem(
                selected = isSelected,
                onClick = { onNavigateToRoute(item.route) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = itemLabel
                    )
                },
                label = {
                    Text(
                        text = itemLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                alwaysShowLabel = true,
                modifier = Modifier.padding(vertical = 4.dp),
                colors = NavigationRailItemDefaults.colors(
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
            visibleItems = BottomNavItem.entries,
            onNavigateToRoute = {}
        )
    }
}
