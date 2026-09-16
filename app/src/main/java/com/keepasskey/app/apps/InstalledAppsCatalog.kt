package com.keepasskey.app.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * 已安装应用目录项（应用选择器的一行：应用名 + 包名 + 图标）。
 *
 * [icon] 允许为 null（图标不可读 / 未安装），UI 侧回退为通用占位图标，
 * **不伪造**任何应用图标或名称。
 */
data class InstalledAppOption(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap? = null
)

/**
 * 已安装应用目录（TASK-139：应用填充相关的包名选取统一入口）。
 *
 * 供两类界面共用：① 自动填充 / 保存侧黑名单；② 条目「关联应用」绑定（写入 `android://<包名>`）。
 * 二者此前都要求用户**手工键入包名**——拼错即成「假屏蔽 / 假绑定」且无法察觉，
 * 本目录把「应用名 + 图标 + 包名」一并取自系统，用户按名称指认即可。
 *
 * 可见性口径（**不使用 `QUERY_ALL_PACKAGES`**，见清单 `<queries>` 注释）：
 * 仅枚举带 `MAIN` / `LAUNCHER` 入口的应用，即用户能在桌面看到、也确实可能承载登录表单的那些；
 * 无桌面入口的应用（系统组件 / 无界面 SDK 集成方）**不在列表内**，此类包名仍可经手工输入兜底。
 *
 * 线程契约：本类全部方法均可能触发 Binder IPC 与图标解码，**必须**在 `Dispatchers.IO`
 * （或等价后台调度）调用，不得在组合期或主线程裸调。
 */
object InstalledAppsCatalog {

    /** 图标解码目标边长（px）：选择器按 36~40dp 绘制，128px 已足以覆盖高密度屏 */
    private const val ICON_TARGET_PX = 128

    /**
     * 枚举可启动应用（按应用名升序；同一包名只保留一条；剔除本应用自身）。
     *
     * 包可见性受限时 `queryIntentActivities` 返回的即系统允许可见的子集——
     * 本方法如实返回该子集，不补齐、不猜测。
     */
    fun launchableApps(context: Context): List<InstalledAppOption> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (_: Throwable) {
            // 查询受限 / 系统异常：返回空列表，由调用方如实提示「未读取到应用」而不谎报
            return emptyList()
        }

        val byPackage = LinkedHashMap<String, InstalledAppOption>()
        for (info in resolved) {
            val packageName = info.activityInfo?.packageName ?: continue
            // 本应用自身不允许被屏蔽 / 被绑定（填入自身包名只会产生无意义条目）
            if (packageName == context.packageName) continue
            if (byPackage.containsKey(packageName)) continue
            byPackage[packageName] = InstalledAppOption(
                packageName = packageName,
                label = safeLabel(pm, info, packageName),
                icon = safeIcon(info, pm)
            )
        }
        return orderApps(byPackage.values.toList())
    }

    /**
     * 按应用名（不区分大小写）升序排列，同名以包名兜底，保证列表顺序稳定。
     *
     * 纯函数（无 Android 依赖），供宿主 JVM 单测覆盖；返回新列表，不改动入参。
     */
    fun orderApps(options: List<InstalledAppOption>): List<InstalledAppOption> =
        options.sortedWith(APP_ORDER)

    /**
     * 按应用名或包名筛选（大小写不敏感的子串匹配；空查询返回原列表顺序）。
     *
     * 纯函数，无 Android 依赖，供宿主 JVM 单测覆盖。
     */
    fun filter(options: List<InstalledAppOption>, query: String): List<InstalledAppOption> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return options
        return options.filter {
            it.label.contains(keyword, ignoreCase = true) ||
                it.packageName.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * 解析单个包名的显示信息（列表行回显用）。
     *
     * 不可解析（未安装 / 受包可见性限制 / 读取异常）时返回 null——调用方**如实回落为包名文本**，
     * 不伪造应用名与图标。
     */
    fun lookup(context: Context, packageName: String): InstalledAppOption? {
        if (packageName.isBlank()) return null
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() } ?: packageName
            InstalledAppOption(
                packageName = packageName,
                label = label,
                icon = try {
                    pm.getApplicationIcon(info).toBitmap(ICON_TARGET_PX, ICON_TARGET_PX).asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 应用名（不区分大小写）升序，同名校验包名，保证顺序稳定 */
    private val APP_ORDER: Comparator<InstalledAppOption> =
        compareBy<InstalledAppOption, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy { it.packageName }

    private fun safeLabel(pm: PackageManager, info: ResolveInfo, fallback: String): String =
        try {
            info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Throwable) {
            fallback
        }

    private fun safeIcon(info: ResolveInfo, pm: PackageManager): ImageBitmap? =
        try {
            info.loadIcon(pm)?.toBitmap(ICON_TARGET_PX, ICON_TARGET_PX)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
}
