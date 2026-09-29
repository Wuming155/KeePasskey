package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 候选呈现面两开关的接线守卫（ISSUE-P3-376 AC②③，体例同 `AutofillChannelSwitchWiringTest`）。
 *
 * 吸收 Monica `manual_selection_enabled` / `password_suggestion_enabled` 的两条新开关——
 * 历史教训（`ISSUE-P2-228` / `ISSUE-P3-65` 假开关）决定了判据必须指向**整条链路**：
 * 1. **读写键成对**：数据类字段、load、save、单键读取器四处齐备且缺省同值（默认 true）；
 * 2. **真实消费方**：手动选择器开关被 `buildPickerDataset` 消费；新建入口开关被
 *    选择器 `canCreateNew` 与 CM `shouldOfferPasswordCreateAction` **两处**消费（计数防只接一处）；
 * 3. **设置页链路**：UiState 字段 → 投影 → 控制器 setter → ViewModel 委托 → 路由引用 → 卡片行
 *    逐段在场。
 */
class AutofillCandidateSurfaceSwitchWiringTest {

    private fun readSource(path: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(6) {
            val candidate = dir ?: return@repeat
            val file = File(candidate, path)
            if (file.isFile) return file.readText()
            dir = candidate.parentFile
        }
        error("源码缺失：$path")
    }

    private val settingsSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt")

    private val storeSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt")

    // ===== AC② 读写键成对 + 缺省同值 =====

    @Test
    fun `两开关数据类字段默认开启且读写键四处齐备`() {
        assertTrue(
            "数据类缺省必须为 true",
            settingsSource.contains("val autofillManualPickerEnabled: Boolean = true")
        )
        assertTrue(
            settingsSource.contains("val autofillOfferCreateEntry: Boolean = true")
        )
        listOf(
            "K_AUTOFILL_MANUAL_PICKER = \"autofill_manual_picker_enabled\"",
            "K_AUTOFILL_OFFER_CREATE_ENTRY = \"autofill_offer_create_entry\""
        ).forEach { declaration ->
            assertTrue("缺键声明：$declaration", storeSource.contains(declaration))
        }
        // load（defaults 同值传导）与 save（回写）各自精确锚
        assertTrue(
            "manual picker 键缺 load 读取",
            Regex("""p\.getBoolean\(\s*K_AUTOFILL_MANUAL_PICKER""").containsMatchIn(storeSource)
        )
        assertTrue(
            "manual picker 键缺 save 回写",
            storeSource.contains(".putBoolean(K_AUTOFILL_MANUAL_PICKER")
        )
        assertTrue(
            "offer create 键缺 load 读取",
            Regex("""p\.getBoolean\(\s*K_AUTOFILL_OFFER_CREATE_ENTRY""").containsMatchIn(storeSource)
        )
        assertTrue(
            "offer create 键缺 save 回写",
            storeSource.contains(".putBoolean(K_AUTOFILL_OFFER_CREATE_ENTRY")
        )
        // 单键读取器存在且缺省 true（ISSUE-P2-43 踩坑形态：缺省漂移即假开关）
        assertTrue(
            "manual picker 单键读取器缺省必须为 true",
            Regex("""fun isAutofillManualPickerEnabled\(\): Boolean =\s*prefs\?\.getBoolean\(K_AUTOFILL_MANUAL_PICKER, true\) \?: true""")
                .containsMatchIn(storeSource)
        )
        assertTrue(
            "offer create 单键读取器缺省必须为 true",
            Regex("""fun isAutofillOfferCreateEntryEnabled\(\): Boolean =\s*prefs\?\.getBoolean\(K_AUTOFILL_OFFER_CREATE_ENTRY, true\) \?: true""")
                .containsMatchIn(storeSource)
        )
    }

    // ===== AC② 真实消费方（计数防只接一处） =====

