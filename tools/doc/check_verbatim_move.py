"""逐字搬移核对（ISSUE-P3-188 段落拆分专用，反向口径）。

用法：
    python tools/doc/check_verbatim_move.py <原文件> <搬入后的本体> [搬入后的其它文件...]

判据：把「去掉缩进后有内容的行」按**出现次数**建多重集，原文件的每一种行必须在
（本体 ∪ 其它文件）里至少出现同样次数——**丢行**与**顺手改行**都会被同一判据抓到。
纯符号行（`{` / `}` / `},` 等）不参与比对；报告里的「新侧独有行」需人工确认全是
函数签名 / 形参 / 调用点 / 注释，而不是新增的绘制或逻辑。
"""
import sys
from collections import Counter
from pathlib import Path

PUNCT = {"{", "}", "},", ")", "(", ") {", ") {", ""}


def counts(path: Path) -> Counter:
    return Counter(l.strip() for l in path.read_text(encoding="utf-8").splitlines() if l.strip() not in PUNCT)


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    orig = counts(Path(argv[0]))
    now: Counter = Counter()
    for p in argv[1:]:
        now += counts(Path(p))

    missing = {k: (v, now[k]) for k, v in orig.items() if now[k] < v}
    new_only = sorted(k for k in now if k not in orig)

    print(f"orig_content_kinds={len(orig)}  MISSING_KINDS={len(missing)}")
    for k, (need, have) in sorted(missing.items()):
        print(f"   - need {need} have {have}: {k}")
    print(f"NEW_ONLY_KINDS={len(new_only)}")
    for k in new_only:
        print(f"   + {k}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
