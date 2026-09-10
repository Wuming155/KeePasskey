package com.keepasskey.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-03：偏好单键读取接口的默认值契约测试。
 *
 * 消费方（日志闸门 / 自动填充服务 / 同步编排器）在运行期高频调用这些单键读取方法，
 * 其默认值必须与 [com.keepasskey.app.ui.screens.settings.ExtendedSettings] 的字段默认值
 * **逐项一致**——任何一处漂移都会让「未持久化」状态下的行为与设置页显示不符。
 * 使用 null 上下文构造（不持久化的内存语义）验证回落分支。
 */
class ExtendedSettingsStoreSingleKeyTest {

    private val store = ExtendedSettingsStore(null)

    @Test
    fun `诊断日志默认关闭`() {
        assertFalse(store.isDiagnosticLogEnabled())
    }

    @Test
    fun `详细同步日志默认关闭`() {
        assertFalse(store.isVerboseSyncLogEnabled())
    }

    @Test
    fun `内联建议默认开启`() {
        assertTrue(store.isInlineSuggestionsEnabled())
    }

    @Test
    fun `填充后复制 TOTP 默认开启`() {
        assertTrue(store.isAutofillCopyTotpEnabled())
    }
}
