package com.keepasskey.app.security

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局「脏表单」注册表与锁定丢弃登记（ISSUE-P2-355 AC③）。
 *
 * 解耦要点（依赖倒置 + 包边界）：锁定路径（security 侧 [AutoLockSessionGuard]）只依赖本类型，
 * **不反向 import ui**；编辑页 ViewModel（ui 侧）在生命周期内注册「isDirty 提供者」、销毁时注销。
 * 本类只回答两件事——「现在有没有未保存编辑」与「本次锁定是否丢弃了未保存编辑」。
 *
 * 为何是「丢弃 + 一次性告知」而不是确认弹窗 / 暂存草稿（AC③ 的取舍，登记以免反复）：
 * - 可达的锁定触发（熄屏即锁 / 后台超时）都发生在**用户不可应答**的时刻（屏幕已灭或已离开），
 *   且锁定本身是安全硬约束（熄屏必须立即清零会话）——推迟锁定等用户确认 = 熄屏期间会话存活，
 *   不可接受；而返回键 / 手动锁入口只存在于顶层路由，编辑页自身的返回丢弃确认已先行接管
 *   （EntryEditContent 的 requestBack），故「先弹确认」在现有导航结构下没有可达触发点。
 * - 草稿跨锁暂存会把表单内容带过「锁定即擦除」安全边界；且口令 / TOTP 明文本就不在
 *   UiState 中（M1 / TASK-10 铁律），残缺草稿恢复后重存反而可能静默覆盖丢失机密字段。
 * 故：锁定收口处（[AutoLockSessionGuard.triggerLock]）先登记「已丢弃」事实，
 * 解锁页一次性告知「上次未保存的改动已丢弃」——AC③「不静默丢编辑」的意图由此覆盖。
 */
@Singleton
class UnsavedEditRegistry @Inject constructor() {

    /** owner → 脏态提供者；按注册 owner 配对注销（编辑页 ViewModel 单实例注册） */
    private val providers = mutableMapOf<Any, () -> Boolean>()

    /** 锁定丢弃未保存编辑的一次性告知标记（解锁页消费后复位） */
    private val _unsavedEditsDiscarded = MutableStateFlow(false)

    /** 注册脏态提供者（同 owner 重复注册以新 lambda 覆盖，幂等） */
    @Synchronized
    fun register(owner: Any, isDirty: () -> Boolean) {
        providers[owner] = isDirty
    }

    /** 注销提供者（编辑页 ViewModel 销毁时调用，杜绝陈旧脏态残留） */
    @Synchronized
    fun unregister(owner: Any) {
        providers.remove(owner)
    }

    /** 当前是否存在未保存编辑（任一已注册提供者为 true） */
    @Synchronized
    fun hasUnsavedEdits(): Boolean = providers.values.any { it() }

    /** 锁定收口唯一置位入口：存在未保存编辑即登记「已丢弃」（幂等，可重复置位） */
    @Synchronized
    fun markDirtyEditsDiscarded() {
        if (providers.values.any { it() }) {
            _unsavedEditsDiscarded.value = true
        }
    }

    /** 解锁页一次性消费：返回是否需要展示告知；读后复位（下次真丢弃才会再提示） */
    @Synchronized
    fun consumeDiscardNotice(): Boolean {
        val pending = _unsavedEditsDiscarded.value
        _unsavedEditsDiscarded.value = false
        return pending
    }
}
