#!/usr/bin/env bash
# 安全复核报告一致性扫描
#
# 用途：防止"先写更正节、再手工回改正文"造成的残留（已在第二轮 / 第三轮各失守一次）。
# 机制：每条「已撤销 / 已更正」的断言附其**禁用短语**；任何仍以肯定语气出现禁用短语的正文行即为残留。
#
# 豁免：更正记录本身（§12 / §15.2）会引用被撤销短语，故这些行被豁免 ——
#       判据是行内含豁免标记词，或处于 §12 / §15.2 章节区间内。
#
# 实现：单次 awk 扫描（勿在行循环内 spawn grep —— 会因进程创建量超时；教训见 §15.2(n)）。
#
# 用法：bash tools/audit/check_recheck_consistency.sh [报告路径]
# 退出码：0 = PASS / 1 = 有残留 / 2 = 报告不存在
#
# 【2026-09-15 路径更正，ISSUE-P3-129 ②】报告曾于提交 `523d0fd` 被整份删除（退役未登记），
# 致本脚本以默认路径调用时恒为 exit 2「报告不存在」、闸门停摆。现按 `docs/` 分区纪律将报告
# 恢复至 `docs/security/`，默认路径随之下沉；调用方若显式传路径，仍以传入值为准。

set -uo pipefail
REPORT="${1:-docs/security/SECURITY_RECHECK_2026-09.md}"

if [[ ! -f "$REPORT" ]]; then
  echo "FAIL: 报告不存在: $REPORT" >&2
  exit 2
fi

# 豁免标记词（**收窄版**）：只保留"明确在引用/更正旧结论"的标记。
# 【第三次审核后收紧】原词表含 `审核|我方|期望|应为|映射` 等高频词，在**全报告范围**生效，
# 使残留行只要含这些词即被静默跳过 —— 证据：§14 的残留（"`M = 4 GiB`"）最终由人工 grep 抓到、
# 而非本脚本。故删除高频词，仅保留下列强标记。
EXEMPT='已撤销|已并轨|已更正|原列|原写|初稿|我方先写|与事实相反|表述错误|高估 16|实为|该表述错误|不应|本表原写|前版漏'

# 禁用短语（每行一条；井号开头为注释；支持 POSIX ERE 正则——第三次审核后升级）
PHRASES_FILE="$(mktemp)"
trap 'rm -f "$PHRASES_FILE"' EXIT
cat > "$PHRASES_FILE" <<'PHRASES'
连 `Zeroizing` 都没有
2²⁴.{0,10}(×|且).{0,10}4 ?GiB
最坏工作量 ≈
8 条若字面实施
8 条会写坏库
一条根因、五个出口
污染 5 个出口
必须同时覆盖 4 处调用点
release 生成组件证明为
Zeroizing` 全路径（**除
小时级
PHRASES
n_phrases=$(grep -c . "$PHRASES_FILE")

awk -v exempt="$EXEMPT" -v phrases_file="$PHRASES_FILE" '
BEGIN {
  n = 0
  while ((getline p < phrases_file) > 0) {
    if (p != "") { phrases[++n] = p }
  }
  fail = 0; in_exempt = 0; lineno = 0
}
{
  lineno++
  if ($0 ~ /^## 15\.2/ || $0 ~ /^# 12\./) in_exempt = 1
  if ($0 ~ /^## 15\.3/ || $0 ~ /^# 13\./) in_exempt = 0
  if (in_exempt) next
  if ($0 ~ exempt) next
  for (i = 1; i <= n; i++) {
    if ($0 ~ phrases[i]) {
      fail++
      printf "  L%d  禁用短语「%s」\n", lineno, phrases[i]
      printf "         原文：%s\n", substr($0, 1, 110)
    }
  }
}
END {
  if (fail == 0) {
    printf "PASS: 无残留禁用短语（已扫描 %d 行，%d 条禁用短语）\n", lineno, n
    exit 0
  }
  printf "FAIL: 发现 %d 处残留 —— 请回改正文，或补豁免标记词\n", fail > "/dev/stderr"
  exit 1
}
' "$REPORT"
rc=$?
[[ $rc -eq 0 ]] || echo "（禁用短语清单：$n_phrases 条，见脚本内 heredoc）" >&2
exit $rc
