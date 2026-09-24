#!/usr/bin/env python3
"""门禁读数单点采集（`ISSUE-P3-305` AC④ 防回潮机制）。

用法：`python tools/doc/gate_readings.py`

## 为什么需要本脚本

§285 把七条自研机检挂成 CI `hygiene-gate`（fail-closed）之后，§286~§299 的连续整改仍让
`tier1(>500)` 与 `functions_ge_100` **双双回潮而无人察觉**——历史批次一律以
「`gradlew test` 全绿」结案，**从未逐条复核门禁面读数**（`ISSUE-P3-305` 的根因）。
本脚本把「CI 到底跑哪几条」与「本地一次跑完、逐条打印读数」收敛为**单一入口**：
清单**直接从 `.github/workflows/build.yml` 的 `hygiene-gate` 段落解析**，不另抄一份，
故本地读数与 CI 不可能漂移（改 CI 即自动生效；解析结果为空则报红，不会静默空转）。

## 退出码（fail-closed）

- `0` = 解析到的每条机检均 EXIT 0；
- `1` = 任一条非 0，或 YAML 中解析不到任何命令（防「命令清单消失 ⇒ 本脚本假装全绿」）。

## 批次结案纪律（AC④）

批次文档 §3 必须**原样粘贴本脚本输出的读数块**（含逐条 EXIT 与读数行），
**不得**只写「七条机检 EXIT 0」——「闸门存在 ≠ 闸门被执行」正是本条要堵的缺口。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"

# hygiene-gate 段落内的机检命令（形如 `python3 tools/doc/xxx.py`）
COMMAND = re.compile(r"^\s*python3\s+(tools/\S+\.py)\s*$", re.M)
JOB_START = re.compile(r"^  hygiene-gate:\s*$", re.M)
NEXT_JOB = re.compile(r"^  \S", re.M)
# 逐条目清单行（缩进 ≥2 空格的明细）不构成读数，剔除后剩余即「读数行」
LISTING_LINE = re.compile(r"^\s{2,}\S")
# 扫描规模前缀（`files_scanned=NNN  `）本身不是读数，但同行可能还带真读数（如 `functions_ge_100=0`）
SCANNED_PREFIX = re.compile(r"^files_scanned=\d+\s*")


def hygiene_commands(text: str) -> list[str]:
    """解析 `hygiene-gate` job 内的机检脚本相对路径（按出现顺序）。"""
    start = JOB_START.search(text)
    if not start:
        return []
    tail = text[start.end():]
    nxt = NEXT_JOB.search(tail)
    section = tail[: nxt.start()] if nxt else tail
    return COMMAND.findall(section)


def readings(stdout: str) -> str:
    """从机检 stdout 提取读数行（剔除逐条目清单与规模前缀）。"""
    keep = []
    for line in stdout.splitlines():
        if not line.strip() or LISTING_LINE.match(line):
            continue
        stripped = SCANNED_PREFIX.sub("", line.strip())
        if stripped:
            keep.append(stripped)
    return "  ".join(keep) if keep else "(无读数行)"


def main() -> int:
    if not WORKFLOW.is_file():
        print(f"::error::找不到 CI 工作流：{WORKFLOW}")
        return 1
    commands = hygiene_commands(WORKFLOW.read_text(encoding="utf-8"))
    if not commands:
        print("::error::未能从 hygiene-gate 段落解析到任何 python3 tools/... 机检命令")
        return 1

    print("=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===")
    failed = []
    for i, rel in enumerate(commands, start=1):
        proc = subprocess.run(
            [sys.executable, rel],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        lines = (proc.stdout or "").splitlines() + (proc.stderr or "").splitlines()
        summary = readings("\n".join(lines))
        print(f"[{i}/{len(commands)}] {rel:<44} EXIT {proc.returncode}  | {summary}")
        if proc.returncode != 0:
            failed.append(rel)

    total = len(commands)
    if failed:
        print(f"=== 汇总：{total - len(failed)}/{total} PASS，红：{'、'.join(failed)} ===")
        return 1
    print(f"=== 汇总：{total}/{total} PASS ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
