package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.sync.parentDirectoryPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远端目录浏览「返回上一级」的路径语义用例（仿安卓文件管理器 / Kp2a getParentPath）。
 *
 * ENG-20261028-12 整改：原附带的「对话框与装配段必须接路径跳转」用例只做
 * `dialog.contains("onNavigateToPath")` 之类的源码文本存在性判定——它锁的是当时的参数命名与
 * 调用写法（改名、下沉到子组件、改用状态提升都会误红），且不能证明「上一级」按钮真的能导航。
 * 该接线属 Compose 界面行为，须由设备 / 界面层验收覆盖；本件只保留纯函数的路径语义读数。
 */
class RemoteBrowseBackUpWiringTest {

    @Test
    fun `parentDirectoryPath 支持返回上一级`() {
        assertEquals("folder", parentDirectoryPath("folder/sub"))
        assertEquals("", parentDirectoryPath("folder"))
        assertEquals("", parentDirectoryPath(""))
        assertEquals("a/b", parentDirectoryPath("a/b/c"))
        assertEquals("", parentDirectoryPath("/"))
    }
}
