package com.keepasskey.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * 设置主页「设置项搜索」的**纯匹配 / 高亮内核**（`ISSUE-P3-444` AC③）。
 *
 * 无 Android / 组合依赖，JVM 单测可直接断言（`SettingsSearchTest`）。
 * 判定口径与列表页搜索一致（大小写不敏感子串），并同样对**已生效**的输入词求值。
 */
internal object SettingsSearch {

    /** 空 / 全空白查询恒为真（未搜索时不过滤任何行）。 */
    fun matchesQuery(query: String, texts: List<String>): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return texts.any { it.contains(q, ignoreCase = true) }
    }

    /**
     * 命中高亮：把 [text] 中**首个**（大小写不敏感）[query] 命中段包上 [style]。
     *
     * 只高亮首个命中段（与列表页行内高亮同口径：设置项标题短，多段命中无额外信息量）；
     * 未命中或查询空白时原样返回（不产生任何 span，调用方无需分支）。
     */
    fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString {
        val q = query.trim()
        if (q.isEmpty()) return AnnotatedString(text)
        val index = text.indexOf(q, ignoreCase = true)
        if (index < 0) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text.substring(0, index))
            withStyle(style) { append(text.substring(index, index + q.length)) }
            append(text.substring(index + q.length))
        }
    }
}

/**
 * 当前生效的设置项搜索词（`ISSUE-P3-444` AC③ 的命中高亮通道）。
 *
 * 行组件（[ModernSettingsRow] / `DisplayPrefRow`）在**深层**渲染自己的标题，逐层透传查询词
 * 会污染十余个中间签名；改由分组在渲染每行时注入本局部值。缺省空串 ⇒ 无高亮
 * （未注入的宿主，如各二级设置页，渲染行为与改动前完全一致）。
 */
internal val LocalSettingsHighlightQuery = androidx.compose.runtime.compositionLocalOf { "" }

/**
 * 一条可搜索的设置项：参与过滤的文本 + 渲染体。
 *
 * [searchTexts] 由调用点在组合中经 `stringResource` 解析后传入（标题 / 副标题 / 追加关键词），
 * 使过滤与渲染共用同一份文案，不会出现「换语言后过滤不准」。
 */
internal class SettingsSearchRow(
    val searchTexts: List<String>,
    val content: @Composable () -> Unit
)

/** 一个设置分组：分组标题资源 + 行列表。 */
internal class SettingsGroupSpec(
    @StringRes val headerRes: Int,
    val rows: List<SettingsSearchRow>
)

/**
 * 构造一条设置行（`@Composable` 只为解析两条文案资源；返回值供 [SettingsSearchGroup] 过滤）。
 */
@Composable
internal fun settingsRow(
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int,
    content: @Composable () -> Unit
): SettingsSearchRow = SettingsSearchRow(
    searchTexts = listOf(stringResource(titleRes), stringResource(subtitleRes)),
    content = content
)

/**
 * 按关键词过滤后的分组卡片：整组零命中时**整组不渲染**（含分组标题），
 * 组内命中的行之间保留分隔线（首个可见行前不加）。
 */
@Composable
internal fun SettingsSearchGroup(
    @StringRes headerRes: Int,
    query: String,
    rows: List<SettingsSearchRow>
) {
    val visible = rows.filter { SettingsSearch.matchesQuery(query, it.searchTexts) }
    if (visible.isEmpty()) return
    ModernSectionHeader(title = stringResource(headerRes))
    SettingsGroupCard {
        visible.forEachIndexed { index, row ->
            if (index > 0) SettingsItemDivider()
            // 命中高亮经局部值下传（行组件在深层渲染标题，逐层透传会污染中间签名）
            androidx.compose.runtime.CompositionLocalProvider(
                LocalSettingsHighlightQuery provides query
            ) {
                row.content()
            }
        }
    }
}

/** 关键词零命中空态（`ISSUE-P3-444` AC③：如实告知「没有匹配项」，不呈现空白页）。 */
@Composable
internal fun SettingsSearchEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.settings_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_search_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
