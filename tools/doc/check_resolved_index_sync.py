#!/usr/bin/env python3
"""归档索引「分册 ↔ 批次正文」双向一致性自检（§218 立规，§282 扩重登/全量索引）。

## 为什么需要这个尺子

`docs/resolved/` 是**两层索引**：
- `RESOLVED_LOG.md`：全量批次索引（**逐批追加**，权威登记表）；
- `BATCH_*.md`（5 册）：**分册级**索引（分册 04 已封卷、05 自 §158 滚动）。

两层一旦不同步，就会出现「**批次正文存在但无法从任何分册跳达**」——与
`AGENTS.md` §4「索引纪律（`ISSUE-P3-81` 立规）」「**不得脱离索引与工作流入口**」直接冲突。
2026-09-19 实际发生过一次：`BATCH_158_PLUS.md` 止于 §202，而 `batches/` 已有 §203~§215
（13 批正文**存在但分册漏登**），且 `resolved/README.md` 的「当前最大编号」仍写 §202。
**当时无任何机检能发现**（`check_md_links.py` 只校验已登记链接**可达**，不校验**漏登**）
——故本条按 `ISSUE-P3-205` 的同一条教训「**散文承诺 ≠ 机检**」补机检。

## 判据（五条，任一不成立即退出码 1）

1. **批次正文 → 分册**：`docs/resolved/batches/*.md` 的文件名前缀编号，必须**全部**出现在某个
   `BATCH_*.md` 的 `| §N |` 行中（否则该批次无法从任何分册跳达）。
2. **分册 → 批次正文**（反向，防陈旧行）：分册登记的编号必须**有**对应正文文件，
   或落在显式豁免表中。
3. **`resolved/README.md` 的最大编号口径**必须等于批次正文的最大编号
   （否则「下一批次从 §N 起」会指错，重号风险）。
4. **分册登记不得重登**：同一 `§N` 在全部 `BATCH_*.md` 中至多一行
   （2026-09-23 实测 `BATCH_158_PLUS.md` 出现 3 条 `§280`，旧判据用 `setdefault`
   静默吞掉重登故恒绿——散文「双向一致」挡不住重登，必须机检）。
5. **批次正文 → 全量索引**：每份正文编号还须出现在 `docs/RESOLVED_LOG.md` 的
   `| §N |` 行中，且该表同样不得重登（§270 曾漏登 §269 的全量索引行，分册却已有）。

## 豁免（唯一权威定义，改此处须同时在批次文档说明理由）

- `§42` / `§43`：依 `docs/README.md`「`resolved/` — 历史批次归档」注记，
  **已归入 `docs/security/`**，故 `batches/` 下无正文文件。
  正文落位：`docs/security/退役审计承接-42-威胁建模与架构评估.md`、
  `docs/security/退役审计承接-43-安全整改方案与附录.md`。

## 反校（度量工具一律用已知值反校）

本条尺子上线时**先跑出红**（报「13 个批次正文未被任何分册登记」+ README 最大编号不一致），
回填 §203~§215 后转绿——**红→绿即本尺子的有效性证据**，不得只跑绿态。
§282 扩判据 4/5 时同法：先对现存 3 条重复 `§280` 跑红，去重后转绿。

用法：`python tools/doc/check_resolved_index_sync.py`
"""

import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
RESOLVED_DIR = REPO_ROOT / "docs" / "resolved"
BATCH_DIR = RESOLVED_DIR / "batches"
README = RESOLVED_DIR / "README.md"
RESOLVED_LOG = REPO_ROOT / "docs" / "RESOLVED_LOG.md"

# 见模块 docstring「豁免」节
EXEMPT_NO_BATCH_FILE = {42, 43}

# 分册行：`| §NN | 主题 | … |`
INDEX_ROW = re.compile(r"\|\s*§(\d+)\s*\|")
# 批次正文文件名前缀：`NNN-中文短名.md`
BATCH_FILE = re.compile(r"(\d+)-")
# README 口径：`当前最大为 **§NNN**`
README_MAX = re.compile(r"当前最大为\s*\*\*§(\d+)\*\*")


