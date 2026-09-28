package com.keepasskey.app.ui.screens.edit

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「搜索空态 → 新建条目」的一次性预填宿主（ISSUE-P3-352 AC①）。
 *
 * 为什么不走路由参数：搜索词可能被用户粘贴任何内容（含口令形态文本），
 * 经 nav route 会落入 `SavedStateHandle`（进程死亡时持久化）——违反「敏感值不落
 * 持久化载体」的项目口径（P2-105 同源红线的延伸）。故以内存单例承接：
 * [publish] 仅由列表页搜索空态「新建条目」出口在**导航前一拍**调用，
 * [takeTitle] 由编辑页 **init 即取**（新建形态套用预填；编辑 / 模板形态**只取不用**）——
 * 取与用分离，陈旧预填既不串进下一次无关新建、也不覆盖既有条目标题。
 */
@Singleton
class CreateEntryPrefillHost @Inject constructor() {

    /** 待消费的预填标题；仅主线程读写（两端调用点均为 ViewModel 主线程方法）。 */
    @Volatile
    private var pendingTitle: String? = null

    /** 发布一次性预填（空白串视为无预填）。 */
    fun publish(title: String) {
        pendingTitle = title.trim().takeIf { it.isNotEmpty() }
    }

    /** 取走并清空预填；无预填返回 null。 */
    fun takeTitle(): String? {
        val taken = pendingTitle
        pendingTitle = null
        return taken
    }
}
