"""文档相对链接自检：`docs/**/*.md` 里的每个 `](xxx)` 必须指向真实存在的**文件**。

用法：
    python tools/doc/check_md_links.py            # 有断链则退出码 1 并逐条列出

背景（§180）：批次正文与分区文档里的相对路径有两类历史写法——以 `docs/` 为基准
（`architecture/…`、`resolved/batches/…`）与以本文件所在目录为基准（正确的 Markdown 口径）。
前者在 `docs/resolved/batches/` 与 `docs/security/` 下会全部解析失败。本脚本按 Markdown 的
真实语义（相对**本文件**）判定，并对「目标确实存在、只是相对前缀写错」的情况直接给出建议路径。

判据扩展（§194）：**光靠「目标必须以 `.md` 结尾」的正则会漏**。索引行生成脚本曾把行尾链接截成
`](resolved/batches/)`——被砍掉的恰好是 `.md` 文件名，旧正则一条都不认识，自检当场报
`BROKEN_MD_LINKS=0`（同类截断 §179 也发生过一次）。现跑两遍：
* **存在性**（`LINK`，跑在剔掉围栏与行内代码的文本上）：目标解析不到任何存在的文件 / 目录 ⇒ 断链；
* **截断形态**（`TRUNC`，跑在**只剔围栏**的文本上）：反引号点名的 `` `xxx.md` `` 链接文字，
  其后目标却以 `/` 结尾 ⇒ 断链。不直接把「目标是目录」判红，是因为文档地图里有**故意**指向分区的
  目录链接（`references/`、`batches/`、`../security/`），一律判红属误报。

写作约定：正文里**举例**说明断链形态时，不要写成真的 markdown 链接——反引号挡不住 `TRUNC`
（嵌套反引号在 Markdown 里也不生效，§194 的缺陷记录就因此被自己的脚本报红一次）。
把被截断的目标单独放行内代码、不接 `](` 即可。
"""
import re
import sys
from pathlib import Path

DOCS = Path(__file__).resolve().parents[2] / "docs"
LINK = re.compile(r"\[([^\]]*)\]\(([^)#\s]+)\)")
TRUNC = re.compile(r"\[`([^`\]]+\.md)`\]\(([^)]+/)\)")


def is_broken(target: str, md: Path) -> bool:
    """目标既不是存在的文件、也不是存在的目录 ⇒ 断链。"""
    if target.startswith(("http://", "https://")):
        return False
    resolved = (md.parent / target).resolve()
    return not (resolved.is_file() or resolved.is_dir())


def without_fences(text: str) -> str:
    """只剔除 ``` 围栏代码块（其内是「供粘贴到他处」的示例正文，路径体例未必按本文件目录）。"""
    out = []
    fenced = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        out.append("" if fenced else line)
    return "\n".join(out)


def scannable(text: str) -> str:
    """在去围栏之上再剔行内代码——其中的 markdown 片段是示例文字，用本文件目录去判定必然误报。"""
    return "\n".join(re.sub(r"`[^`]*`", "", line) for line in without_fences(text).splitlines())


def main() -> int:
    broken = []
    by_name: dict[str, Path] = {p.name: p for p in DOCS.rglob("*.md")}
    for md in sorted(DOCS.rglob("*.md")):
        raw = md.read_text(encoding="utf-8")
        for m in LINK.finditer(scannable(raw)):
            label, tgt = m.group(1), m.group(2)
            if not is_broken(tgt, md):
                continue
            hint = by_name.get(Path(tgt).name) or by_name.get(label.replace('`', '').strip())
            rel = f"  -> 实际位于 {hint.relative_to(DOCS)}" if hint else "  -> 目标不存在（目录写错 / 缺文件）"
            broken.append(f"{md.relative_to(DOCS.parent)}: [{label}]({tgt}){rel}")
        for m in TRUNC.finditer(without_fences(raw)):
            name, tgt = m.group(1), m.group(2)
            hint = by_name.get(name)
            rel = f"  -> 链接被截断，目标应为 {hint.relative_to(DOCS)}" if hint else "  -> 链接被截断且目标不存在"
            broken.append(f"{md.relative_to(DOCS.parent)}: [`{name}`]({tgt}){rel}")
    if broken:
        print(f"BROKEN_MD_LINKS={len(broken)}")
        for b in broken:
            print("   ", b)
        return 1
    print("BROKEN_MD_LINKS=0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
