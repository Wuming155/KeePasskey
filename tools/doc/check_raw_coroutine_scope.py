"""裸 `CoroutineScope(` 收口机检（`ISSUE-P1-538` 的机检化；`ISSUE-P3-535` 的人工普查曾漏检本形态）。

## 为何存在

应用级长生命周期协程作用域**必须以 `guardedScope(...)` 工厂创建**（该工厂附带
`SupervisorJob` + `CoroutineExceptionHandler`）。裸 `CoroutineScope(...)` 两者皆无：逃逸到协程根的
异常直达线程默认处理器并**直接杀死进程**（§473 真机闪退即此形态；§474 的整改把 15 处
`CoroutineScope(SupervisorJob(...))` 接入工厂，但**漏掉了不带 `SupervisorJob` 的裸形态**）。

`ISSUE-P1-538` 立规缘由：上一批的普查口径写死为 `CoroutineScope(SupervisorJob(` ⇒ **形态性漏检**
（`AppShellLocalization.kt` 的 `CoroutineScope(Dispatchers.IO)` 不含 `SupervisorJob` 故从未进入普查面）。
与 `ISSUE-P3-305`「闸门存在 ≠ 闸门被执行」同型：收口靠人工普查、无机检 ⇒ 新代码不会自动受约束。

## 判据（唯一，命中即退出码 1）

**`*/src/main/**` 内的 `CoroutineScope(` 只允许出现在 `ALLOWED_FILES` 登记的文件里。**

- 扫描面＝**五个生产模块**（`MODULES`）各自的 `src/main/**` 下的 `.kt` 文件（与 `count_line_tiers.py` 同口径）。
  **不用 `*/src/main/**` 通配**：本机存在**未跟踪的只读参考树** `参考项目/`（2410 个 `.kt`），
  通配会把第三方代码算进扫描面与命中数（实测：本机 2430 文件 / 50 命中，排除后 731 文件 / 0 命中）⇒
  本机红、CI 绿，读数不可复现（2026-10-08 §479 更正，见该批批次文档「过程留痕」）。
  测试源集与生成物不参与生产数据路径。
- 判据正则 `\\bCoroutineScope\\s*\\(`：`\\b` 保证**不误伤** `rememberCoroutineScope()`
  （其 `C` 前是 `r`，同为词字符 ⇒ 无词边界；Compose 生命周期作用域不属本判据）。
  类型引用（`import` / `: CoroutineScope`）不含 `(`，同样不命中。
- 注释行（`//` 与 KDoc 行 `*`）一律跳过——判据盯的是**调用**，不是文档里的提及。
- `viewModelScope` / `lifecycleScope` 等属性访问不在判据内（它们自带 `SupervisorJob` + 生命周期取消）。

## 口径声明（**不得**据其绿推定「全仓已无异常逃逸面」）

本机检只钉**构造形态**。以下均**看不见**：① 经工厂函数包装后再暴露（间接形态）；
② `GlobalScope`（另一族问题，另有 KDoc 禁令）；③ 作用域虽经工厂创建但协程体内部
`try/catch` 吞掉异常（那属调用点语义，非构造面）。

用法：`python tools/doc/check_raw_coroutine_scope.py [--root 目录] [--selftest]`；
命中即退出码 1（无命中退 0）；`--selftest` 用内嵌正 / 反样本反校判据本身。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# 允许出现 `CoroutineScope(` 的文件（相对仓库根，posix 路径）。新成员入列须写明理由。
ALLOWED_FILES = (
    # 工厂自身的定义处（其内部 `CoroutineScope(SupervisorJob() + dispatcher + handler)`）
    "app/src/main/java/com/keepasskey/app/coroutines/GuardedScope.kt",
)

# 判据：真正的 `CoroutineScope(` 构造调用。`\b` 排除 `rememberCoroutineScope(`。
RAW_SCOPE_RE = re.compile(r"\bCoroutineScope\s*\(")

# 扫描面：五个生产模块的 `src/main`（与 `count_line_tiers.py` 同口径，不随未跟踪目录漂移）。
MODULES = ("app", "database", "crypto", "sync", "core")


def _is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


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
            for lineno, raw in enumerate(text.splitlines(), 1):
                if _is_comment(raw) or not RAW_SCOPE_RE.search(raw):
                    continue
                if rel in ALLOWED_FILES:
                    continue
                hits.append("RAW_SCOPE %s:%d  %s" % (rel, lineno, raw.strip()))
    return hits, checked


# `--selftest` 内嵌样本：绿样本（工厂调用 / Compose 生命周期）0 命中；红样本各命中一次。
SELFTEST_GOOD = (
    "package demo\n",
    "val s = guardedScope(Dispatchers.IO, \"Demo\")\n",
    "val c = rememberCoroutineScope()\n",
    "val t: CoroutineScope = other\n",
)
SELFTEST_BAD = "val s = CoroutineScope(Dispatchers.IO)\n"


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
        and bad_hits[0].startswith("RAW_SCOPE")
    )
    print(
        "[selftest] 绿样本命中=%d（须 0，检查 %d 文件）；红样本命中=%d（须 1，检查 %d 文件）"
        % (len(good_hits), good_checked, len(bad_hits), bad_checked)
    )
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print("[selftest] PASS：判据可分辨「工厂 / Compose 生命周期」与「裸构造」")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="裸 CoroutineScope( 收口机检（ISSUE-P1-538）")
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, checked = scan(root)
    print(
        "[check_raw_coroutine_scope] 检查文件 %d 个（*/src/main/**）；允许清单 %d 个；命中 %d 处"
        % (checked, len(ALLOWED_FILES), len(hits))
    )
    for hit in hits:
        print("  HIT " + hit)
    if hits:
        print(
            "[check_raw_coroutine_scope] FAIL：应用级 scope 必须经 `guardedScope(...)` 工厂创建"
            "（裸 `CoroutineScope(` 无异常处理器 ⇒ 未捕获异常直达线程默认处理器、进程闪退）"
        )
        return 1
    print("[check_raw_coroutine_scope] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
