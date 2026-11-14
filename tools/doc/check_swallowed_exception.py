"""静默吞异常（空 `catch` 块）机检（`ISSUE-P2` E6 的机检化）。

## 为何存在

本仓的失败可见性已按多种形态整改过：同步编排的 catch-all 补 `kind/code`、设置页静默 catch
补留痕、附件与图标读失败改用户可见文案。但这些整改都是**逐点**的，下一处新增的
`catch (_: Throwable) {}` 不会触发任何检查——与 `ISSUE-P1-538`「闸门存在 ≠ 闸门被执行」同型。

空 `catch` 的代价不是「少一条日志」：它把失败吞成**静默成功**，调用方拿到未初始化的状态继续
执行，定位时既无栈也无分支线索。因此把判据钉成脚本，覆盖生产、`src/debug` 与 `src/androidTest`。

## 判据

**`catch` 块体（去掉注释后）为空即命中**，以下两种形态豁免：

1. **块体内有注释**：注释即意图声明（`// 预期：已关闭`、`// 取消无副作用`）。空块带注释属
   可审计的显式忽略；无注释的空块无法与「漏写」区分。
2. **对应的 `try` 体含 `fail(`**：用例的「必须抛出」断言惯用法——
   `try { 动作; fail("必须抛") } catch (_: X) {}`。此处空块是断言成立的前提，吞掉才是对的。

判据只认**块体**：`catch` 的参数名、异常类型、是否有 `?:` 都不影响结论。注释先剥除再判空，
字符串字面量保留（`"//"` 不会被当作注释）。

## 口径声明（**不得**据其绿推定「失败已全部可见」）

本机检只钉**空块**。它**看不见**：① 块体内有日志但只写 `e.message`（另由
`check_message_not_in_user_text.py` 与脱敏器覆盖）；② 块体内 `return null` / 默认值等
**吞掉错误语义**的形态；③ 用 `runCatching { }.getOrNull()` 等不经 `catch` 关键字的等价格式；
④ 注释写了但内容不成立（例如 `// 不会失败`）——这属评审面，机检无法判定。

用法：`python tools/doc/check_swallowed_exception.py [--root 目录] [--selftest]`；
命中即退出码 1（无命中退 0，扫描面为空同样退 1）；`--selftest` 用内嵌正 / 反样本反校判据本身。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

MODULES = ("app", "database", "crypto", "sync", "core")
SOURCE_SETS = ("main", "debug", "androidTest")

CATCH_RE = re.compile(r"catch\s*\([^)]*\)\s*\{")
TRY_RE = re.compile(r"\btry\s*\{")
FAIL_CALL_RE = re.compile(r"\bfail\s*\(")


def _strip_comments(text: str) -> str:
    """把 `//` 与 `/* */` 注释替换为等长空白（保留换行与偏移）；字符串字面量原样保留。"""
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '"' or ch == "'":
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                out.append(text[i])
                if text[i] == "\\":
                    if i + 1 < n:
                        out.append(text[i + 1])
                        i += 2
                        continue
                elif text[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if ch == "/" and i + 1 < n:
            if text[i + 1] == "/":
                while i < n and text[i] != "\n":
                    out.append(" ")
                    i += 1
                continue
            if text[i + 1] == "*":
                out.append(" ")
                out.append(" ")
                i += 2
                while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                    out.append("\n" if text[i] == "\n" else " ")
                    i += 1
                for _ in range(min(2, n - i)):
                    out.append(" ")
                i += 2
                continue
        out.append(ch)
        i += 1
    return "".join(out)


def _block_end(text: str, start: int) -> int:
    """返回从 `{` 之后开始、与之配对的 `}` 的下标；不配平时返回文本末尾。"""
    depth = 1
    i = start
    while i < len(text) and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    return i - 1


def _enclosing_try_body(text: str, catch_start: int) -> str:
    """取 `catch` 之前最近的 `try` 块体文本（找不到返回空串）。"""
    candidates = [m for m in TRY_RE.finditer(text, 0, catch_start)]
    if not candidates:
        return ""
    try_start = candidates[-1]
    open_idx = try_start.end() - 1
    body = text[open_idx + 1 : _block_end(text, open_idx + 1)]
    # `try` 与 `catch` 之间只允许空白 / 注释 / `finally`：若其间还有别的语句，说明匹配错了 try。
    between = text[_block_end(text, open_idx + 1) + 1 : catch_start]
    if re.search(r"[^\s{}]", _strip_comments(between).replace("finally", "")):
        return ""
    return body


def scan(root: pathlib.Path) -> tuple[list[str], int]:
    hits: list[str] = []
    checked = 0
    for module in MODULES:
        for source_set in SOURCE_SETS:
            src = root / module / "src" / source_set
            if not src.is_dir():
                continue
            for path in sorted(src.rglob("*.kt")):
                rel = path.relative_to(root).as_posix()
                checked += 1
                try:
                    text = path.read_text(encoding="utf-8")
                except (OSError, UnicodeDecodeError):
                    continue
                code = _strip_comments(text)
                for match in CATCH_RE.finditer(code):
                    body_start = match.end()
                    body_end = _block_end(code, body_start)
                    if code[body_start:body_end].strip():
                        continue
                    # 掩码后为空、原文非空 ⇒ 块内只有注释：意图已声明，属可审计的显式忽略。
                    if text[body_start:body_end].strip():
                        continue
                    if FAIL_CALL_RE.search(_enclosing_try_body(code, match.start())):
                        continue
                    line = code[: match.start()].count("\n") + 1
                    raw_line = text.splitlines()[line - 1].strip() if text.splitlines() else ""
                    hits.append("EMPTY_CATCH %s:%d  %s" % (rel, line, raw_line))
    if checked == 0:
        hits.append("EMPTY_SCAN 扫描面为空（模块改名或 --root 指向错误）")
    return hits, checked


SELFTEST_GOOD = (
    "package demo\n",
    "fun a() { try { run() } catch (_: Exception) { // 预期：已关闭\n } }\n",
    "fun b() { try { run(); fail(\"必须抛\") } catch (_: Exception) {} }\n",
    "fun c() { try { run() } catch (e: Exception) { log(e) } }\n",
)
SELFTEST_BAD = "fun d() { try { run() } catch (_: Exception) {} }\n"


def _write(base: pathlib.Path, name: str, body: str) -> pathlib.Path:
    target = base / "app/src/main/java/demo" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")
    return base


def selftest() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        good_root = _write(pathlib.Path(tmp) / "good", "Good.kt", "".join(SELFTEST_GOOD))
        good_hits, good_checked = scan(good_root)
        bad_root = _write(pathlib.Path(tmp) / "bad", "Bad.kt", SELFTEST_BAD)
        bad_hits, bad_checked = scan(bad_root)

    ok = (
        good_checked == 1
        and bad_checked == 1
        and not good_hits
        and len(bad_hits) == 1
        and bad_hits[0].startswith("EMPTY_CATCH")
    )
    print(
        "[selftest] 绿样本命中=%d（须 0，检查 %d 文件）；红样本命中=%d（须 1，检查 %d 文件）"
        % (len(good_hits), good_checked, len(bad_hits), bad_checked)
    )
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        for hit in good_hits + bad_hits:
            print("  " + hit)
        return 1
    print("[selftest] PASS：判据可分辨「带注释 / fail 断言」与「无说明空 catch」")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="空 catch 块机检（ISSUE-P2 E6）")
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, checked = scan(root)
    print(
        "[check_swallowed_exception] 检查文件 %d 个（*/src/{main,debug,androidTest}/**）；命中 %d 处"
        % (checked, len(hits))
    )
    for hit in hits:
        print("  HIT " + hit)
    if hits:
        print(
            "[check_swallowed_exception] FAIL：空 catch 把失败吞成静默成功；"
            "补留痕，或在块内写明忽略理由（如 `// 预期：已关闭`）"
        )
        return 1
    print("[check_swallowed_exception] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
