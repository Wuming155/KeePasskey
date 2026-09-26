#!/usr/bin/env python3
"""Manager / Util / Helper / Common 类型名**有界性**机检（PD-34，§285 立规）。

规则口径（全局工程原则，本仓落地为白名单棘轮）：
    「避免**无边界**的 Manager / Util / Helper / Common 命名」
    —— 判定对象是**泛化后缀类型名**，不是「一律禁止后缀」。
    已登记类型均有域前缀 + 单一职责（非无边界），故入 ALLOWED；
    新增同后缀类型**默认红**，须先改名（推荐域前缀 + 语义名，不带泛化后缀）
    或显式扩 ALLOWED 并同步 [`产品裁决登记.md`](../../docs/architecture/产品裁决登记.md) `PD-34`。

用法：python tools/doc/check_bounded_type_names.py
      python tools/doc/check_bounded_type_names.py --selftest   # 口径反校

退出码（与 CI `hygiene-gate` 对齐）：
    0 = 五模块 src/main 无「未登记的 Manager|Util|Helper|Common 类型名」
    1 = 存在未登记类型（逐条列出 文件:行号: 类型名），或 selftest 失败

边界（静态启发式，**不得**据其绿推定「命名面已全面合规」）：
    - 只扫 `*/src/main/**/*.kt` 的顶层/嵌套 `class|object|interface` 简单名；
      测试源（`src/test` / `src/androidTest` / `screenshotTest`）与生成目录不入扫。
    - **不**解析 Kotlin 语义：同名不同类型若后缀命中，按简单名放行 / 拦截；
      `typealias` / 局部匿名对象 / 注解类上的元注释形态可能漏报或误报，命中后人工复核。
    - 白名单只收**简单名**；同名跨包共用一条豁免（当前 12 个简单名均全局唯一）。
      集合的增 / 减都须同步 `PD-34`（`§335` 补正：`UnlockPasskeyManager` 随 `§334` 整层移除后
      本集合同步下线，避免对已不存在的类型名**预授权**）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULES = ["app", "database", "crypto", "sync", "core"]

# PD-34 已登记：域前缀 + 单一职责，非「无边界」命名。改此集合须同步 PD-34。
ALLOWED = frozenset(
    {
        "HashUtil",
        "LittleEndianUtil",
        "KdbxXmlWriteUtil",
        "KdbxXmlValueUtil",
        "KdbxXmlTimeHelper",
        "HistoryManager",
        "KeystoreManager",
        "AutoLockManager",
        "ClipboardSecurityManager",
        "BiometricAuthManager",
        "UnlockThrottleManager",
        "ChildDatabaseSessionManager",
    }
)

SUFFIX = re.compile(r"(?:Manager|Util|Helper|Common)$")
# 修饰符顺序在 Kotlin 中较自由；这里只取声明头上的常见组合。
TYPE_DECL = re.compile(
    r"^[ \t]*(?:(?:public|internal|private|protected|open|abstract|sealed|data|"
    r"enum|value|inner|annotation|companion|actual|expect|final|override)[ \t]+)*"
    r"(?:class|object|interface)[ \t]+([A-Za-z_]\w*)",
    re.MULTILINE,
)


def iter_main_kotlin() -> list[Path]:
    files: list[Path] = []
    for m in MODULES:
        src = ROOT / m / "src" / "main"
        if not src.is_dir():
            continue
        for p in src.rglob("*.kt"):
            rel = p.relative_to(ROOT).as_posix()
            if "/build/" in f"/{rel}":
                continue
            files.append(p)
    return files


def find_hits(path: Path) -> list[tuple[int, str]]:
    text = path.read_text(encoding="utf-8")
    hits: list[tuple[int, str]] = []
    for m in TYPE_DECL.finditer(text):
        name = m.group(1)
        if SUFFIX.search(name) and name not in ALLOWED:
            line = text.count("\n", 0, m.start()) + 1
            hits.append((line, name))
    return hits


def scan() -> list[tuple[str, int, str]]:
    results: list[tuple[str, int, str]] = []
    for p in iter_main_kotlin():
        rel = p.relative_to(ROOT).as_posix()
        for line, name in find_hits(p):
            results.append((rel, line, name))
    return sorted(results)


def selftest() -> int:
    """口径反校：已知白名单放行、已知未登记后缀拦截。"""
    ok = True
    sample_ok = "object HashUtil\nclass AutoLockManager\ninterface ClipboardSecurityChannel\n"
    sample_bad = "object CommonUtil\nclass FooManager\ninterface BarHelper\nobject LittleEndian\n"
    if not TYPE_DECL.search(sample_ok):
        print("::error::selftest 样例未匹配 TYPE_DECL")
        ok = False
    for name in ("HashUtil", "AutoLockManager"):
        if name in ALLOWED and SUFFIX.search(name):
            pass
        else:
            print(f"::error::selftest ALLOWED 预期含 {name}")
            ok = False
    bad_names = [n for n in ("CommonUtil", "FooManager", "BarHelper") if n not in ALLOWED and SUFFIX.search(n)]
    if len(bad_names) != 3:
        print("::error::selftest 未登记样例应全部命中后缀且不在 ALLOWED")
        ok = False
    if SUFFIX.search("LittleEndian") or SUFFIX.search("ClipboardSecurityChannel"):
        print("::error::selftest 无后缀样例被误判")
        ok = False
    # 用临时文本走 find_hits 的等价逻辑
    tmp = Path("<selftest>")  # 仅作标签；find_hits 需真实文件时用内联求值
    _ = tmp
    hits = []
    for m in TYPE_DECL.finditer(sample_bad):
        name = m.group(1)
        if SUFFIX.search(name) and name not in ALLOWED:
            hits.append(name)
    if sorted(hits) != sorted(bad_names):
        print(f"::error::selftest 命中集不符：{hits}")
        ok = False
    print(f"selftest={'OK' if ok else 'FAIL'} allowed={len(ALLOWED)}")
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()
    hits = scan()
    files_scanned = len(iter_main_kotlin())
    print(f"files_scanned={files_scanned}")
    print(f"allowed={len(ALLOWED)}")
    print(f"unregistered_manager_util_helper_common={len(hits)}")
    for rel, line, name in hits:
        print(f"  {rel}:{line}: {name}")
    if hits:
        print(
            "::error::发现未登记的 Manager|Util|Helper|Common 类型名。"
            "请改名（推荐域前缀 + 语义名）或扩 ALLOWED 并同步 PD-34。"
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
