#!/usr/bin/env python3
"""产品裁决 / 工程限界两表的**统一检索入口**（B2 仪式瘦身，2026-10-09）。

用法：
    python tools/doc/find_decision.py <关键词> [关键词...]   # 全关键词命中（不分大小写）
    python tools/doc/find_decision.py --list                # 列出两表全部条目（浏览入口）
    python tools/doc/find_decision.py --selftest            # 解析口径反校

## 为什么需要本脚本（B2「PD / 限界表给统一检索入口而非逐条互链」）

`docs/architecture/产品裁决登记.md`（PD 条目）与 `docs/architecture/已知工程限界.md`（§ 分节）原以
**条目之间逐条交叉引用**互相指路（「见 `PD-45`」「见 §256」…），每新增一条都要回头维护若干条正文；
表体越长，维护成本越高而收益递减。本脚本把「定位一条裁决 / 限界」收敛为**单一入口**：
按关键词在两表内检索，返回 `文件 + 编号 + 标题 + 行号`，**不再需要逐条互链**。

判据是**纯文本解析**（不依赖表格格式之外的结构），不修改任何文档；退出码仅表达「是否命中」。

## 退出码

- `0` = 命中至少一条（或 `--list` / `--selftest` 成功）；
- `1` = 无命中（关键词未被任何条目包含）；
- `2` = 用法错误，或解析到的条目数为 0（防「两表被改名 / 结构漂移 ⇒ 本脚本假装无命中」）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRIES = [
    (ROOT / "docs" / "architecture" / "产品裁决登记.md", re.compile(r"^## (PD-\d+) (.+)$")),
    (ROOT / "docs" / "architecture" / "已知工程限界.md", re.compile(r"^#{2,3} ([\d.]+) (.+)$")),
]
# 条目正文的边界：同级或更高一级的标题即下一条条目
ANY_HEADING = re.compile(r"^#{1,3} ")


def parse_entries(path: Path, heading: re.Pattern):
    """返回 [(编号, 标题, 起始行号, 正文文本)]。标题行不匹配则跳过，不做任何猜测。"""
    if not path.is_file():
        return []
    lines = path.read_text(encoding="utf-8").splitlines()
    entries = []
    current = None
    for index, line in enumerate(lines, start=1):
        matched = heading.match(line)
        if matched:
            if current is not None:
                entries.append((*current[:3], "\n".join(current[3])))
            current = (matched.group(1), matched.group(2).strip(), index, [line])
        elif current is not None:
            if ANY_HEADING.match(line):
                entries.append((*current[:3], "\n".join(current[3])))
                current = None
            else:
                current[3].append(line)
    if current is not None:
        entries.append((*current[:3], "\n".join(current[3])))
    return entries


def collect():
    collected = []
    for path, heading in REGISTRIES:
        for entry_id, title, line_no, body in parse_entries(path, heading):
            collected.append((path, entry_id, title, line_no, body))
    return collected


def run_selftest() -> int:
    entries = collect()
    if not entries:
        print("::error::未解析到任何条目（两表结构与本脚本的解析口径已漂移）")
        return 2
    per_registry = {}
    for path, entry_id, _title, _line, _body in entries:
        per_registry.setdefault(path.name, []).append(entry_id)
    for name, ids in per_registry.items():
        print(f"{name}: {len(ids)} 条（首 {ids[0]} / 末 {ids[-1]}）")
    # 反校：已知必存在的锚点必须命中
    for keyword in ("zxcvbn", "口令强度"):
        hits = [e for e in entries if keyword.lower() in e[4].lower()]
        if not hits:
            print(f"::error::反校失败：关键词「{keyword}」应命中至少一条，实际 0")
            return 2
    print("selftest=PASS")
    return 0


def main(argv) -> int:
    if not argv:
        print(__doc__)
        return 2
    if argv[0] == "--selftest":
        return run_selftest()

    entries = collect()
    if not entries:
        print("::error::未解析到任何条目（两表结构与本脚本的解析口径已漂移）")
        return 2

    if argv[0] == "--list":
        for path, entry_id, title, line_no, _body in entries:
            print(f"{path.name}  {entry_id:<8} {title}  (:{line_no})")
        return 0

    keywords = [k.lower() for k in argv]
    hits = []
    for path, entry_id, title, line_no, body in entries:
        haystack = body.lower()
        if all(k in haystack for k in keywords):
            hits.append((path, entry_id, title, line_no))
    if not hits:
        print(f"no_match={len(keywords)} 关键词：{' '.join(argv)}")
        return 1
    for path, entry_id, title, line_no in hits:
        print(f"{path.name}  {entry_id:<8} {title}  (:{line_no})")
    print(f"matched={len(hits)} 关键词：{' '.join(argv)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
