"""检查维护文档中的本地链接；存在断链时打印位置并退出 1。"""

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def main() -> int:
    documents = [ROOT / "README.md", ROOT / "AGENTS.md"]
    documents.extend((ROOT / "docs").rglob("*.md"))
    documents.extend((ROOT / ".codebuddy/rules").glob("*.md"))
    broken = []
    for document in sorted(documents):
        fenced = False
        for line_no, line in enumerate(document.read_text(encoding="utf-8").splitlines(), 1):
            if line.lstrip().startswith("```"):
                fenced = not fenced
                continue
            if fenced:
                continue
            for target in LINK.findall(line):
                target = target.strip().split(' "', 1)[0].strip("<>")
                url = urlsplit(target)
                if url.scheme or not url.path:
                    continue
                if not (document.parent / unquote(url.path)).exists():
                    broken.append(f"{document.relative_to(ROOT)}:{line_no}: {target}")
    for item in broken:
        print(item)
    print(f"BROKEN_MD_LINKS={len(broken)}")
    return int(bool(broken))


if __name__ == "__main__":
    sys.exit(main())
