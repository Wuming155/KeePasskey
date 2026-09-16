package com.keepasskey.app.apps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.autofill.AutofillPackageNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 应用选择器数据源的**设备侧**回归（TASK-139）。
 *
 * 为什么必须上设备：本功能的正确性完全取决于 Android 11+ 的**包可见性过滤**——
 * 只有清单声明了 `MAIN` + `LAUNCHER` 的 `<queries>` intent 签名，`queryIntentActivities`
 * 才会返回本机应用；宿主 JVM 里 `PackageManager` 是桩实现，无论清单对不对都只会返回空列表，
 * 因此「列表非空」这一事实**无法**由宿主单测证明。`PackageVisibilityQueriesWiringTest`
 * 只能锁住清单里的声明文本，运行期是否真的生效由本用例负责。
 *
 * 覆盖三件事：
 * 1. 声明生效：可启动应用列表非空，且从中任取一个包名能被 `getApplicationInfo` 解析
 *    （即该包对本应用**可见**，图标/名称/绑定解析路径可用）；
 * 2. 自排除：本应用自身不出现在列表里（不应允许把自己列入黑名单或绑定到条目）；
 * 3. 与黑名单校验一致：枚举出的每个包名都能被 `AutofillPackageNames.normalize` 接受
 *    ——否则选择器会列出「能选却加不进名单」的应用，属可感知的功能裂缝。
 */
@RunWith(AndroidJUnit4::class)
class InstalledAppsCatalogDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `包可见性声明生效_可启动应用列表非空且包名可解析`() {
        val apps = InstalledAppsCatalog.launchableApps(context)

        assertTrue(
            "可启动应用列表为空：清单 <queries> 的 MAIN/LAUNCHER 声明未生效，" +
                "或本机确实没有任何带桌面入口的应用（前者才是本用例要拦的回归）",
            apps.isNotEmpty()
        )

        val probe = apps.first()
        assertNotNull(
            "枚举出的包名 ${probe.packageName} 无法解析应用信息——可见性对枚举生效但对" +
                "getApplicationInfo 未生效，名称/图标/绑定解析路径会整体退化",
            InstalledAppsCatalog.lookup(context, probe.packageName)
        )
    }

    @Test
    fun `本应用自身不出现在候选列表`() {
        val apps = InstalledAppsCatalog.launchableApps(context)
        assertFalse(
            "本应用（${context.packageName}）不得出现在可屏蔽 / 可绑定的应用列表内",
            apps.any { it.packageName == context.packageName }
        )
    }

    @Test
    fun `枚举结果无重复包名且名称非空`() {
        val apps = InstalledAppsCatalog.launchableApps(context)
        assertEquals(
            "同一包名只应出现一次（多入口应用需去重）",
            apps.size,
            apps.map { it.packageName }.toSet().size
        )
        assertTrue(
            "应用名不得为空串（不可解析时应回落为包名）",
            apps.all { it.label.isNotBlank() && it.packageName.isNotBlank() }
        )
    }

    @Test
    fun `图标解码链路在真机可用`() {
        val apps = InstalledAppsCatalog.launchableApps(context)
        assertTrue(
            "全部 ${apps.size} 个应用都没能解出图标——loadIcon/toBitmap 链路在真机上失效，" +
                "选择器会整体退化成通用占位图标（功能不缺失但失真）",
            apps.any { it.icon != null }
        )
    }

    @Test
    fun `枚举路径与单包查询路径给出同一应用名`() {
        val apps = InstalledAppsCatalog.launchableApps(context)
        val probe = apps.firstOrNull { it.label != it.packageName } ?: apps.first()
        val looked = InstalledAppsCatalog.lookup(context, probe.packageName)
        assertNotNull("枚举出的包名必须能被 lookup 解析", looked)
        assertEquals(
            "两条路径的应用名不一致：列表行与回显行会显示成两个名字",
            probe.label,
            looked!!.label
        )
    }

    @Test
    fun `枚举出的包名全部能通过黑名单的严格校验`() {
        val apps = InstalledAppsCatalog.launchableApps(context)
        val rejected = apps.filter { AutofillPackageNames.normalize(it.packageName) == null }
        assertTrue(
            "下列包名可被选择器列出、却会被黑名单判为非法（选中后必然报「包名无效」）：" +
                rejected.joinToString { it.packageName },
            rejected.isEmpty()
        )
    }

    @Test
    fun `按包名筛选在当前设备数据上精确命中`() {
        val apps = InstalledAppsCatalog.launchableApps(context)
        val probe = apps.first()
        assertEquals(
            listOf(probe.packageName),
            InstalledAppsCatalog.filter(apps, probe.packageName).map { it.packageName }
        )
    }
}
