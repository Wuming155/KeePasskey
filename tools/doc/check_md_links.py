"""文档相对链接自检：`docs/**/*.md` 里的每个 `](xxx.md)` 必须指向真实存在的文件。

用法：
    python tools/doc/check_md_links.py            # 有断链则退出码 1 并逐条列出

背景（§180）：批次正文与分区文档里的相对路径有两类历史写法——以 `docs/` 为基准
（`architecture/…`、`resolved/batches/…`）与以本文件所在目录为基准（正确的 Markdown 口径）。
前者在 `docs/resolved/batches/` 与 `docs/security/` 下会全部解析失败。本脚本按 Markdown 的
真实语义（相对**本文件**）判定，并对「目标确实存在、只是相对前缀写错」的情况直接给出建议路径。
"""
import re
import sys
from pathlib import Path

DOCS = Path(__file__).resolve().parents[2] / "docs"
LINK = re.compile(r"\]\(([^)#\s]+\.md)\)")


def scannable(text: str) -> str:
    """剔除 ``` 围栏代码块与 `行内代码`——两者里的 markdown 片段是「示例 / 供粘贴到他处」的正文，
    其相对路径按目标文件的体例（或干脆是虚构示例）书写，用本文件目录去判定必然误报。"""
    out = []
    fenced = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        out.append("" if fenced else re.sub(r"`[^`]*`", "", line))
    return "\n".join(out)


def main() -> int:
    broken = []
    by_name: dict[str, Path] = {p.name: p for p in DOCS.rglob("*.md")}
    for md in sorted(DOCS.rglob("*.md")):
        for m in LINK.finditer(scannable(md.read_text(encoding="utf-8"))):
            tgt = m.group(1)
            if tgt.startswith(("http://", "https://")):
                continue
            if (md.parent / tgt).resolve().is_file():
                continue
            hint = by_name.get(Path(tgt).name)
            rel = f"  -> 实际位于 {hint.relative_to(DOCS)}" if hint else "  -> 目标不存在"
            broken.append(f"{md.relative_to(DOCS.parent)}: ({tgt}){rel}")
    if broken:
        print(f"BROKEN_MD_LINKS={len(broken)}")
        for b in broken:
            print("   ", b)
        return 1
    print("BROKEN_MD_LINKS=0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
