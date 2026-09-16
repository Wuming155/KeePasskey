package com.keepasskey.app.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用选择器数据源的纯逻辑回归（TASK-139）。
 *
 * 覆盖范围与**不覆盖**范围（如实声明）：
 * - 覆盖：列表排序、按应用名/包名筛选、已选集合的语义输入（这些是唯一可做宿主 JVM 判定的部分）；
 * - 不覆盖：`launchableApps` 的 `PackageManager` 枚举与包可见性过滤——它只能在设备侧验证，
 *   其**清单前置条件**由 `PackageVisibilityQueriesWiringTest` 静态锁定（漏声明即列表恒空）。
 *
 * 图标载荷一律为 null：本用例只关心筛选/排序语义，图标解码与绘制不在宿主 JVM 判定范围。
 */
class InstalledAppsCatalogTest {

    private fun app(pkg: String, label: String) = InstalledAppOption(packageName = pkg, label = label)

    private val sample = listOf(
        app("com.example.zeta", "Zeta 银行"),
        app("com.example.alpha", "alpha 钱包"),
        app("org.example.beta", "Beta")
    )

    @Test
    fun `空查询按原顺序返回全部条目`() {
        assertEquals(sample, InstalledAppsCatalog.filter(sample, ""))
        assertEquals(sample, InstalledAppsCatalog.filter(sample, "   "))
    }

    @Test
    fun `按应用名筛选且不区分大小写`() {
        val hit = InstalledAppsCatalog.filter(sample, "BETA")
        assertEquals(listOf("org.example.beta"), hit.map { it.packageName })

        val lower = InstalledAppsCatalog.filter(sample, "beta")
        assertEquals(listOf("org.example.beta"), lower.map { it.packageName })
    }

    @Test
    fun `按包名子串筛选`() {
        val hit = InstalledAppsCatalog.filter(sample, "example.zeta")
        assertEquals(listOf("com.example.zeta"), hit.map { it.packageName })
    }

    @Test
    fun `查询前后空白被忽略且无匹配时返回空`() {
        assertEquals(
            listOf("com.example.alpha"),
            InstalledAppsCatalog.filter(sample, "  alpha  ").map { it.packageName }
        )
        assertTrue(InstalledAppsCatalog.filter(sample, "com.example.missing").isEmpty())
    }

    @Test
    fun `排序按应用名不区分大小写且不改动入参`() {
        val original = sample.toList()
        val ordered = InstalledAppsCatalog.orderApps(sample)
        assertEquals(
            listOf("alpha 钱包", "Beta", "Zeta 银行"),
            ordered.map { it.label }
        )
        assertEquals("排序不得改动入参列表", original, sample)
    }

    @Test
    fun `同名应用按包名兜底排序保证顺序稳定`() {
        val sameName = listOf(
            app("com.example.two", "同名应用"),
            app("com.example.one", "同名应用")
        )
        assertEquals(
            listOf("com.example.one", "com.example.two"),
            InstalledAppsCatalog.orderApps(sameName).map { it.packageName }
        )
    }
}
