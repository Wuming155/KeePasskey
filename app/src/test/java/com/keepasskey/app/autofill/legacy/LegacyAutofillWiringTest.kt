package com.keepasskey.app.autofill.legacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 旧版无障碍自动填充通道的接线守卫（ISSUE-P3-324 AC②③，静态源码比对，
 * 体例沿用 `AutofillChannelSwitchWiringTest`）。
 *
 * 锁定的失效形态：
 * 1. **开关成摆设**（ISSUE-P2-228 整改前三条通道开关的原形态）：服务必须真求值
 *    `isAutofillLegacyAccessibilityEnabled()`，持久化键必须读写成对，UI 值必须取自
 *    持久化快照 `extState`；
 * 2. **默认值漂移**：数据类默认 = 单键读取缺省 = **false**（本通道出厂关闭，与三条
 *    默认开启的通道开关刻意不同）；
 * 3. **安全闸门缺失**：自我排除（ISSUE-P2-226 同口径）、完整性闸门（ISSUE-P2-08）、
 *    黑名单（TASK-44）、锁定态放弃（ISSUE-P3-201 同口径）缺一不可；
 * 4. **组件声明缺失**：Manifest 服务声明（权限保护）与配置 XML（可读窗口内容）必须存在。
 */
class LegacyAutofillWiringTest {

    private val serviceSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyAutofillAccessibilityService.kt")

    private val pickerSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyFillPickerActivity.kt")

    private val coordinatorSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyAutofillCoordinator.kt")

    private val manifestSource: String
        get() = readSource("app/src/main/AndroidManifest.xml")

    private val configSource: String
        get() = readSource("app/src/main/res/xml/legacy_autofill_accessibility_service.xml")

    private val storeSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt")

    private val extendedSettingsSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt")

    private val projectionSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt")

    private val controllerSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt"
        )

    private val componentsSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsComponents.kt"
        )

    // ========== AC②：持久化闭环，默认关闭 ==========

    @Test
    fun `开关键在读侧与写侧同时存在`() {
        assertTrue("缺读侧", storeSource.contains("K_AUTOFILL_LEGACY_ACCESSIBILITY_ENABLED, defaults."))
        assertTrue("缺写侧", storeSource.contains(".putBoolean(K_AUTOFILL_LEGACY_ACCESSIBILITY_ENABLED"))
    }

    @Test
    fun `单键读取缺省与数据类默认同为 false`() {
        assertTrue(
            "单键缺省必须显式为 false（不得沿用 CHANNEL_SWITCH_DEFAULT）",
            storeSource.contains("getBoolean(K_AUTOFILL_LEGACY_ACCESSIBILITY_ENABLED, false)")
        )
        assertTrue(
            "数据类默认必须为 false（出厂关闭）",
            extendedSettingsSource.contains("autofillLegacyAccessibilityEnabled: Boolean = false")
        )
    }

    @Test
    fun `UI 开关值取自持久化快照且服务真求值`() {
        assertTrue(
            "投影未经持久化快照",
            projectionSource.contains(
                "autofillLegacyAccessibilityEnabled = extState.autofillLegacyAccessibilityEnabled"
            )
        )
        assertTrue(
            "偏好控制器缺 setter（写入面）",
            controllerSource.contains("fun setAutofillLegacyAccessibilityEnabled")
        )
        assertTrue(
            "设置页必须消费 uiState.autofillLegacyAccessibilityEnabled",
            componentsSource.contains("uiState.autofillLegacyAccessibilityEnabled")
        )
        assertTrue(
            "服务必须真求值开关（否则关掉整条通道不生效——假开关复现）",
            serviceSource.contains("isAutofillLegacyAccessibilityEnabled()")
        )
    }

    // ========== AC③：安全闸门缺一不可 ==========

    @Test
    fun `服务侧四道闸门齐备`() {
        assertTrue(
            "自我排除（ISSUE-P2-226 同口径）：本应用窗口绝不触发",
            serviceSource.contains("AutofillAccessPolicy.isSelfApp(")
        )
        assertTrue(
            "黑名单命中不得发通知（TASK-44）",
            serviceSource.contains("autofillBlocklistStore::isBlocked")
        )
        assertTrue(
            "库锁定不得发通知也不得回填（发通知与回填两处都要复核）",
            serviceSource.split("vaultRepository.isLocked()").size - 1 >= 2
        )
        assertTrue(
            "回填必须复核对目标窗口包名的一致性（不跨窗口误填）",
            serviceSource.contains("currentPkg != fill.targetPackage")
        )
    }

    @Test
    fun `选择器侧受保护窗口与锁定复核齐备`() {
        assertTrue(
            "受保护窗口：FLAG_SECURE 防截屏录屏",
            pickerSource.contains("FLAG_SECURE")
        )
        assertTrue(
            "受保护窗口：反悬浮窗覆盖",
            pickerSource.contains("setHideOverlayWindows(true)")
        )
        assertTrue(
            "受保护窗口：遮挡触摸过滤",
            pickerSource.contains("filterTouchesWhenObscured = true")
        )
        assertTrue(
            "交付前锁定复核（ISSUE-P3-201 同口径）",
            pickerSource.split("AutofillAuthenticationPolicy.canDeliverAuthResult").size - 1 >= 2
        )
        assertTrue(
            "交付必须经协调器（不得绕开单例桥自建通道）",
            pickerSource.contains("coordinator.submit(")
        )
    }

    @Test
    fun `待决载荷的明文不得进入日志`() {
        assertFalse(
            "PendingFill 的 toString 必须脱敏（默认数据类实现会整份展开明文）",
            coordinatorSource.contains("data class PendingFill(") &&
                !coordinatorSource.contains("override fun toString()")
        )
    }

    // ========== 组件声明 ==========

    @Test
    fun `Manifest 服务与配置 XML 声明齐备`() {
        assertTrue(
            "服务必须声明 BIND_ACCESSIBILITY_SERVICE 权限保护",
            manifestSource.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")
        )
        assertTrue(
            "服务必须在 Manifest 注册",
            manifestSource.contains(".autofill.legacy.LegacyAutofillAccessibilityService")
        )
        assertTrue(
            "选择器 Activity 必须为不可导出（仅通知 PendingIntent 内部拉起）",
            Regex(
                """<activity\s+android:name="\.autofill\.legacy\.LegacyFillPickerActivity"[^>]*android:exported="false"""",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(manifestSource)
        )
        assertTrue(
            "配置 XML 必须声明可读窗口内容（否则无法识别口令框）",
            configSource.contains("android:canRetrieveWindowContent=\"true\"")
        )
        assertTrue(
            "配置 XML 必须声明窗口级事件类型",
            configSource.contains("typeWindowStateChanged|typeWindowContentChanged")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
