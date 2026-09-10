package com.keepasskey.app.ui.screens.importer

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.importer.ImportOutcome
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.data.importer.ImportWarning
import com.keepasskey.app.data.importer.ImportWarningReason
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * ISSUE-P3-19 交付物 4：明文导入结果报告界面。
 *
 * 纪律（务必保持）：
 * - **不展示任何条目字段明文**——标题、用户名、URL、备注、密码一律不进界面；
 *   警告项只渲染机器可读定位符（`doc` / `row:3` / `entry:12` / `path:A/B`，见
 *   `ImportWarningLocation`）与稳定编码对应的资源文案；
 * - Screen / 区块不写业务逻辑：状态经 [ImportUiState] 传入，事件经回调上行；
 * - 全部文案经 `R.string.*` 引用（含警告编码 → 资源映射 [warningReasonRes]）。
 */
@Composable
fun ImportReportDialog(state: ImportUiState, onDismiss: () -> Unit) {
    when (state) {
        ImportUiState.Idle -> Unit
        is ImportUiState.Parsing -> ImportProgressDialog(state.source)
        is ImportUiState.Done -> ImportResultDialog(state.outcome, onDismiss)
        is ImportUiState.Failed -> ImportFailureDialog(state.reason.messageRes, onDismiss)
    }
}

/** 解析中：转圈 + 「正在解析 X 数据...」（复用既有资源，无新增文案）。 */
@Composable
private fun ImportProgressDialog(source: ImportSource) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.dbset_import_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE.dp))
                Text(
                    text = stringResource(R.string.dbset_import_preparing, importSourceLabel(source)),
                    modifier = Modifier.padding(start = PROGRESS_GAP.dp)
                )
            }
        },
        confirmButton = {}
    )
}

/** 导入完成：来源 + 计数 + 警告列表。 */
@Composable
private fun ImportResultDialog(outcome: ImportOutcome, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_report_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ImportOutcomeReport(outcome)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}

/** 导入失败：归类原因文案（不含异常 message，避免路径等外部标识外泄）。 */
@Composable
private fun ImportFailureDialog(@StringRes reasonRes: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_import_title)) },
        text = { Text(text = stringResource(reasonRes), color = MaterialTheme.colorScheme.error) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}

