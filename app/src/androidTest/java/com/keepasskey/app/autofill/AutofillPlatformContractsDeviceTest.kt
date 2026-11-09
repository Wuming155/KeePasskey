package com.keepasskey.app.autofill

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.autofill.AutofillId
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.EditText
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENG-20261028-12：平台对象与工厂契约的真实设备行为用例。
 *
 * 替代原先在 JVM 宿主单测中的两处源码结构锁定断言：
 * 1. [SensitiveToStringRedactionTest] 对 `ParsedAutofillNode.toString()` 的源码字符比对：
 *    在真机/模拟器主线程经真实 [EditText.getAutofillId] 构造真实实例，注入哨兵断言无泄露且具备结构摘要；
 * 2. [AutofillPrivacyDefaultsTest] 的 `disabledGatePrecedesRequest()` 源码文本顺序比对：
 *    在 Android 运行时使用真实的 [InlineSuggestionsRequest]、[InlinePresentationSpec] 与 [UiVersions]
 *    验证 [AutofillInlinePresentationFactory] 的关闭不下发、开启下发与降级契约。
 */
@RunWith(AndroidJUnit4::class)
class AutofillPlatformContractsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var settingsStore: ExtendedSettingsStore
    private lateinit var originalSettings: ExtendedSettings

    @Before
    fun setUp() {
        settingsStore = ExtendedSettingsStore(context)
        originalSettings = settingsStore.load()
    }

    @After
    fun tearDown() {
        settingsStore.save(originalSettings)
    }

    @Test
    fun `ParsedAutofillNode真实平台实例toString绝不泄漏哨兵且具备结构摘要`() {
        var autofillId: AutofillId? = null
        instrumentation.runOnMainSync {
            val editText = EditText(context)
            autofillId = editText.autofillId
        }
        val realAutofillId = checkNotNull(autofillId) { "主线程 EditText 必须产出有效 AutofillId" }

        val sentinel = "S3NT1N3L-D0-N0T-LEAK"
        val node = ParsedAutofillNode(
            autofillId = realAutofillId,
            autofillHints = listOf("password"),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            isFocused = true,
            htmlName = "$sentinel-htmlName",
            label = "$sentinel-label",
            webDomain = "example.com",
            isVisible = true,
            importantForAutofill = true,
            autocomplete = "$sentinel-autocomplete",
            className = "$sentinel-className",
            contentDescription = "$sentinel-contentDescription",
            text = "$sentinel-plaintext-password"
        )

        val rendered = node.toString()

        assertFalse(
            "ParsedAutofillNode.toString() 绝不得泄露任何敏感字段哨兵明文（实际：$rendered）",
            rendered.contains(sentinel, ignoreCase = true)
        )
        assertTrue("必须输出 textLength 摘要而非明文内容", rendered.contains("textLength=${node.text.length}"))
        assertTrue("必须输出 hasHtmlName 结构标识", rendered.contains("hasHtmlName=true"))
        assertTrue("必须输出 hasLabel 结构标识", rendered.contains("hasLabel=true"))
        assertTrue("必须输出 hasClassName 结构标识", rendered.contains("hasClassName=true"))
        assertTrue("必须输出 hasContentDesc 结构标识", rendered.contains("hasContentDesc=true"))
        assertTrue("必须输出 hasAutocomplete 结构标识", rendered.contains("hasAutocomplete=true"))
        assertTrue("必须输出真实 autofillId 标识", rendered.contains("autofillId="))
    }

    @Test
    fun `AutofillInlinePresentationFactory真实请求契约_关闭恒不下发_开启有效下发_规格不符降级`() {
        Assume.assumeTrue(
            "内联建议特性要求 Android 11+（API 30+）",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        )

        val factory = AutofillInlinePresentationFactory(context, settingsStore)

        // 构造支持 v1 UI 样式的合法 InlineSuggestionsRequest
        val v1Style = InlineSuggestionUi.newStyleBuilder().build()
        val styleBundle = UiVersions.newStylesBuilder().addStyle(v1Style).build()
        val validSpec = InlinePresentationSpec.Builder(Size(100, 50), Size(400, 100))
            .setStyle(styleBundle)
            .build()
        val validRequest = InlineSuggestionsRequest.Builder(listOf(validSpec)).build()

        // 构造不支持 v1 样式的 InlineSuggestionsRequest（空 Bundle 样式）
        val unsupportedSpec = InlinePresentationSpec.Builder(Size(100, 50), Size(400, 100))
            .setStyle(Bundle())
            .build()
        val unsupportedRequest = InlineSuggestionsRequest.Builder(listOf(unsupportedSpec)).build()

        // 1. 开关关闭（默认态）：即使请求完全合法，也恒返回 null（关闭不下发）
        settingsStore.save(originalSettings.copy(inlineSuggestionsEnabled = false))
        assertFalse("开关必须为关闭态", factory.isEnabled())
        assertNull(
            "开关关闭时即使请求合法也恒不得下发内联卡片（防通道未开启外泄）",
            factory.build(validRequest, "fakeUser", "fakeTitle")
        )

        // 2. 开关开启：合法 v1 请求必须成功构建非空 InlinePresentation 并持有 Slice
        settingsStore.save(originalSettings.copy(inlineSuggestionsEnabled = true))
        assertTrue("开关必须为开启态", factory.isEnabled())
        val presentation = factory.build(validRequest, "fakeUser", "fakeTitle")
        assertNotNull("开关开启且请求样式支持 v1 时必须产出 InlinePresentation", presentation)
        assertNotNull("构建的 InlinePresentation 必须持有有效 Slice 内容", presentation?.slice)

        // 3. 降级分支一：请求为 null 时降级返回 null
        assertNull(
            "请求为 null 时必须优雅降级为 null",
            factory.build(null, "fakeUser", "fakeTitle")
        )

        // 4. 降级分支二：请求样式不声明支持 v1 时降级返回 null
        assertNull(
            "IME 样式不支持 v1 模板时必须降级为 null（官方降级链）",
            factory.build(unsupportedRequest, "fakeUser", "fakeTitle")
        )
    }
}
