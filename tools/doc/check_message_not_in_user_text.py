"""异常 `message` 不得进用户可见文案槽（`ISSUE-P3-550` 的机检化）。

## 为何存在

`KdbxResult.Failure` 的 KDoc 早已明写：`error.message` **属不可信外部输入**——可能携带
主机地址 / 文件路径 / 协议细节（且服务端可控），「绝不上浮 UI」；`Failure.message` 也已标
`@Deprecated(level = ERROR)`。但纪律只写在注释里时，`ISSUE-P3-550` 的普查仍抓出 **11 处**
`t.message` / `e.message` / `ex?.message` 被直接拼进用户可见文案，且其中一处
（`SyncConflictAutoMerge` 的 `ex?.message`）**逃过了上一轮的 grep**——普查口径写死为
`\\.message ?:`（BRE），而 `ex?.message` 不带 `?:`。

「口径写死 ⇒ 形态性漏检」在本仓已是第二次（`ISSUE-P1-538` 裸 `CoroutineScope(` 同型），
故把判据钉成脚本：**文案槽内出现 `.message` 即红**。

## 判据

**用户可见文案槽的实参文本内不得出现 `.message`（含 `?.message`）。**

- 文案槽＝`strings.get(` / `UiMessage(` / `KdbxResult.Failure(` / `SyncOutcome.Error(` /
  `ProbeOutcome.Failed(` / `showSnackbar(` / `makeText(`（后两者为 `ISSUE-P2-556` 补入——
  仓内确有该形态的文案出口，此前不在扫描面）/ `errorMessage =` / `healthMessage =` / `userMessage =`。
- 扫描面＝**五个生产模块**的 `src/main/**`（与 `check_raw_coroutine_scope.py` 同口径，
  不用通配：本机存在未跟踪的只读参考树 `参考项目/`，通配会让本机与 CI 读数不可复现）。
- **按实参文本取整段、不按行**（`ISSUE-P2-556` AC③）：调用式槽取**配对括号内**的整段实参
  （多行调用因此全覆盖）；赋值式槽（`errorMessage =` 等）取该语句的表达式区间。
  逐行扫描会漏「槽标记与 `.message` 各占一行」的形态。
- **接收者正则放宽**（`ISSUE-P2-556` AC③）：`([\\w)\\]]+)[^\\S\\n]*\\??[^\\S\\n]*\\.[^\\S\\n]*message\\b`
  ——覆盖 `e.message`、`ex?.message`、以及方法接收者 `result.exceptionOrNull()?.message`
  （末字符为 `)`，旧的 `\\b(\\w+)` 形态与其不匹配）。
- 注释一律**掩码后**再扫（判据盯**代码**，不是文档里的提及）；字符串字面量保留。
- **唯一豁免**：接收者在本文件内被 `catch (x: T)` 绑定、且 `T ∈ ALLOWED_EXCEPTION_TYPES`。
  现仅 `ImportFormatException`——其 `message` 恒为**本仓自产的解析失败原因常量**
  （`MESSAGE_*` / 各 throw 点，2026-10-09 逐处核实无外部输入）；其硬编码中文是
  **i18n 面**的问题，不属本机检（外部输入外泄）范围，故按类型登记而非放行调用点。
- **非退化断言**（`ISSUE-P3-562` AC）：`checked == 0`（扫描面为空 / `--root` 传错 / 模块改名）
  **判红**——与 `check_box_slot_children.py` 同范式，杜绝「空转显绿」。

## 口径声明（**不得**据其绿推定「UI 已无敏感细节」）

本机检只钉「`.message` 不进文案槽」。它**看不见**：① 经变量中转再进槽
（如 `val detail = t.message` 后 `strings.get(R.x, detail)`）；② 非 `.message` 的
敏感字段（`error.localizedMessage` / `uri.toString()`）；③ 别名字段承载的异常消息。

用法：`python tools/doc/check_message_not_in_user_text.py [--root 目录] [--selftest]`；
命中即退出码 1（无命中退 0，扫描面为空同样退 1）；`--selftest` 用内嵌正 / 反样本反校判据本身。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# 用户可见文案槽（命中任一即把其**实参文本**纳入判据面）
SLOT_MARKERS = (
    "strings.get(",
    "UiMessage(",
    "KdbxResult.Failure(",
    "SyncOutcome.Error(",
    "ProbeOutcome.Failed(",
    "showSnackbar(",
    "makeText(",
    "errorMessage =",
    "healthMessage =",
    "userMessage =",
)

# 允许作为 `.message` 接收者的异常类型（按 `catch` 绑定类型登记，理由见模块 KDoc）
ALLOWED_EXCEPTION_TYPES = ("ImportFormatException",)

# 判据：`<receiver>?.message` / `<receiver>.message`（接收者末字符可为 `)` / `]`）
MESSAGE_ACCESS_RE = re.compile(r"([\w)\]]+)[^\S\n]*\??[^\S\n]*\.[^\S\n]*message\b")

# `catch (name: Type)` 绑定
CATCH_BINDING_RE = re.compile(r"catch\s*\(\s*(\w+)\s*:\s*([\w.]+)")

MODULES = ("app", "database", "crypto", "sync", "core")

# 赋值式槽的表达式续行判据（行尾 / 行首出现这些字符即视为同一表达式未结束）
_CONTINUATION_TAIL = set("+-*/%&|^!=<>?:,.[({")
_CONTINUATION_HEAD = set("+-*/%&|^?:.)]")


def _mask_comments(text: str) -> str:
    """把注释字符替换为空格（保留换行与长度），使注释里的 `.message` 不参与判定。

    字符串字面量保持原样（槽标记与接收者不会藏在注释里，但可能出现在字符串插值中）。
    """
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '"':
            if text.startswith('"""', i):
                end = text.find('"""', i + 3)
                i = n if end < 0 else end + 3
                continue
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if ch == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            for k in range(i, end):
                if out[k] != "\n":
                    out[k] = " "
            i = end
            continue
        i += 1
    return "".join(out)