def batch_numbers() -> set[int]:
    numbers: set[int] = set()
    for path in BATCH_DIR.glob("*.md"):
        match = BATCH_FILE.match(path.name)
        if match:
            numbers.add(int(match.group(1)))
    return numbers


def scan_index_rows(path: pathlib.Path) -> dict[int, list[str]]:
    """编号 → 出现位置列表（`文件名:行号`）；用于重登检测。"""
    found: dict[int, list[str]] = {}
    if not path.is_file():
        return found
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = INDEX_ROW.match(line)
        if match:
            found.setdefault(int(match.group(1)), []).append(f"{path.name}:{line_no}")
    return found


def index_numbers() -> tuple[dict[int, str], list[str]]:
    """分册登记：编号 → 首次所在分册；并收集重登问题。"""
    places: dict[int, list[str]] = {}
    for path in sorted(RESOLVED_DIR.glob("BATCH_*.md")):
        for number, hits in scan_index_rows(path).items():
            places.setdefault(number, []).extend(hits)
    problems: list[str] = []
    registered: dict[int, str] = {}
    for number, hits in sorted(places.items()):
        if len(hits) > 1:
            problems.append(f"分册登记重复 §{number}（{len(hits)} 处）: {', '.join(hits)}")
        registered[number] = hits[0].split(":", 1)[0]
    return registered, problems


def resolved_log_numbers() -> tuple[set[int], list[str]]:
    """全量索引登记编号；并收集重登问题。"""
    places = scan_index_rows(RESOLVED_LOG)
    problems: list[str] = []
    for number, hits in sorted(places.items()):
        if len(hits) > 1:
            problems.append(f"RESOLVED_LOG 登记重复 §{number}（{len(hits)} 处）: {', '.join(hits)}")
    return set(places), problems


def readme_max_number() -> int | None:
    match = README_MAX.search(README.read_text(encoding="utf-8"))
    return int(match.group(1)) if match else None


def main() -> int:
    batches = batch_numbers()
    registered, index_problems = index_numbers()
    log_numbers, log_problems = resolved_log_numbers()
    problems: list[str] = [*index_problems, *log_problems]

    missing = sorted(batches - set(registered))
    if missing:
        problems.append(
            f"批次正文未被任何分册登记（{len(missing)} 个，无法从分册跳达）: {missing}"
        )

    missing_log = sorted(batches - log_numbers)
    if missing_log:
        problems.append(
            f"批次正文未被 RESOLVED_LOG 登记（{len(missing_log)} 个）: {missing_log}"
        )

    stray = sorted(
        number
        for number in registered
        if number not in batches and number not in EXEMPT_NO_BATCH_FILE
    )
    if stray:
        problems.append(f"分册登记的编号无对应批次正文（{len(stray)} 个，疑似陈旧行）: {stray}")

    stray_log = sorted(
        number
        for number in log_numbers
        if number not in batches and number not in EXEMPT_NO_BATCH_FILE
    )
    if stray_log:
        problems.append(
            f"RESOLVED_LOG 登记的编号无对应批次正文（{len(stray_log)} 个，疑似陈旧行）: {stray_log}"
        )

    readme_number = readme_max_number()
    if readme_number is None:
        problems.append("resolved/README.md 未找到「当前最大为 **§N**」口径")
    elif batches and readme_number != max(batches):
        problems.append(
            f"resolved/README.md 的最大编号 §{readme_number} 与批次正文最大值 §{max(batches)} 不一致"
        )

    if problems:
        print("RESOLVED_INDEX_SYNC=FAIL")
        for problem in problems:
            print(" - " + problem)
        return 1

    print(
        f"RESOLVED_INDEX_SYNC=OK（批次正文 {len(batches)} 份；分册登记 {len(registered)} 条；"
        f"全量索引 {len(log_numbers)} 条；最大 §{max(batches)}）"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
