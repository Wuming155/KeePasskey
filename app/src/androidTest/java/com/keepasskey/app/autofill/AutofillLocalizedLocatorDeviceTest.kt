package com.keepasskey.app.autofill

import android.content.res.Configuration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** ISSUE-P3-608：在 Android 上验证资源解析、角色/窗口约束与故意错误身份反例。 */
@RunWith(AndroidJUnit4::class)
class AutofillLocalizedLocatorDeviceTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageName = targetContext.packageName
    private val titleResourceName = targetContext.resources.getResourceName(R.id.tv_username)

    @Test
    fun 中英文从被测APK资源读取且不改变原配置() {
        val before = targetContext.resources.configuration.locales.toLanguageTags()
        val chinese = labelsFor(Locale.SIMPLIFIED_CHINESE)
        val english = labelsFor(Locale.ENGLISH)
        assertEquals("解锁 KeePasskey 查看凭据", chinese.dataset(R.string.cred_autofill_unlock_prompt))
        assertEquals("Unlock KeePasskey to view credentials", english.dataset(R.string.cred_autofill_unlock_prompt))
        assertEquals("搜索全部条目…", chinese.dataset(R.string.autofill_picker_entry_title))
        assertEquals("Search all entries…", english.dataset(R.string.autofill_picker_entry_title))
        assertEquals("确认填充", chinese.dataset(R.string.autofill_confirm_ok))
        assertEquals("Confirm Fill", english.dataset(R.string.autofill_confirm_ok))
        assertEquals(before, targetContext.resources.configuration.locales.toLanguageTags())
    }

    @Test
    fun 排版空白归一化但相似文字必须拒绝() {
        val node = titleNode("U\u00a0N\tL O C K\nV A U L T")
        assertTrue(AutofillNodeIdentity.exactText(node, "U N L O C K   V A U L T"))
        node.text = "Confirm Fill another entry"
        assertFalse(AutofillNodeIdentity.exactText(node, "Confirm Fill"))
    }

    @Test
    fun 应用按钮必须同时匹配包名角色与文案() {
        val node = titleNode("Confirm Fill").apply { className = AutofillNodeIdentity.BUTTON }
        assertTrue(AutofillNodeIdentity.appButton(node, packageName, "Confirm Fill"))
        node.packageName = "com.example.unrelated"
        assertFalse(AutofillNodeIdentity.appButton(node, packageName, "Confirm Fill"))
        node.packageName = packageName
        node.className = AutofillNodeIdentity.TEXT_VIEW
        assertFalse(AutofillNodeIdentity.appButton(node, packageName, "Confirm Fill"))
        node.className = AutofillNodeIdentity.BUTTON
        assertFalse(AutofillNodeIdentity.appButton(node, packageName, "确认填充"))
    }

    @Test
    fun 隐藏禁用及不可点击按钮不得成为目标() {
        val node = titleNode("Confirm Fill").apply { className = AutofillNodeIdentity.BUTTON }
        node.isVisibleToUser = false
        assertFalse(AutofillNodeIdentity.appButton(node, packageName, "Confirm Fill"))
        node.isVisibleToUser = true
        node.isEnabled = false
        assertFalse(AutofillNodeIdentity.appButton(node, packageName, "Confirm Fill"))
        node.isEnabled = true
        node.isClickable = false
        assertFalse(AutofillNodeIdentity.appButton(node, packageName, "Confirm Fill"))
    }

    @Test
    fun 数据集精确标题和固定资源身份拒绝相似项() {
        val expected = labelsFor(Locale.ENGLISH).dataset(R.string.cred_autofill_unlock_prompt)
        val node = titleNode(expected).apply { viewIdResourceName = titleResourceName }
        assertTrue(AutofillNodeIdentity.datasetTitle(node, titleResourceName, expected))
        node.viewIdResourceName = "$packageName:id/tv_subtitle"
        assertFalse(AutofillNodeIdentity.datasetTitle(node, titleResourceName, expected))
        node.viewIdResourceName = titleResourceName
        node.text = "$expected (other)"
        assertFalse(AutofillNodeIdentity.datasetTitle(node, titleResourceName, expected))
        node.text = expected
        node.className = AutofillNodeIdentity.BUTTON
        assertFalse(AutofillNodeIdentity.datasetTitle(node, titleResourceName, expected))
    }

    @Test
    fun 数据集窗口拒绝旧窗口无关应用与无焦点前提() {
        val system = AccessibilityWindowInfo.TYPE_SYSTEM
        assertTrue(AutofillNodeIdentity.datasetWindow(system, "android", TOP_LAYER, CLIENT_LAYER))
        assertFalse(AutofillNodeIdentity.datasetWindow(system, "android", CLIENT_LAYER, TOP_LAYER))
        assertFalse(AutofillNodeIdentity.datasetWindow(system, "com.example.unrelated", TOP_LAYER, CLIENT_LAYER))
        assertFalse(AutofillNodeIdentity.datasetWindow(system, "android", TOP_LAYER, null))
        assertFalse(AutofillNodeIdentity.datasetWindow(
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, "android", TOP_LAYER, CLIENT_LAYER
        ))
    }

    @Test
    fun 内联建议无应用资源ID仍必须匹配文本控件与精确文案() {
        assertTrue(AutofillNodeIdentity.datasetWindow(
            AccessibilityWindowInfo.TYPE_INPUT_METHOD, "com.example.ime", TOP_LAYER, CLIENT_LAYER
        ))
        val node = titleNode("Search all entries…").apply { viewIdResourceName = "com.example.ime:id/suggestion" }
        assertTrue(AutofillNodeIdentity.datasetTitle(node, titleResourceName, "Search all entries…", inline = true))
        node.text = "Search all entries… unrelated"
        assertFalse(AutofillNodeIdentity.datasetTitle(node, titleResourceName, "Search all entries…", inline = true))
    }

    private fun labelsFor(locale: Locale): AutofillTestLabels {
        val config = Configuration(targetContext.resources.configuration).apply { setLocale(locale) }
        return AutofillTestLabels(targetContext.createConfigurationContext(config))
    }

    private fun titleNode(value: String): AccessibilityNodeInfo = AccessibilityNodeInfo().apply {
        packageName = this@AutofillLocalizedLocatorDeviceTest.packageName
        className = AutofillNodeIdentity.TEXT_VIEW
        text = value
        isVisibleToUser = true
        isEnabled = true
        isClickable = true
    }

    private companion object {
        const val CLIENT_LAYER = 1
        const val TOP_LAYER = 2
    }
}
