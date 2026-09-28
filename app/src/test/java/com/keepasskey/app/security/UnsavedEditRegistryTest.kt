package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-355 AC③：全局脏表单注册表语义锁。
 *
 * 覆盖四条契约：
 * 1. 注册 / 注销与脏态提供者的配对（注销后不得残留陈旧脏态——锁定销毁编辑页后的关键防线）；
 * 2. 锁定收口登记「已丢弃」只在存在脏表单时发生；
 * 3. 一次性告知只可消费一次；
 * 4. 无脏表单的锁定不登记告知（干净锁定不得骚扰用户）。
 */
class UnsavedEditRegistryTest {

    @Test
    fun `注册提供者后按脏态翻转报告且注销后不残留`() {
        val registry = UnsavedEditRegistry()
        val owner = Any()
        var dirty = false
        registry.register(owner) { dirty }

        assertFalse("初始干净态不得报告脏表单", registry.hasUnsavedEdits())
        dirty = true
        assertTrue("编辑发生后必须报告脏表单", registry.hasUnsavedEdits())

        registry.unregister(owner)
        assertFalse("注销后不得残留陈旧脏态", registry.hasUnsavedEdits())
    }

    @Test
    fun `存在脏表单时锁定登记丢弃告知且仅可消费一次`() {
        val registry = UnsavedEditRegistry()
        registry.register(Any()) { true }

        registry.markDirtyEditsDiscarded()

        assertTrue("脏表单被锁定丢弃必须可告知", registry.consumeDiscardNotice())
        assertFalse("告知为一次性：二次消费必须为空", registry.consumeDiscardNotice())
    }

    @Test
    fun `无脏表单时锁定不登记告知`() {
        val registry = UnsavedEditRegistry()

        registry.markDirtyEditsDiscarded()

        assertFalse("干净锁定不得给出丢弃告知", registry.consumeDiscardNotice())
    }

    @Test
    fun `注销后锁定不再登记告知`() {
        val registry = UnsavedEditRegistry()
        val owner = Any()
        registry.register(owner) { true }
        registry.unregister(owner)

        registry.markDirtyEditsDiscarded()

        assertFalse("已离开的编辑页不得再影响告知", registry.consumeDiscardNotice())
    }
}
