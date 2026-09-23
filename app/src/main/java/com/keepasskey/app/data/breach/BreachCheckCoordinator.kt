package com.keepasskey.app.data.breach

import com.keepasskey.core.model.KdbxEntry
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 已泄露密码比对协调器（TASK-47）。
 *
 * 职责：把全库条目密码转换为 k-匿名查询（SHA-1 前缀），按前缀聚合去重后向 [BreachRangeClient]
 * 发起查询，并在本地完成后缀比对。**调用方（健康度控制器）负责按用户开关决定是否调用本协调器**，
 * 本类不读设置——保持「网络行为由显式开关门控」的可测性与可审计性。
 *
 * 外联预算（ISSUE-P3-294 AC③ 取值依据）：
 * - **前缀去重**：按 SHA-1 前缀 `LinkedHashMap` 分组，同前缀仅 1 次网络请求——k-匿名协议以
 *   5 位前缀为查询粒度，相同前缀共享同一响应；N 条重复口令的外联压成 1 次。
 * - **请求数上限**：隐式上界 = 唯一前缀数 ≤ 非空口令条目数，**不设人工硬顶**。依据：
 *   ① 扫描由用户开关显式触发、一次性；② 单请求超时已在 `BreachCheckModule` 收口；
 *   ③ 请求间隙有取消检查（见 [check]），用户可随时退出；④ HIBP range API 免费且
 *   官方无强制配额。若日后要硬顶，属独立取舍、须另行裁决。
 *
 * 敏感数据处理：密码明文字节仅在本地短暂驻留用于计算 SHA-1，任何路径（含异常）都在 `finally`
 * 中清零；网络链路只出现 5 位十六进制前缀。
 *
 * 失败语义：查询异常一律向上抛出 [BreachCheckException]，由调用方转为
 * [BreachCheckStatus.FAILED] 并如实上浮——绝不静默回落为「未泄露」。
 * 协程取消走 `CancellationException` 正常传播（**不是** FAILED）。
 *
 * 构造由 [com.keepasskey.app.di.BreachCheckModule] 显式装配（未标注 `@Inject`）。
 */
class BreachCheckCoordinator(
    private val rangeClient: BreachRangeClient
) {

    /**
     * 执行全库泄露比对。
     *
     * 请求间隙检查协程取消（[ensureActive]）：`BreachRangeClient.queryRange` 的在途
     * `execute()` 不可中断，但**间隙**可检——用户退出后不再发起后续外联。
     *
     * @param entries 参与比对的条目（密码为空的条目跳过，不计入泄露）
     * @return [BreachCheckOutcome]；无可用密码时返回 [BreachCheckStatus.CLEAN]（确实无从比对）
     */
    suspend fun check(entries: List<KdbxEntry>): BreachCheckOutcome {
        val candidatesByPrefix = collectCandidates(entries)
        if (candidatesByPrefix.isEmpty()) return BreachCheckOutcome(BreachCheckStatus.CLEAN)

        val breachedEntryIds = LinkedHashSet<String>()
        for ((prefix, candidates) in candidatesByPrefix) {
            coroutineContext.ensureActive()
            val breachedSuffixes = rangeClient.queryRange(prefix)
            if (breachedSuffixes.isEmpty()) continue
            for (candidate in candidates) {
                if (candidate.suffix in breachedSuffixes) {
                    breachedEntryIds.add(candidate.entryId)
                }
            }
        }

        return if (breachedEntryIds.isEmpty()) {
            BreachCheckOutcome(BreachCheckStatus.CLEAN)
        } else {
            BreachCheckOutcome(BreachCheckStatus.BREACHED, breachedEntryIds)
        }
    }

    /**
     * 本地计算各条目密码的 SHA-1，按 5 位前缀聚合——同前缀只产生一次网络查询。
     */
    private fun collectCandidates(entries: List<KdbxEntry>): Map<String, List<Candidate>> {
        val grouped = LinkedHashMap<String, MutableList<Candidate>>()
        for (entry in entries) {
            val password = entry.password
            if (password == null || password.length == 0) continue
            val passwordBytes = password.readUtf8()
            try {
                if (passwordBytes.isEmpty()) continue
                // ISSUE-P3-102：字节态计算 + 就地拆分——完整 40 位摘要不物化为 String，
                // 摘要字节由 BreachHasher 内部 finally 清零（密码明文字节由本方法 finally 清零）
                val (prefix, suffix) = BreachHasher.splitPrefixSuffixOfSha1(passwordBytes)
                grouped.getOrPut(prefix) { mutableListOf() }.add(
                    Candidate(entryId = entry.id.toHexString(), suffix = suffix)
                )
            } finally {
                passwordBytes.fill(0)
            }
        }
        return grouped
    }

    private data class Candidate(val entryId: String, val suffix: String)
}
