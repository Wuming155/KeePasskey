"""归档面不可回退机检（`ISSUE-P2-546` 立规；`ISSUE-P2-545` 的机检化）。

## 为何存在

2026-10-08 实测事故：提交 `bc7f187a` 把上一批 `33a8b86b`（§477）**整树回退**——六条整改、5 份新增用例、
第 11 道机检、批次正文、`PD-79` / `PD-80` 与两侧索引行**一并消失**，而**现有全部机检仍判绿**：
`check_resolved_index_sync.py` 的口径是「四份索引**互相自洽**」，旧态恰恰自洽 ⇒ **自洽的旧态不可分辨**。
与 `ISSUE-P3-305`「闸门存在 ≠ 闸门被执行」同型——**闸门被静默拆除**同样无人拦。

## 判据（两条，任一命中即退出码 1）

- **RULE-A（索引行不得消失）**：基线树 `docs/RESOLVED_LOG.md` 中的每个 `| §NN |` 批次号，
  在现树同文件中**必须仍存在**。
- **RULE-B（批次正文不得消失）**：基线树 `docs/resolved/batches/` 下的每个 `.md` 文件名，
  在现树**必须仍存在**（即使同时把索引行一并回退，本条仍能命中）。

归档是**只增不减**的（AGENTS.md 规则 6.3「编号续用不复用」）⇒ 任何删除都属异常，须显式说明后
再动本判据，不得「顺手清理」。

## 基线解析（**不得**解析不到就当绿）

`--base` 默认 `HEAD~1`：与其父提交比较 ⇒ 任何**单笔**删除都会在其自身那一笔被拦下。
解析不到基线（浅克隆 / 首个提交 / 非 git 目录）⇒ 退出码 **2**，且打印原因；
CI 侧 `hygiene-gate` 的 checkout 已配 `fetch-depth: 0`（浅克隆下 `HEAD~1` 不存在，会是**假绿**来源）。

## 口径边界（**不得**据其绿推定「归档未被改动」）

① 只比**集合存在性**，不比行内容——正文被改写 / 稀释 / 张冠李戴**看不见**；
② 只覆盖 `docs/RESOLVED_LOG.md` 与 `docs/resolved/batches/`（`BATCH_*.md` 分册与
`resolved/README.md` 编号行不在本判据内）；
③ 门禁脚本集合**有意**不纳入：脚本可被有意退役，纳入会产生假红（本判据只钉**归档面**）。

用法：`python tools/doc/check_archive_monotonicity.py [--base <rev>] [--head <rev>] [--root 目录] [--selftest]`；
`--head` 指另一 rev（默认读工作区）——仅用于**判据反校**（如 `--base 33a8b86b --head bc7f187a` 应报红）。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

INDEX_PATH = "docs/RESOLVED_LOG.md"
BATCHES_DIR = "docs/resolved/batches"

# 索引行的批次号：`| §477 | 读路径判据收口… |`
INDEX_ROW_RE = re.compile(r"^\|\s*(§\d+)\s*\|")


def parse_index_rows(text: str) -> set[str]:
    """从索引表文本中取出全部 `§NN` 批次号。"""
    return {m.group(1) for line in text.splitlines() if (m := INDEX_ROW_RE.match(line))}


def _git(root: pathlib.Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *args], cwd=str(root), capture_output=True, text=False
    )


def _git_ok(root: pathlib.Path, *args: str) -> str:
    proc = _git(root, *args)
    if proc.returncode != 0:
        raise RuntimeError(
            "git %s 失败：%s" % (" ".join(args), proc.stderr.decode("utf-8", "replace").strip())
        )
    return proc.stdout.decode("utf-8", "replace")


def snapshot_from_disk(root: pathlib.Path) -> tuple[set[str], set[str]]:
    """现树（工作区）快照 ⇒ (索引批次号集合, 批次正文文件名集合)。"""
    rows = parse_index_rows((root / INDEX_PATH).read_text(encoding="utf-8"))
    batches = {p.name for p in (root / BATCHES_DIR).glob("*.md")}
    return rows, batches


def snapshot_from_rev(root: pathlib.Path, rev: str) -> tuple[set[str], set[str]]:
    """指定 rev 快照（经 `git show` / `git ls-tree`，不检出工作区）。"""
    rows = parse_index_rows(_git_ok(root, "show", "%s:%s" % (rev, INDEX_PATH)))
    # `-z`：非 ASCII 路径默认会被 git 加引号转义（`core.quotepath`），NUL 分隔则原样输出。
    listing = _git_ok(root, "ls-tree", "-r", "--name-only", "-z", rev, "--", BATCHES_DIR)
    batches = {
        pathlib.PurePosixPath(line).name
        for line in listing.split("\0")
        if line.endswith(".md")
    }
    return rows, batches


def compare(base: tuple[set[str], set[str]], head: tuple[set[str], set[str]]) -> list[str]:
    """返回违规清单（空 ⇒ 通过）。纯函数，供 `--selftest` 直接反校。"""
    base_rows, base_batches = base
    head_rows, head_batches = head
    hits = ["RULE-A 索引批次号消失：%s" % row for row in sorted(base_rows - head_rows, key=_row_key)]
    hits += ["RULE-B 批次正文消失：%s" % name for name in sorted(base_batches - head_batches)]
    return hits


def _row_key(row: str) -> int:
    return int(row.lstrip("§"))


def selftest() -> int:
    """内嵌正 / 反样本反校判据（不依赖 git）。"""
    good = compare(({"§476", "§477", "§478"}, {"476-x.md", "477-y.md"}),
                   ({"§476", "§477", "§478", "§479"}, {"476-x.md", "477-y.md", "479-z.md"}))
    bad = compare(({"§476", "§477", "§478"}, {"476-x.md", "477-y.md"}),
                  ({"§476", "§478"}, {"476-x.md"}))
    ok = not good and len(bad) == 2 and bad[0].startswith("RULE-A") and bad[1].startswith("RULE-B")
    print("[selftest] 绿样本命中=%d（须 0）；红样本命中=%d（须 2：RULE-A 索引行 + RULE-B 正文各 1）" % (len(good), len(bad)))
    for hit in bad:
        print("  [selftest] " + hit)
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print("[selftest] PASS：判据可分辨「只增不减」与「索引行 / 正文消失」")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="归档面不可回退机检（ISSUE-P2-546）")
    parser.add_argument("--base", default="HEAD~1", help="基线 rev（默认 HEAD~1）")
    parser.add_argument("--head", default=None, help="现树 rev（默认读工作区；仅用于判据反校）")
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    try:
        base = snapshot_from_rev(root, args.base)
    except (RuntimeError, OSError) as exc:
        print("[check_archive_monotonicity] 基线不可解析（%s）：%s" % (args.base, exc))
        print("[check_archive_monotonicity] 退出码 2：不得当绿（浅克隆请用 fetch-depth: 0，或显式 --base）")
        return 2
    try:
        if args.head:
            head = snapshot_from_rev(root, args.head)
            head_label = args.head
        else:
            head = snapshot_from_disk(root)
            head_label = "工作区"
    except (RuntimeError, OSError) as exc:
        print("[check_archive_monotonicity] 现树不可读：%s" % exc)
        return 2

    hits = compare(base, head)
    print(
        "[check_archive_monotonicity] 基线 %s：索引行 %d 个 / 批次正文 %d 份；现树 %s：%d / %d；缺失 %d 处"
        % (args.base, len(base[0]), len(base[1]), head_label, len(head[0]), len(head[1]), len(hits))
    )
    for hit in hits:
        print("  HIT " + hit)
    if hits:
        print(
            "[check_archive_monotonicity] FAIL：归档只增不减——已登记的批次号 / 正文不得消失"
            "（若确为有意退役，须先完成结论分流并显式改判据，不得静默删除）"
        )
        return 1
    print("[check_archive_monotonicity] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