def _matching_paren(text: str, open_idx: int) -> int:
    """返回与 `text[open_idx] == '('` 配对的右括号下标；找不到返回 -1。"""
    depth = 0
    idx = open_idx
    while idx < len(text):
        if text[idx] == "(":
            depth += 1
        elif text[idx] == ")":
            depth -= 1
            if depth == 0:
                return idx
        idx += 1
    return -1


def _statement_span(text: str, start: int) -> str:
    """从 `start` 起取「一条表达式语句」的文本（括号配对 + 续行判据，跨行不误截）。"""
    depth = 0
    i = start
    while i < len(text):
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth < 0:
                return text[start:i]
        elif ch == "\n":
            before = text[:i].rstrip()
            after = text[i + 1 :].lstrip()
            if before and before[-1] in _CONTINUATION_TAIL:
                i += 1
                continue
            if after and after[0] in _CONTINUATION_HEAD:
                i += 1
                continue
            return text[start:i]
        i += 1
    return text[start:]


def _argument_span(text: str, marker_pos: int, marker: str) -> tuple[str, int]:
    """槽的实参文本及其在 `text` 中的起始下标。"""
    start = marker_pos + len(marker)
    if marker.endswith("("):
        close = _matching_paren(text, start - 1)
        return (text[start:close], start) if close >= 0 else (text[start:], start)
    return _statement_span(text, start), start


def _catch_bindings(text: str) -> dict[str, str]:
    """`catch` 参数名 → 异常类型（用于豁免判定）。"""
    return {name: type_name for name, type_name in CATCH_BINDING_RE.findall(text)}


def scan(root: pathlib.Path) -> tuple[list[str], int]:
    """返回 (命中清单, 检查过的文件数)。"""
    hits: list[str] = []
    checked = 0
    for module in MODULES:
        src = root / module / "src" / "main"
        if not src.is_dir():
            continue
        for path in sorted(src.rglob("*.kt")):
            rel = path.relative_to(root).as_posix()
            checked += 1
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            masked = _mask_comments(text)
            bindings = _catch_bindings(masked)
            source_lines = text.splitlines()
            reported: set[int] = set()
            for marker in SLOT_MARKERS:
                for marker_pos in _find_all(masked, marker):
                    span, span_start = _argument_span(masked, marker_pos, marker)
                    for m in MESSAGE_ACCESS_RE.finditer(span):
                        receiver = m.group(1)
                        if bindings.get(receiver) in ALLOWED_EXCEPTION_TYPES:
                            continue
                        abs_idx = span_start + m.start()
                        lineno = masked.count("\n", 0, abs_idx) + 1
                        if lineno in reported:
                            continue
                        reported.add(lineno)
                        raw = source_lines[lineno - 1] if lineno <= len(source_lines) else ""
                        hits.append(
                            "MESSAGE_IN_USER_TEXT %s:%d  %s" % (rel, lineno, raw.strip())
                        )
    return hits, checked


def _find_all(text: str, needle: str) -> list[int]:
    positions: list[int] = []
    start = text.find(needle)
    while start >= 0:
        positions.append(start)
        start = text.find(needle, start + 1)
    return positions


def verdict(hits: list[str], checked: int) -> int:
    """统一判定：命中即红；**扫描面为空同样判红**（`ISSUE-P3-562`）。"""
    if checked == 0:
        return 1
    return 1 if hits else 0


