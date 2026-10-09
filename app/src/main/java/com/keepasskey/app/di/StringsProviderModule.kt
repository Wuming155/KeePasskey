package com.keepasskey.app.di

import android.content.Context
import com.keepasskey.app.ui.AppLocaleTracker
import com.keepasskey.app.ui.localizedResourcesFor
import com.keepasskey.app.ui.model.StringsProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Singleton

/**
 * TASK-21：[StringsProvider] 生产绑定——经**按应用内语言派生过的** Resources 取串。
 *
 * **铁律（ISSUE-P3-459 真机事故）**：这里**严禁**直接 `context.getString(id)`。
 * `@ApplicationContext` 的 resources 按**系统 locale** 定形，拿不到本仓 UI 侧
 * `localizedContextOf` 包出来的 Wrapper ⇒ 应用内语言切到 English 后，所有经
 * `StringsProvider.get()` 的文案**恒为中文**（真机复现：解锁页 / 生物识别 / 本地存储 /
 * 改主密码 / 同步失败五处全是中文，唯独走 Compose `stringResource(LocalContext)` 的
 * 「主密码错误」一条是英文——两条通道口径不一致正是本次事故的识别特征）。
 * 必须经 [localizedResourcesFor] 按 [AppLocaleTracker.snapshot] 派生后再 `getString`。
 * 机检规则 D（`tools/audit/check_user_visible_cjk.py`）锁死本条，改动前先看该脚本读数。
 *
 * 单元测试环境不走 Hilt，直接构造 SettingsViewModel 时注入假实现。
 */
@Module
@InstallIn(SingletonComponent::class)
object StringsProviderModule {

    @Provides
    @Singleton
    fun provideStringsProvider(
        @ApplicationContext context: Context,
        localeTracker: AppLocaleTracker
    ): StringsProvider = object : StringsProvider {

        override fun get(id: Int, vararg args: Any?): String =
            localizedResourcesFor(context, localeTracker.snapshot()).getString(id, *args)

        /**
         * `ISSUE-P3-560`：把应用内语言一并交出去——需要 Locale 的格式化（如
         * `RelativeTimeFormatter` 的 `ofPattern(pattern, locale)`）必须与取 pattern 的同一语言，
         * 否则英文资源里的 `MMM d` 会按系统 Locale 渲染出混合语言文案。
         * `null` 表示跟随系统（`RelativeTimeFormatter` 侧回落 `Locale.getDefault()`）。
         */
        override val locale: Locale?
            get() = localeTracker.snapshot()
    }
}
