package com.keepasskey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.keepasskey.app.ui.screens.edit.EntryEditScreen
import com.keepasskey.app.ui.screens.edit.EntryEditViewModel
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * `ISSUE-P3-643`：设备侧用例的**编辑页宿主**（最小 UI 测试基建）。
 *
 * 与 `EntryEditDeviceHostActivity` 的差别只有一处：本宿主把**生产的** [EntryEditViewModel]
 * 暴露给用例，使「连续选择同一图标」这一序列可以走真实的 ViewModel 入口调用。
 *
 * 为什么需要这条旁路（如实边界）：编辑页图标 tile 是图标-only 的可点击 Box，
 * **没有 `contentDescription`**，无障碍树里无法定位（这是本轮发现的既有可访问性缺口，
 * 已登记待办；补齐它属于编辑页 UI 改动，不在本项授权范围内）。因此：
 * - **真实 UI 点击**覆盖：标题输入、顶栏返回、丢弃确认对话框、「继续编辑」；
 * - **生产 ViewModel 入口**覆盖：`onCustomIconSelected(同一 id)` 连续两次——
 *   这正是图标选择器 `onSelectIcon` / `onSelectCustomIcon` 在选中时调用的同一个方法
 *   （`EntryEditScreen` 的 `onIconChange` / `onSelectCustomIcon` 直连它）。
 *
 * 组合件本身仍是生产的 `EntryEditScreen`（含其 `requestBack` 谓词与丢弃确认弹窗），
 * 故「脏位 → 是否弹确认」这条链路没有任何替身参与。
 */
@AndroidEntryPoint
class EntryEditDirtyConfirmDeviceHostActivity : ComponentActivity() {

    private val editViewModel: EntryEditViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent.getStringExtra(EntryEditDeviceHostActivity.EXTRA_ENTRY_ID)
        currentViewModel = editViewModel
        setContent {
            KeePasskeyTheme {
                EntryEditScreen(
                    entryId = entryId,
                    onBackClick = { finish() },
                    onSaveSuccess = { finish() },
                    viewModel = editViewModel
                )
            }
        }
    }

    override fun onDestroy() {
        if (currentViewModel === editViewModel) currentViewModel = null
        super.onDestroy()
    }

    companion object {
        /** 用例读取的生产 ViewModel（Activity 销毁即置空，避免跨用例持有）。 */
        @Volatile
        @JvmStatic
        var currentViewModel: EntryEditViewModel? = null
            private set
    }
}