    @Test
    fun `手动选择器开关被填充响应的装配点消费`() {
        val builders = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt")
        assertEquals(
            "isAutofillManualPickerEnabled 消费点应恰为 1 处（定义在 store，此处为唯一填充侧消费）",
            1,
            Regex("""isAutofillManualPickerEnabled\(\)""").findAll(builders).count()
        )
        assertTrue(
            "buildPickerDataset 必须以该开关早退",
            builders.contains("if (!settingsStore.isAutofillManualPickerEnabled()) return")
        )
        val storeCount = Regex("""fun isAutofillManualPickerEnabled""").findAll(storeSource).count()
        assertEquals("读取器定义应恰为 1 处", 1, storeCount)
    }

    @Test
    fun `新建入口开关被选择器与CM装配两处消费`() {
        // ISSUE-P3-390 拆分后：选择器 canCreateNew 消费点在内容树文件（Activity 只透传 onCreateNew）
        val picker = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillPickerLocalizedContent.kt")
        val assembler = readSource("app/src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt")

        assertTrue(
            "选择器 canCreateNew 必须合取该开关",
            picker.contains("settingsStore.isAutofillOfferCreateEntryEnabled()")
        )
        assertTrue(
            "CM 第五门控必须读该开关",
            assembler.contains("offerCreateEntryEnabled = settingsStore.isAutofillOfferCreateEntryEnabled()")
        )
        // 消费点计数 = 2（选择器内容树 + CM），防「只接一处」
        val pickerCount = Regex("""isAutofillOfferCreateEntryEnabled\(\)""").findAll(picker).count()
        val assemblerCount = Regex("""isAutofillOfferCreateEntryEnabled\(\)""").findAll(assembler).count()
        assertEquals(1, pickerCount)
        assertEquals(1, assemblerCount)
    }

    // ===== AC③ 设置页链路 =====

    @Test
    fun `设置页从状态投影到卡片行整条链路在场`() {
        val uiState = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt"
        )
        val projection = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        )
        val controller = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt"
        )
        val viewModel = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        )
        val routes = readSource(
            "app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsNavGraphRoutes.kt"
        )
        val components = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsComponents.kt"
        )
        val screen = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsScreen.kt"
        )

        listOf("autofillManualPickerEnabled", "autofillOfferCreateEntry").forEach { field ->
            assertTrue("SettingsUiState 缺字段 $field", uiState.contains("val $field"))
            assertTrue("投影缺映射 $field", projection.contains("$field = extState.$field"))
        }
        assertTrue(
            controller.contains("fun setAutofillManualPickerEnabled(enabled: Boolean)")
        )
        assertTrue(
            controller.contains("fun setAutofillOfferCreateEntry(enabled: Boolean)")
        )
        assertTrue(
            viewModel.contains("fun setAutofillManualPickerEnabled(enabled: Boolean)")
        )
        assertTrue(
            viewModel.contains("fun setAutofillOfferCreateEntry(enabled: Boolean)")
        )
        assertTrue(
            routes.contains("onAutofillManualPickerToggle = settingsViewModel::setAutofillManualPickerEnabled")
        )
        assertTrue(
            routes.contains("onAutofillOfferCreateToggle = settingsViewModel::setAutofillOfferCreateEntry")
        )
        // 卡片行绑定（checked 必须取自 uiState 同名字段）
        assertTrue(
            components.contains("checked = uiState.autofillManualPickerEnabled")
        )
        assertTrue(
            components.contains("checked = uiState.autofillOfferCreateEntry")
        )
        // 屏幕把回调透传进两张卡
        assertTrue(
            screen.contains("onAutofillManualPickerToggle = onAutofillManualPickerToggle")
        )
        assertTrue(
            screen.contains("onAutofillOfferCreateToggle = onAutofillOfferCreateToggle")
        )
    }

    @Test
    fun `两开关文案中英成对且键名成对`() {
        val zh = readSource("app/src/main/res/values/strings.xml")
        val en = readSource("app/src/main/res/values-en/strings.xml")
        listOf(
            "autofill_manual_picker_title", "autofill_manual_picker_sub",
            "autofill_offer_create_title", "autofill_offer_create_sub"
        ).forEach { key ->
            assertTrue("zh 缺 $key", zh.contains("name=\"$key\""))
            assertTrue("en 缺 $key", en.contains("name=\"$key\""))
        }
    }
}
