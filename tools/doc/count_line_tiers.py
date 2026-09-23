#!/usr/bin/env python3
"""统计五模块 src/main 的 Kotlin 文件行数分档（ISSUE-P3-188 口径），并作 **fail-closed** 规模闸门。

用法：python tools/doc/count_line_tiers.py

退出码（工程卫生硬门禁，与 CI `hygiene-gate` 对齐，§281 立规）：
    0 = tier1(>500) = 0 且 tier2(400~500) ≤ TIER2_BUDGET
    1 = 越过任一闸门（逐条列出超标文件）

闸门口径（**只紧不松**）：
    - **tier1(>500) 恒为 0**——§280 收工线，不得回潮；
    - **tier2(400~500) 走棘轮**：当前预算 TIER2_BUDGET=37（§280 收工读数）。
      只允许**减少或持平**；拆分降档后应把本常量**下调**到新读数（同批或下一批）。
      新增大文件 / 把既有文件撑进 400~500 档即红，禁止「先超标再补登记」。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULES = ["app", "database", "crypto", "sync", "core"]
# §280 收工读数 37；棘轮只紧不松，降档后下调本常量（改此处须在批次文档留痕）。
TIER2_BUDGET = 37


def main() -> int:
    rows = []
    for m in MODULES:
        src = ROOT / m / "src" / "main"
        if not src.is_dir():
            continue
        for p in src.rglob("*.kt"):
            # 只取 src/main 下的真实源文件（排除 generated 目录）
            rel = p.relative_to(ROOT).as_posix()
            if "/build/" in f"/{rel}":
                continue
            rows.append((len(p.read_bytes().splitlines()), rel))

    t1 = sorted([r for r in rows if r[0] > 500], reverse=True)
    t2 = sorted([r for r in rows if 400 <= r[0] <= 500], reverse=True)

    print(f"files_scanned={len(rows)}")
    print(f"tier1(>500)={len(t1)}")
    for n, rel in t1:
        print(f"  {n:5d}  {rel}")
    print(f"tier2(400~500)={len(t2)}  budget={TIER2_BUDGET}")
    for n, rel in t2:
        print(f"  {n:5d}  {rel}")

    failed = False
    if t1:
        print(f"::error::tier1(>500)={len(t1)}，闸门要求恒为 0（§280 收工线）")
        failed = True
    if len(t2) > TIER2_BUDGET:
        print(
            f"::error::tier2(400~500)={len(t2)} > TIER2_BUDGET={TIER2_BUDGET}"
            "（棘轮只紧不松：拆分降档后应下调预算，禁止新增大文件）"
        )
        failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