# `--selftest` 内嵌样本
SELFTEST_GOOD = """package demo

class Good(private val strings: StringsProvider) {
    fun run() {
        try { work() } catch (t: Throwable) {
            AppLog.w(TAG, "失败", t)
            emit(UiMessage(R.string.op_failed, listOf(strings.get(R.string.err_unknown))))
        }
        try { parse() } catch (format: ImportFormatException) {
            emit(KdbxResult.Failure(format, format.message))
        }
        // 注释里的 t.message 不算命中
        emit(strings.get(R.string.ok))
    }
}
"""

# 反样本：覆盖 `ISSUE-P2-556` 实测的 3 类绕过形态 + 2 类新增槽形态。
SELFTEST_BAD = """package demo

class Bad(private val strings: StringsProvider) {
    fun run() {
        try { work() } catch (t: Throwable) {
            emit(UiMessage(R.string.op_failed, listOf(t.message ?: "")))
        } catch (e: Exception) {
            emit(KdbxResult.Failure(e, strings.get(R.string.repo_failed, e.message ?: "")))
        } catch (ex: java.io.IOException) {
            emit(strings.get(R.string.sync_error, ex?.message))
        }
    }

    fun multiLine(strings: StringsProvider) {
        // 形态①：槽标记与 `.message` 各占一行（逐行扫描必漏）
        emit(
            strings.get(
                R.string.sync_error,
                pending.message ?: ""
            )
        )
    }

    fun methodReceiver(strings: StringsProvider) {
        // 形态②：方法接收者（末字符 `)`，旧 `\\b(\\w+)` 正则不匹配）
        emit(SyncOutcome.Error(strings.get(R.string.sync_error, result.exceptionOrNull()?.message)))
    }

    fun snackbarSlot() {
        // 形态④：`showSnackbar(` 槽（此前不在 SLOT_MARKERS）
        host.showSnackbar("同步失败：" + error.message)
    }

    fun toastSlot() {
        // 形态⑤：`makeText(` 槽（此前不在 SLOT_MARKERS）
        Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
    }
}
"""


def _write(base: pathlib.Path, name: str, body: str) -> pathlib.Path:
    target = base / "app/src/main/java/demo" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")
    return base


def selftest() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        good_root = _write(pathlib.Path(tmp) / "good", "Good.kt", SELFTEST_GOOD)
        good_hits, good_checked = scan(good_root)
        bad_root = _write(pathlib.Path(tmp) / "bad", "Bad.kt", SELFTEST_BAD)
        bad_hits, bad_checked = scan(bad_root)
        empty_root = pathlib.Path(tmp) / "empty"
        empty_root.mkdir(parents=True, exist_ok=True)
        empty_hits, empty_checked = scan(empty_root)

    # 绿样本 0 命中；红样本 7 处（t.message / e.message / ex?.message / 多行槽 / 方法接收者 /
    # showSnackbar 槽 / makeText 槽）；空树必须判红。
    ok = (
        good_checked == 1
        and bad_checked == 1
        and not good_hits
        and len(bad_hits) == 7
        and all(h.startswith("MESSAGE_IN_USER_TEXT") for h in bad_hits)
        and empty_checked == 0
        and verdict(empty_hits, empty_checked) == 1
        and verdict(bad_hits, bad_checked) == 1
        and verdict(good_hits, good_checked) == 0
    )
    print(
        "[selftest] 绿样本命中=%d（须 0，检查 %d 文件）；红样本命中=%d（须 7，检查 %d 文件）；"
        "空树 checked=%d 判定=%d（须 1）"
        % (
            len(good_hits),
            good_checked,
            len(bad_hits),
            bad_checked,
            empty_checked,
            verdict(empty_hits, empty_checked),
        )
    )
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print(
        "[selftest] PASS：判据可分辨「细节只进日志 / 自产常量」与「异常 message 进文案槽」，"
        "并覆盖多行调用 / 方法接收者 / showSnackbar / makeText 四种形态与空树非退化"
    )
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="异常 message 不得进用户可见文案槽的机检（ISSUE-P3-550）"
    )
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, checked = scan(root)
    print(
        "[check_message_not_in_user_text] 检查文件 %d 个（*/src/main/**）；文案槽 %d 类；命中 %d 处"
        % (checked, len(SLOT_MARKERS), len(hits))
    )
    for hit in hits:
        print("  HIT " + hit)
    if checked == 0:
        print(
            "[check_message_not_in_user_text] FAIL：扫描面为空（--root 传错 / 模块被移动或改名）"
            "——空转不得当绿（ISSUE-P3-562）"
        )
        return 1
    if hits:
        print(
            "[check_message_not_in_user_text] FAIL：异常 message 属不可信外部输入"
            "（路径 / 端点 / 主机 / 协议细节，服务端可控），只进日志；UI 侧一律走错误码映射"
        )
        return 1
    print("[check_message_not_in_user_text] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
