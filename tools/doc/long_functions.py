"""超长函数复核（ISSUE-P3-188 第 3 目口径，五模块 `src/main`）。

用法：
    python tools/doc/long_functions.py [最小行数，默认 100]

三条判据（§175 立、§178~§181 补，缺一即误报 / 漏报）：
1. **括号配平**：从声明行起累加 `{`/`}`，回到 0 的那一行为函数体尾；
2. **先去字符串字面量、再去注释**：只去注释会把 `Text("content://... 或 /path")` 里的 `//…`
   当行注释吃掉，连带删掉该行的引号与右括号 ⇒ 配平永久失配（§178 实测：226 行的函数被漏报为 0）；
3. **跳过单表达式函数**：`fun f(...) = 表达式` 没有自己的左花括号，配平会把后面的声明一并吞进来
   （`PasskeyData.usePrivateKeyBytes` / `SimpleJson.isNull` / `PuxArchiveReader.asLong`
   曾被误计成 154~285 行）。**判「单表达式」必须在参数表右括号之后再看第一个 `{` 还是 `=`**——
   在整段文本里找第一个 `=` 会被形参默认值（`modifier: Modifier = Modifier`）抢先命中，
   从而把带默认值的**块体**函数误判成单表达式而整条漏报（§181 首版即因此少报 8 条）。

**本工具是初筛**：签名里带默认 lambda 参数（`cb: () -> Unit = {}`）等写法仍可能失真，
清单用于裁决前须按 §175 的纪律**实读**确认。已知值反校：`keepasskeySettingsNavGraph` 252、
`CreateVaultWizardDialog` 158（§179 后）、`OpenExistingVaultDialog` 96（§178 后，故不得出现在 ≥100 清单里）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULES = ["app", "database", "crypto", "sync", "core"]

STR = re.compile(r'"(?:\\.|[^"\\\n])*"' + r"|'(?:\\.|[^'\\\n])*'")
FUN = re.compile(r"\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_]+\.)?([A-Za-z0-9_]+)\s*[(<]")


def mask(src: str) -> str:
    src = STR.sub(lambda m: "S", src)
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def body_is_block(text_from_paren: str) -> bool:
    """参数表右括号之后，先遇到 `{` 即块体；先遇到 `=` 即单表达式（无自有花括号）。"""
    depth = 0
    close = -1
    for k, ch in enumerate(text_from_paren):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                close = k
                break
    if close < 0:
        return False
    for c in text_from_paren[close + 1:]:
        if c == "{":
            return True
        if c == "=":
            return False
    return False


def main() -> int:
    threshold = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    hits = []
    scanned = 0
    for m in MODULES:
        src = ROOT / m / "src" / "main"
        if not src.is_dir():
            continue
        for f in src.rglob("*.kt"):
            scanned += 1
            masked = mask(f.read_text(encoding="utf-8")).splitlines()
            for i, line in enumerate(masked):
                fm = FUN.search(line)
                if not fm:
                    continue
                tail = "\n".join(masked[i:])[fm.start():]
                # fm 的末尾把参数表的左括号一起吃掉了，这里退回一个字符，
                # 让 body_is_block 从「(」开始做括号配平（否则它永远等不到 depth==0）。
                if not body_is_block(tail[fm.end() - fm.start() - 1:]):
                    continue
                depth = 0
                started = False
                for j in range(i, len(masked)):
                    body = masked[j]
                    depth += body.count("{") - body.count("}")
                    started = started or "{" in body
                    if started and depth == 0:
                        if j - i + 1 >= threshold:
                            hits.append((j - i + 1, f.relative_to(ROOT).as_posix(), i + 1, fm.group(1)))
                        break
    hits.sort(reverse=True)
    print(f"files_scanned={scanned}  functions_ge_{threshold}={len(hits)}")
    for n, rel, ln, name in hits:
        print(f"{n:5d}  L{ln:<5} {rel}::{name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
