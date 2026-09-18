#!/usr/bin/env python3
"""统计五模块 src/main 的 Kotlin 文件行数分档（ISSUE-P3-188 口径）。

用法：python tools/doc/count_line_tiers.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULES = ["app", "database", "crypto", "sync", "core"]

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
print(f"tier2(400~500)={len(t2)}")
for n, rel in t2:
    print(f"  {n:5d}  {rel}")
