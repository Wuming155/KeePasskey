#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""安全复核报告一致性扫描（原 `check_recheck_consistency.sh`，§283 移植为 Python 单源）。

## 用途

防止「先写更正节、再手工回改正文」造成的残留（已在第二轮 / 第三轮各失守一次）。
机制：每条「已撤销 / 已更正」的断言附其**禁用短语**；任何仍以肯定语气出现禁用短语的正文行即为残留。

## 豁免

更正记录本身（§12 / §15.2）会引用被撤销短语，故这些行被豁免——
判据是行内含豁免标记词，或处于 §12 / §15.2 章节区间内。

## 实现说明

原 bash/awk 版在 PowerShell 会话**不可直接跑**（本机甚至无 `bash`），故移植为本文件；
`.sh` 保留为调用本文件的薄封装，历史文档里的 `bash …/check_recheck_consistency.sh` 仍可执行。
禁用短语支持 **Python `re`**（与原 POSIX ERE 对本清单等价；清单内仅一条含 `.{0,10}`）。

用法：

    python tools/audit/check_recheck_consistency.py [报告路径]

退出码：0 = PASS / 1 = 有残留 / 2 = 报告不存在

【2026-09-15 路径更正，ISSUE-P3-129 ②】报告曾于提交 `523d0fd` 被整份删除（退役未登记），
致本脚本以默认路径调用时恒为 exit 2「报告不存在」、闸门停摆。现按 `docs/` 分区纪律将报告
恢复至 `docs/security/`，默认路径随之下沉；调用方若显式传路径，仍以传入值为准。
"""
from __future__ import annotations

import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_REPORT = REPO_ROOT / "docs" / "security" / "SECURITY_RECHECK_2026-09.md"

# 豁免标记词（**收窄版**）：只保留「明确在引用/更正旧结论」的标记。
# 【第三次审核后收紧】原词表含 `审核|我方|期望|应为|映射` 等高频词，在**全报告范围**生效，
# 使残留行只要含这些词即被静默跳过——证据：§14 的残留（`M = 4 GiB`）最终由人工 grep 抓到、
# 而非本脚本。故删除高频词，仅保留下列强标记。
EXEMPT = re.compile(
    r"已撤销|已并轨|已更正|原列|原写|初稿|我方先写|与事实相反|表述错误|"
    r"高估 16|实为|该表述错误|不应|本表原写|前版漏"
)

# 禁用短语（每行一条；Python `re`——`**` 等元字符按字面转义，
# 仅 `2²⁴…GiB` 一条保留 ERE 语义，与原 awk 清单对齐）。
# 【§283 移植踩坑】原 awk 把 `Zeroizing` 全路径（**除` 的 `**` 当字面量能跑；
# Python `re` 视 `**` 为 multiple repeat 直接抛错——故该条显式 `\*\*`。
PHRASES = [
    r"连 `Zeroizing` 都没有",
    r"2²⁴.{0,10}(×|且).{0,10}4 ?GiB",
    r"最坏工作量 ≈",
    r"8 条若字面实施",
    r"8 条会写坏库",
    r"一条根因、五个出口",
    r"污染 5 个出口",
    r"必须同时覆盖 4 处调用点",
    r"release 生成组件证明为",
    r"Zeroizing` 全路径（\*\*除",
    r"小时级",
]
_PHRASE_RES = [re.compile(p) for p in PHRASES]

# 章节豁免区间：进入 / 离开（与原 awk 一致，按行首标题匹配）
EXEMPT_ENTER = re.compile(r"^## 15\.2|^# 12\.")
EXEMPT_LEAVE = re.compile(r"^## 15\.3|^# 13\.")


def scan(report: pathlib.Path) -> tuple[int, list[str], int, int]:
    """返回 (fail 数, 报错行列表, 扫描行数, 禁用短语条数)。"""
    text = report.read_text(encoding="utf-8")
    lines = text.splitlines()
    findings: list[str] = []
    in_exempt = 0
    fail = 0
    for lineno, line in enumerate(lines, 1):
        if EXEMPT_ENTER.search(line):
            in_exempt = 1
        if EXEMPT_LEAVE.search(line):
            in_exempt = 0
        if in_exempt:
            continue
        if EXEMPT.search(line):
            continue
        for phrase, phrase_re in zip(PHRASES, _PHRASE_RES):
            if phrase_re.search(line):
                fail += 1
                findings.append(
                    f"  L{lineno}  禁用短语「{phrase}」\n"
                    f"         原文：{line[:110]}"
                )
    return fail, findings, len(lines), len(PHRASES)


def main(argv: list[str]) -> int:
    report = pathlib.Path(argv[1]) if len(argv) > 1 else DEFAULT_REPORT
    if not report.is_file():
        print(f"FAIL: 报告不存在: {report}", file=sys.stderr)
        return 2

    fail, findings, lineno, n_phrases = scan(report)
    if fail == 0:
        print(f"PASS: 无残留禁用短语（已扫描 {lineno} 行，{n_phrases} 条禁用短语）")
        return 0

    print(f"FAIL: 发现 {fail} 处残留 —— 请回改正文，或补豁免标记词", file=sys.stderr)
    for item in findings:
        print(item)
    print(f"（禁用短语清单：{n_phrases} 条，见脚本内 PHRASES）", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
