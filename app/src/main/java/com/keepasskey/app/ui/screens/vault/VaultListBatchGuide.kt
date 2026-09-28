package com.keepasskey.app.ui.screens.vault

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.keepasskey.app.R
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage

/**
 * ISSUE-P3-360 AC④b：首次长按进入批量模式的一次性引导。
 *
 * - [BatchSelectGuideStore]：持久化「已引导过」标记（SharedPreferences 布尔，无任何敏感数据），
 *   [consumeFirstGuide] 消费即置位——**全安装生命周期只返回一次 true**；
 * - [publishBatchSelectGuide]：经全局 Snackbar 通道（`ISSUE-P3-359 AC④` 同一通道）发布引导文案，
 *   不新建屏内 Snackbar 宿主；
 * - [rememberBatchSelectGuideStore]：Screen 侧按组合记忆构造（LocalContext）。
 */
internal class BatchSelectGuideStore(private val prefs: android.content.SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /** 消费「首次长按」标记：仅首次调用返回 true，之后恒 false（持久化置位）。 */
    fun consumeFirstGuide(): Boolean {
        val firstTime = !prefs.getBoolean(KEY_GUIDED, false)
        if (firstTime) {
            prefs.edit().putBoolean(KEY_GUIDED, true).apply()
        }
        return firstTime
    }

    private companion object {
        const val PREFS_NAME = "vault_batch_guide"
        const val KEY_GUIDED = "batch_select_guided"
    }
}

/** 发布批量选择引导（全局通道单次消费；文案资源化，生产者不落硬编码）。 */
internal fun publishBatchSelectGuide() {
    AppSnackbarChannel.trySend(
        AppSnackbarEvent(message = UiMessage(R.string.vault_batch_select_guide))
    )
}

/** Screen 侧记忆构造（每次进入列表页组合各持一份，标记本身由 SharedPreferences 持久）。 */
@Composable
internal fun rememberBatchSelectGuideStore(): BatchSelectGuideStore {
    val context = LocalContext.current
    return remember(context) { BatchSelectGuideStore(context) }
}
