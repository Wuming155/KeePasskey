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

**用户可见文案槽内不得出现 `.message`（含 `?.message`）。**

- 文案槽＝`strings.get(` / `UiMessage(` / `KdbxResult.Failure(` / `SyncOutcome.Error(` /
  `ProbeOutcome.Failed(` / `errorMessage =` / `healthMessage =` / `userMessage =`。
- 扫描面＝**五个生产模块**的 `src/main/**`（与 `check_raw_coroutine_scope.py` 同口径，
  不用通配：本机存在未跟踪的只读参考树 `参考项目/`，通配会让本机与 CI 读数不可复现）。
- 注释行跳过（判据盯**代码**，不是文档里的提及）。
- **唯一豁免**：接收者在本文件内被 `catch (x: T)` 绑定、且 `T ∈ ALLOWED_EXCEPTION_TYPES`。
  现仅 `ImportFormatException`——其 `message` 恒为**本仓自产的解析失败原因常量**
  （`MESSAGE_*` / 各 throw 点，2026-10-09 逐处核实无外部输入）；其硬编码中文是
  **i18n 面**的问题，不属本机检（外部输入外泄）范围，故按类型登记而非放行调用点。

## 口径声明（**不得**据其绿推定「UI 已无敏感细节」）

本机检只钉「`.message` 不进文案槽」。它**看不见**：① 经变量中转再进槽
（如 `val detail = t.message` 后 `strings.get(R.x, detail)`）；② 非 `.message` 的
敏感字段（`error.localizedMessage` / `uri.toString()`）。

用法：`python tools/doc/check_message_not_in_user_text.py [--root 目录] [--selftest]`；
命中即退出码 1（无命中退 0）；`--selftest` 用内嵌正 / 反样本反校判据本身。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# 用户可见文案槽（命中任一即把该行纳入判据面）
SLOT_MARKERS = (
    "strings.get(",
    "UiMessage(",
    "KdbxResult.Failure(",
    "SyncOutcome.Error(",
    "ProbeOutcome.Failed(",
    "errorMessage =",
    "healthMessage =",
    "userMessage =",
)

# 允许作为 `.message` 接收者的异常类型（按 `catch` 绑定类型登记，理由见模块 KDoc）
ALLOWED_EXCEPTION_TYPES = ("ImportFormatException",)

# 判据：`<receiver>?.message` / `<receiver>.message`
MESSAGE_ACCESS_RE = re.compile(r"\b(\w+)\s*\??\.\s*message\b")

# `catch (name: Type)` 绑定
CATCH_BINDING_RE = re.compile(r"catch\s*\(\s*(\w+)\s*:\s*([\w.]+)")

MODULES = ("app", "database", "crypto", "sync", "core")


def _is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


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
            bindings = _catch_bindings(text)
            for lineno, raw in enumerate(text.splitlines(), 1):
                if _is_comment(raw):
                    continue
                slot_start = min(
                    (raw.find(marker) for marker in SLOT_MARKERS if marker in raw),
                    default=-1,
                )
                if slot_start < 0:
                    continue
                for m in MESSAGE_ACCESS_RE.finditer(raw, slot_start):
                    receiver = m.group(1)
                    bound_type = bindings.get(receiver)
                    if bound_type in ALLOWED_EXCEPTION_TYPES:
                        continue
                    hits.append(
                        "MESSAGE_IN_USER_TEXT %s:%d  %s"
                        % (rel, lineno, raw.strip())
                    )
                    break
    return hits, checked


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
    }
}
"""

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

    ok = (
        good_checked == 1
        and bad_checked == 1
        and not good_hits
        and len(bad_hits) == 3
        and all(h.startswith("MESSAGE_IN_USER_TEXT") for h in bad_hits)
    )
    print(
        "[selftest] 绿样本命中=%d（须 0，检查 %d 文件）；红样本命中=%d（须 3，检查 %d 文件）"
        % (len(good_hits), good_checked, len(bad_hits), bad_checked)
    )
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print("[selftest] PASS：判据可分辨「细节只进日志 / 自产常量」与「异常 message 进文案槽」")
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
