package com.keepasskey.app.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * ISSUE-P3-445 AC①：高困惑点「ⓘ 短说明 / 可关闭提示条」的提示位登记表。
 *
 * 每个提示位自带持久化键（SharedPreferences 布尔，无敏感数据）与双语文案资源，
 * 新增高困惑点只扩枚举 + 在页面插入一行 [DismissibleHelpTip]。
 */
enum class HelpTip(
    val prefsKey: String,
    val titleRes: Int,
    val bodyRes: Int
) {
    /** 云同步配置页「测试连接」：先保存再验证的顺序编排（用户常误以为会直接同步） */
    CLOUD_SYNC_TEST_CONNECTION(
        "help_tip_cloud_sync_test",
        R.string.help_tip_cloud_sync_test_title,
        R.string.help_tip_cloud_sync_test_body
    ),

    /** 自动填充设置页通道选择：应用内开关与系统侧选用是两层独立开关 */
    AUTOFILL_CHANNEL(
        "help_tip_autofill_channel",
        R.string.help_tip_autofill_channel_title,
        R.string.help_tip_autofill_channel_body
    ),

    /** 解锁页密钥文件：第二解锁因子的语义与丢失后果 */
    UNLOCK_KEYFILE(
        "help_tip_unlock_keyfile",
        R.string.help_tip_unlock_keyfile_title,
        R.string.help_tip_unlock_keyfile_body
    )
}

/**
 * ISSUE-P3-445 AC②：提示关闭态的持久化仓库（同 [com.keepasskey.app.ui.screens.vault.BatchSelectGuideStore] 范式）。
 *
 * SharedPreferences 布尔键承载「该提示已被关闭」，跨进程重启不再复现；
 * 各提示位相互独立（关一个不影响其余）。键与值均无任何敏感数据。
 */
class HelpTipStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /** 该提示位是否仍应呈现（未关闭过 = true） */
    fun shouldShow(tip: HelpTip): Boolean = !prefs.getBoolean(tip.prefsKey, false)

    /** 关闭提示位（持久化置位，幂等） */
    fun dismiss(tip: HelpTip) {
        prefs.edit().putBoolean(tip.prefsKey, true).apply()
    }

    companion object {
        const val PREFS_NAME = "help_tips"
    }
}

/**
 * 页内一次性可关闭提示条（ISSUE-P3-445 AC①②）。
 *
 * - 形态：ⓘ 图标 + 标题 + 短说明 + 关闭按钮的**非模态**内嵌卡片，不阻断任何操作；
 * - 关闭态经 [HelpTipStore] 持久化，重启后不复现（AC②）；
 * - 组件自包含（自带状态与持久化），调用点只需一行。
 */
@Composable
fun DismissibleHelpTip(tip: HelpTip, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { HelpTipStore(context) }
    var visible by remember(tip) { mutableStateOf(store.shouldShow(tip)) }
    if (!visible) return
    HelpTipCard(
        title = stringResource(tip.titleRes),
        body = stringResource(tip.bodyRes),
        onDismiss = {
            store.dismiss(tip)
            visible = false
        },
        modifier = modifier
    )
}

/** 提示条的无状态呈现体（预览与测试可直接驱动） */
@Composable
fun HelpTipCard(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.help_tip_dismiss),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "提示条 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(
    name = "提示条 - 深色",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
internal fun HelpTipCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        HelpTipCard(
            title = "关于「测试连接」",
            body = "点按后会先保存当前表单，再向远端发起真实连接验证。",
            onDismiss = {}
        )
    }
}
