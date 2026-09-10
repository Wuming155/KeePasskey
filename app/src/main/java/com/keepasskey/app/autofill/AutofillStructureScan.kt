package com.keepasskey.app.autofill

import android.app.assist.AssistStructure
import android.view.autofill.AutofillId

/**
 * 结构树遍历解析出的自动填充节点。
 *
 * 自 [KeePasskeyAutofillService] 内嵌的 `ParsedViewNode` 原样抽出（字段与语义逐字保留）。
 */
internal data class ParsedAutofillNode(
    val autofillId: AutofillId,
    val autofillHints: List<String>,
    val inputType: Int,
    val isFocused: Boolean,
    val htmlName: String?,
    /** ISSUE-P3-39：邻近 label / hint 文本（用于多语言兜底识别） */
    val label: String?,
    val webDomain: String?,
    /** ISSUE-P3-39：节点可见性（不可见账号框不参与，不可见密码框仍准入） */
    val isVisible: Boolean,
    /** ISSUE-P3-43：页面是否允许对该节点自动填充（`importantForAutofill`） */
    val importantForAutofill: Boolean,
    val text: String
)

/**
 * 结构树扫描结果：原始解析节点与投影扫描节点按同一顺序一一对应。
 */
internal data class ScannedStructure(
    val viewNodes: List<ParsedAutofillNode>,
    val scanNodes: List<ScanNode>
)

/**
 * AssistStructure 遍历与节点解析（自 [KeePasskeyAutofillService] 原样抽出）。
 *
 * 纯结构搬运：遍历顺序、children 递归顺序与 `parsedNodes`/`scanNodes` 的构造逻辑
 * 与原先内联实现逐字一致，不改变任何判定语义。
 */
internal object AutofillStructureScanner {

    /**
     * 遍历 [structure]，按发现顺序同时产出原始解析节点与投影扫描节点。
     *
     * @param callingPackage 调用应用包名，原样写入每个 [ScanNode] 的 `packageName`
     */
    fun scan(structure: AssistStructure, callingPackage: String): ScannedStructure {
        val parsedNodes = mutableListOf<ParsedAutofillNode>()
        val scanNodes = mutableListOf<ScanNode>()

        traverseStructure(structure) { node ->
            val indexStr = parsedNodes.size.toString()
            parsedNodes.add(node)
            scanNodes.add(
                ScanNode(
                    id = indexStr,
                    autofillHints = node.autofillHints,
                    inputType = node.inputType,
                    isFocused = node.isFocused,
                    htmlName = node.htmlName,
                    label = node.label,
                    webDomain = node.webDomain,
                    packageName = callingPackage,
                    isVisible = node.isVisible,
                    importantForAutofill = node.importantForAutofill
                )
            )
        }

        return ScannedStructure(viewNodes = parsedNodes, scanNodes = scanNodes)
    }

    private fun traverseStructure(
        structure: AssistStructure,
        onNode: (ParsedAutofillNode) -> Unit
    ) {
        val windowNodes = (0 until structure.windowNodeCount).map { structure.getWindowNodeAt(it) }
        for (window in windowNodes) {
            val root = window.rootViewNode ?: continue
            traverseViewNode(root, onNode)
        }
    }

    private fun traverseViewNode(
        node: AssistStructure.ViewNode,
        onNode: (ParsedAutofillNode) -> Unit
    ) {
        val autofillId = node.autofillId
        if (autofillId != null) {
            val hints = node.autofillHints?.toList().orEmpty()
            val textVal = node.autofillValue?.textValue?.toString() ?: node.text?.toString().orEmpty()
            val htmlName = node.idEntry ?: node.hint
            onNode(
                ParsedAutofillNode(
                    autofillId = autofillId,
                    autofillHints = hints,
                    inputType = node.inputType,
                    isFocused = node.isFocused,
                    htmlName = htmlName,
                    label = node.hint,
                    webDomain = node.webDomain,
                    isVisible = node.visibility == android.view.View.VISIBLE,
                    importantForAutofill = isImportantForAutofill(node),
                    text = textVal
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            traverseViewNode(child, onNode)
        }
    }

    /**
     * ISSUE-P3-43：页面是否允许对该节点自动填充。
     *
     * `IMPORTANT_FOR_AUTOFILL_NO` 与 `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`
     * 视为页面显式禁止填充；其余（AUTO / YES / YES_EXCLUDE_DESCENDANTS）视为允许。
     * 是否被跳过取决于扫描参数 `respectImportantForAutofill`（由 `overrideNoAutofill` 开关决定）。
     */
    private fun isImportantForAutofill(node: AssistStructure.ViewNode): Boolean {
        val important = node.importantForAutofill
        return important != android.view.View.IMPORTANT_FOR_AUTOFILL_NO &&
                important != android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
}
