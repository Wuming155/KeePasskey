package com.keepasskey.app.autofill

/**
 * 结构树 webDomain 一致性策略（ISSUE-P2-383）。
 *
 * 混域判定自 [AutofillFieldScanner] 拆出（单一职责 + 行数门禁）。
 * 口径：同一填充结构中出现**多个不同**非空 webDomain ⇒ 整结构拒绝。
 *
 * **命名说明**：归属校验内核在同包 `AutofillWebDomainPolicy`（normalize / isTrustedBrowser /
 * attribute），本类**不得**与其重名——此处只做「混域是否拒绝」的一致性判定。
 */
internal object AutofillDomainConsistencyPolicy {

    /** 收集节点中全部非空 trim 后的 webDomain */
    fun collectDomains(nodes: List<ScanNode>): LinkedHashSet<String> {
        val domains = LinkedHashSet<String>()
        for (node in nodes) {
            val raw = node.webDomain?.trim().orEmpty()
            if (raw.isNotEmpty()) domains.add(raw)
        }
        return domains
    }

    /** 是否构成混域（≥2 个不同非空 domain） */
    fun isMixedDomain(nodes: List<ScanNode>): Boolean = collectDomains(nodes).size > 1

    /**
     * 解析可用于归属展示的单一 webDomain；混域返回 null（fail-closed）。
     */
    fun resolveSingleDomain(nodes: List<ScanNode>): String? {
        val domains = collectDomains(nodes)
        return if (domains.size > 1) null else domains.firstOrNull()
    }

    /** 首个非空 packageName（仅诊断用） */
    fun resolvePackageName(nodes: List<ScanNode>): String? {
        for (node in nodes) {
            if (node.packageName.isNotBlank()) return node.packageName.trim()
        }
        return null
    }
}
