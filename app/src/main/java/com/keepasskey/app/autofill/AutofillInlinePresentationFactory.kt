package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.autofill.InlinePresentation
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IME 内联建议构建器（ISSUE-P3-03 43b：`inlineSuggestionsEnabled` 的真实消费点）。
 *
 * 从 [KeePasskeyAutofillService] 抽出的独立协作者，理由有二：
 * 1. **单一职责**：自动填充服务负责请求编排，内联展示（Slice 构建）属展示层适配，
 *    由本类独占；服务文件因此回到 400 行阈值以内；
 * 2. **可测**：开关判定与「请求/规格不满足即返回 null」的降级链在此收敛，
 *    不必依赖 Android 框架即可对降级决策做状态断言。
 *
 * 开关语义（设置页文案「键盘上方内联候选条 (Android 11+)」）：
 * - 关闭 → 恒返回 null，数据集不携带内联展示，自动回退为下拉/填充对话框；
 * - 开启 → 仍需请求侧携带 [InlineSuggestionsRequest] 且 IME spec 声明 v1 模板，
 *   任一不满足同样返回 null（官方裁决，非本应用可控）。
 */
@Singleton
class AutofillInlinePresentationFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: ExtendedSettingsStore
) {

    /** 用户偏好：内联建议是否启用（未持久化时回落默认值） */
    fun isEnabled(): Boolean = settingsStore.isInlineSuggestionsEnabled()

    /**
     * 构建官方 androidx.autofill.inline v1 内容模型（Slice）。
     * @return null 表示本次不下发内联展示（开关关闭 / 请求未携带 / IME 不支持 v1 / 构建失败）
     */
    fun build(
        inlineRequest: InlineSuggestionsRequest?,
        title: CharSequence,
        subtitle: CharSequence
    ): InlinePresentation? {
        if (!isEnabled()) return null
        if (inlineRequest == null) return null
        val spec: InlinePresentationSpec = inlineRequest.inlinePresentationSpecs.firstOrNull()
            ?: return null
        return try {
            // 官方裁决：仅当 IME spec 声明支持 v1 UI 模板时才构建 Slice
            if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) {
                return null
            }
            // v1 内容构建器要求 attribution PendingIntent（系统内联卡片上打开提供方应用的入口）
            val attribution = PendingIntent.getActivity(
                context,
                REQUEST_CODE_INLINE_ATTRIBUTION,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val content = InlineSuggestionUi.newContentBuilder(attribution)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setStartIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
                .build()
            InlinePresentation(content.slice, spec, false)
        } catch (t: Throwable) {
            AppLog.w(TAG, "构建 InlinePresentation 失败，回退下拉展示", t)
            null
        }
    }

    private companion object {
        const val TAG = "AutofillInlinePresentation"
        const val REQUEST_CODE_INLINE_ATTRIBUTION = 2002
    }
}
