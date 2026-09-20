package com.keepasskey.app.autofill

import android.service.autofill.FillContext

/**
 * 自动填充保存凭据提取模型（ISSUE-P1-224）。
 */
internal data class ExtractedSaveCredentials(
    val username: String,
    val password: String,
    val webDomain: String?
)

/**
 * 自动填充保存请求表单字段提取器（ISSUE-P1-224）。
 *
 * 职责单一：倒序遍历全部 FillContext 提取有效密码与用户名，并在推荐节点为空时
 * 搜寻同页面任意带有效文本的特征输入框做兜底。
 */
internal object AutofillSaveExtractor {

    suspend fun extract(
        contexts: List<FillContext>,
        callingPkg: String,
        isOverrideNoAutofillEnabled: Boolean,
        originResolver: AutofillOriginResolver
    ): ExtractedSaveCredentials? {
        for (ctx in contexts.reversed()) {
            val structure = ctx.structure
            val scanned = AutofillStructureScanner.scan(structure, callingPkg)
            val parsedNodes = scanned.viewNodes

            val scanResult = AutofillFieldScanner.scan(
                scanned.scanNodes,
                respectImportantForAutofill = !isOverrideNoAutofillEnabled
            )
            var username = scanResult.usernameId?.toIntOrNull()?.let { parsedNodes.getOrNull(it)?.text }.orEmpty()
            var password = scanResult.passwordId?.toIntOrNull()?.let { parsedNodes.getOrNull(it)?.text }.orEmpty()

            // 兜底：若推荐密码节点为空，搜寻同页面任意带有效文本的密码特征节点
            if (password.isBlank()) {
                val candidate = parsedNodes.firstOrNull { node ->
                    AutofillFieldScanner.isPasswordInputType(node.inputType) && node.text.isNotBlank()
                } ?: parsedNodes.firstOrNull { node ->
                    node.autofillHints.any { AutofillFieldScanner.isPasswordHint(it) } && node.text.isNotBlank()
                }
                if (candidate != null) {
                    password = candidate.text
                }
            }

            // 兜底：若用户名为空，搜寻同页面任意带有效文本的账号特征节点
            if (username.isBlank()) {
                val candidate = parsedNodes.firstOrNull { node ->
                    AutofillFieldScanner.isAccountInputType(node.inputType) && node.text.isNotBlank()
                } ?: parsedNodes.firstOrNull { node ->
                    node.autofillHints.any { AutofillFieldScanner.isUsernameHint(it) } && node.text.isNotBlank()
                }
                if (candidate != null) {
                    username = candidate.text
                }
            }

            if (password.isNotBlank()) {
                val usableWebDomain = originResolver.resolveUsableWebDomain(callingPkg, scanResult.webDomain)
                return ExtractedSaveCredentials(
                    username = username,
                    password = password,
                    webDomain = usableWebDomain
                )
            }
        }
        return null
    }
}
