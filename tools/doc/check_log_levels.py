"""日志级别与直写缓冲的机检（`ISSUE-P2` B3 的机检化）。

## 为何存在

级别语义此前只靠调用方自觉：业务代码可以 `debugLog.log("WARN", ...)` 传任意字符串。
级别矩阵写进 [架构](../../docs/architecture/ARCHITECTURE.md#日志级别与详细度) 之后，
需要一道机检让新代码不能悄悄偏离——否则规范只是文档里的一段话。

## 判据

1. **级别字面量不得出现在 `data/logger` 之外**（默认启用）：`"INFO"` / `"WARN"` /
   `"ERROR"` / `"DEBUG"` / `"AUDIT"` 作为字符串实参出现即命中。级别由 `DebugLogBuffer`
   的具名方法（`info` / `warn` / `error` / `debug` / `audit`）决定，业务代码只选方法不传字符串。
2. **`data/logger` 之外不得出现 `DebugLogBuffer` 标识符**（默认启用）：包括构造注入与
   类型引用。业务代码应经 `AppLog` 记录，缓冲是日志组件的内部实现。唯一豁免是
   `BUFFER_EXEMPT_FILES` 登记的诊断日志功能自身（导出 / 复制 / 清除 / 装配），它们必须持有
   缓冲本体；新增成员须写明理由。

扫描面＝五个生产模块的 `src/main`（与其它机检同口径，不用通配以免把 `参考项目/` 算进来）。

## 口径声明（**不得**据其绿推定「级别用得对」）

本机检只钉**形态**。它**看不见**：① 选错方法（把 `ERROR` 写成 `WARN`）；② 该记 `AUDIT`
的数据出域动作只用 `INFO` 记；③ 该记 `DEBUG` 的过程细节写成 `INFO` 淹没缓冲。
这些属评审面，由架构文档的判定规则约束，机检无法判定。

用法：`python tools/doc/check_log_levels.py [--root 目录] [--strict-buffer] [--selftest]`；
命中即退出码 1（无命中退 0，扫描面为空同样退 1）；`--selftest` 用内嵌正 / 反样本反校判据。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

MODULES = ("app", "database", "crypto", "sync", "core")
SOURCE_SETS = ("main",)

# 日志组件的内部实现：级别常量与缓冲类型只允许在这里出现。
# `core/log/AppLog.kt` 是级别的**产生方**（把 `i/w/e` 映射为诊断行级别），不是消费方。
LOGGER_PACKAGES = (
    "app/src/main/java/com/keepasskey/app/data/logger/",
    "core/src/main/java/com/keepasskey/core/log/",
)

# 诊断日志功能自身：导出 / 复制 / 清除 / 装配必须持有缓冲本体，经 `AppLog` 表达不了。
# 这些类不做业务状态记录，只操作缓冲（快照、导出文档、清除）或把它转交给上述功能。
BUFFER_EXEMPT_FILES = (
    # 报告构建：读 `exportDocument()` 与 `MAX_LINES`
    "app/src/main/java/com/keepasskey/app/ui/screens/settings/DiagnosticLogReport.kt",
    # 预览快照 / 刷新 / 清除
    "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsDebugLogActions.kt",
    # 导出写盘、复制到剪贴板、导出审计
    "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExportController.kt",
    # 装配：把同一实例转交给上面三个功能
    "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsControllerGraph.kt",
    "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsPreferencesController.kt",
    "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt",
)

LEVEL_LITERAL_RE = re.compile(r'"(?:INFO|WARN|ERROR|DEBUG|AUDIT)"')
BUFFER_RE = re.compile(r"\bDebugLogBuffer\b")


def _is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def scan(root: pathlib.Path, strict_buffer: bool = False) -> tuple[list[str], int]:
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
                if rel.startswith(LOGGER_PACKAGES) or rel in BUFFER_EXEMPT_FILES:
                    continue
                try:
                    text = path.read_text(encoding="utf-8")
                except (OSError, UnicodeDecodeError):
                    continue
                for lineno, raw in enumerate(text.splitlines(), 1):
                    if _is_comment(raw):
                        continue
                    if LEVEL_LITERAL_RE.search(raw):
                        hits.append(
                            "LEVEL_LITERAL %s:%d  %s（级别应由具名方法决定）"
                            % (rel, lineno, raw.strip())
                        )
                    elif strict_buffer and BUFFER_RE.search(raw):
                        hits.append(
                            "BUFFER_REF %s:%d  %s（业务代码应经 AppLog）"
                            % (rel, lineno, raw.strip())
                        )
    if checked == 0:
        hits.append("EMPTY_SCAN 扫描面为空（模块改名或 --root 指向错误）")
    return hits, checked


SELFTEST_GOOD = (
    "package demo\n",
    "import com.keepasskey.core.log.AppLog\n",
    "fun a() { AppLog.w(\"Tag\", \"降级了\") }\n",
    "fun b() { AppLog.e(\"Tag\", \"失败了\", t) }\n",
    "// 注释里提到 DebugLogBuffer 与 \"ERROR\" 不算\n",
)
SELFTEST_BAD = (
    "package demo\n",
    "fun c() { val l = \"ERROR\"; log(l) }\n",
    "fun d(buffer: DebugLogBuffer) { buffer.info(\"Tag\", \"x\") }\n",
)


def _write(base: pathlib.Path, name: str, body: str) -> pathlib.Path:
    target = base / "app/src/main/java/demo" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")
    return base


def selftest() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        good_root = _write(pathlib.Path(tmp) / "good", "Good.kt", "".join(SELFTEST_GOOD))
        good_hits, good_checked = scan(good_root)
        bad_root = _write(pathlib.Path(tmp) / "bad", "Bad.kt", "".join(SELFTEST_BAD))
        bad_hits, bad_checked = scan(bad_root, strict_buffer=True)

    kinds = {hit.split(" ", 1)[0] for hit in bad_hits}
    ok = (
        good_checked == 1
        and bad_checked == 1
        and not good_hits
        and kinds == {"LEVEL_LITERAL", "BUFFER_REF"}
    )
    print(
        "[selftest] 绿样本命中=%d（须 0，检查 %d 文件）；红样本命中=%d（须 2：级别字面量 + 缓冲引用，检查 %d 文件）"
        % (len(good_hits), good_checked, len(bad_hits), bad_checked)
    )
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        for hit in good_hits + bad_hits:
            print("  " + hit)
        return 1
    print("[selftest] PASS：判据可分辨「经 AppLog 的具名级别」与「传级别字符串 / 直用缓冲」")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="日志级别与直写缓冲机检（ISSUE-P2 B3）")
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument(
        "--allow-buffer",
        action="store_true",
        help="放宽判据 2（仅用于迁移期排查；门禁不传该开关）",
    )
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, checked = scan(root, strict_buffer=not args.allow_buffer)
    print(
        "[check_log_levels] 检查文件 %d 个（*/src/main/**）；命中 %d 处" % (checked, len(hits))
    )
    for hit in hits:
        print("  HIT " + hit)
    if hits:
        print(
            "[check_log_levels] FAIL：级别须由具名方法决定、业务代码须经 AppLog"
            "（级别矩阵见 docs/architecture/ARCHITECTURE.md#日志级别与详细度）"
        )
        return 1
    print("[check_log_levels] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