/** 可复用报告区块（Dialog 与整屏均可复用；状态全来自入参，无业务逻辑）。 */
@Composable
fun ImportOutcomeReport(outcome: ImportOutcome, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp)) {
        Text(
            text = stringResource(R.string.import_report_source, importSourceLabel(outcome.source)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(
                R.string.import_report_counts,
                outcome.imported,
                outcome.updated,
                outcome.skipped,
                outcome.failed
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        if (outcome.sourceSkipped > 0) {
            Text(
                text = stringResource(R.string.import_report_source_skipped, outcome.sourceSkipped),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (outcome.movedToRecycleBin > 0) {
            Text(
                text = stringResource(R.string.import_report_recycled, outcome.movedToRecycleBin),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (outcome.isEmpty) {
            Text(
                text = stringResource(R.string.import_report_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        ImportWarningList(outcome.warnings)
    }
}

/**
 * 警告列表：定位符 + 归类文案。
 * 条数已由框架按 `ImportLimits.MAX_WARNINGS` 封顶；溢出滚动交给外层容器（对话框内的滚动列），
 * 此处**不再**单独限高（限高会裁掉末尾警告且无法滚动查看）。
 */
@Composable
private fun ImportWarningList(warnings: List<ImportWarning>) {
    if (warnings.isEmpty()) return
    Text(
        text = stringResource(R.string.import_report_warnings_title, warnings.size),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        warnings.forEach { warning ->
            Text(
                text = stringResource(
                    R.string.import_report_warning_item,
                    warning.location,
                    warningReasonLabel(warning)
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = WARNING_ITEM_GAP.dp)
            )
        }
    }
}

/** 警告编码 → 文案（未知编码回退 [R.string.import_warn_unknown]，不吞掉也不瞎猜）。 */
@Composable
private fun warningReasonLabel(warning: ImportWarning): String {
    val reason = ImportWarningReason.fromCode(warning.reason)
    return if (reason == null) {
        stringResource(R.string.import_warn_unknown)
    } else {
        stringResource(warningReasonRes(reason))
    }
}

/** 数据源 → 既有本地化选项文案（与设置页共用同一套资源，无新增）。 */
@Composable
private fun importSourceLabel(source: ImportSource): String = stringResource(sourceLabelRes(source))

@StringRes
private fun sourceLabelRes(source: ImportSource): Int = when (source) {
    ImportSource.ONEPASSWORD_1PUX -> R.string.dbset_src_1pux
    ImportSource.BITWARDEN_JSON -> R.string.dbset_src_bitwarden
    ImportSource.KEEPASS_XML -> R.string.dbset_src_keepass
    ImportSource.BROWSER_CSV -> R.string.dbset_src_browser
}

/** 警告稳定编码 → 资源文案（穷尽映射：新增编码漏配资源会在编译期暴露）。 */
@StringRes
internal fun warningReasonRes(reason: ImportWarningReason): Int = when (reason) {
    ImportWarningReason.EMPTY_ROW -> R.string.import_warn_empty_row
    ImportWarningReason.MISSING_REQUIRED_VALUE -> R.string.import_warn_missing_value
    ImportWarningReason.COLUMN_COUNT_MISMATCH -> R.string.import_warn_column_mismatch
    ImportWarningReason.INVALID_UTF8 -> R.string.import_warn_invalid_utf8
    ImportWarningReason.PROTECTED_VALUE_SKIPPED -> R.string.import_warn_protected_value
    ImportWarningReason.HISTORY_IGNORED -> R.string.import_warn_history_ignored
    ImportWarningReason.CUSTOM_FIELD_DROPPED -> R.string.import_warn_custom_field_dropped
    ImportWarningReason.DUPLICATE_ENTRY_SKIPPED -> R.string.import_warn_duplicate_skipped
    ImportWarningReason.GROUP_CREATE_FAILED -> R.string.import_warn_group_create_failed
    ImportWarningReason.ENTRY_SAVE_FAILED -> R.string.import_warn_save_failed
    ImportWarningReason.WARNINGS_TRUNCATED -> R.string.import_warn_truncated
    // ISSUE-P3-19 集成补齐：解析器子批（Bitwarden JSON / 1PUX）的跳过与降级面。
    // 补齐前这些编码未登记 → fromCode 反查为 null → UI 一律显示「未知警告」。
    ImportWarningReason.NON_LOGIN_ITEM_SKIPPED -> R.string.import_warn_non_login_item
    ImportWarningReason.NON_LOGIN_CATEGORY_SKIPPED -> R.string.import_warn_non_login_category
    ImportWarningReason.FOLDER_NOT_FOUND -> R.string.import_warn_folder_not_found
    ImportWarningReason.ITEM_NOT_OBJECT -> R.string.import_warn_item_not_object
    ImportWarningReason.OPTIONAL_FIELD_INVALID -> R.string.import_warn_optional_field_invalid
    ImportWarningReason.TITLE_MISSING -> R.string.import_warn_title_missing
    ImportWarningReason.GROUP_NAME_MISSING -> R.string.import_warn_group_name_missing
}

/** 假数据预览（「Screen 可用假数据独立渲染」的验收要求；不含任何真实条目字段）。 */
@Preview(name = "ImportReport - Light", showBackground = true)
@Preview(name = "ImportReport - Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ImportOutcomeReportPreview() {
    KeePasskeyTheme {
        ImportOutcomeReport(
            outcome = ImportOutcome(
                source = ImportSource.KEEPASS_XML,
                parsed = 12,
                sourceSkipped = 1,
                imported = 9,
                updated = 0,
                skipped = 3,
                failed = 0,
                movedToRecycleBin = 2,
                warnings = listOf(
                    ImportWarning(location = "doc", reason = ImportWarningReason.HISTORY_IGNORED.code),
                    ImportWarning(location = "entry:4", reason = ImportWarningReason.DUPLICATE_ENTRY_SKIPPED.code)
                )
            )
        )
    }
}

private const val PROGRESS_SIZE = 20
private const val PROGRESS_GAP = 12
private const val ROW_GAP = 6
private const val WARNING_ITEM_GAP = 2
