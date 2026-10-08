"""投影面敏感读取安全复核（`ISSUE-P0-531` 真机闪退的机检化）。

## 为何存在

`ProtectedString` 是**可变的共享引用**：会话层在整树替换时会对「被替换下线」的实例**就地清零**
（`KdbxGroup.clearSupersededSensitiveData`，身份集合判定）；而 UI 投影链
（`VaultEntryQueryCoordinator.entriesFlow()` 的 `flowOn(Dispatchers.Default)`）与投影读取
**不共享锁**，存在「旧值已分发 → 擦除发生 → 投影才执行」的调度窗口。

2026-10-08 真机实测（`M332BF`，`adb logcat -b crash` + R8 mapping 还原）：该窗口内
`VaultEntryMapper.mapKdbxEntryToUi` 读 `KdbxEntry.getUserName()`（其 `fields[...]` 实例已被清零）
⇒ `ProtectedString.checkNotCleared()` 抛 `IllegalStateException: ProtectedString 已经清零，禁止继续访问`
⇒ 逃逸到协程根（全仓无全局 `CoroutineExceptionHandler`）⇒ 进程闪退（10 秒内连崩三次）。

整改分两层，**缺一不可**：
1. **擦除时序**（`SessionContentMutations` / `SessionPersistence`）：先发布新树、再擦旧树（收窄窗口）；
2. **投影面降级**：展示面读已清零实例返回确定值而不抛（`ProtectedString.readStringForDisplay()` /
   `readUtf8ForDisplay()`），关闭残余窗口。

本脚本锁住第 2 层的**复发形态**：投影面文件里再出现**裸** `readString()` / `readChars()` /
`readUtf8()`（即不经展示面安全读口）即报红 —— 这正是「将来给投影加一个新字段读取点，顺手写了
`entry.fields[k]?.readString()`」这一最可能的复发路径。

## 口径与边界

* **只查清单内的投影面文件**（见 `PROJECTION_FILES`）：判据窄而准。写路径（序列化 / 保存 /
  合并 / 导出 / 加解密 / 凭据下发）上的裸读**必须保留** fail-fast —— 就地降级会把空值写进用户的库，
  属数据损坏，所以那些文件**刻意不在清单内**。
* **允许的形态**：`readStringForDisplay()` / `readUtf8ForDisplay()`、`cleared` 显式判定、
  以及非敏感类型（如 `KdbxCustomField.key` 这类普通 `String` 字段）不受影响。
* **不解析 Kotlin AST**：逐逻辑行匹配 `\\.readString\\(\\)` / `\\.readUtf8\\(\\)` / `\\.readChars\\(\\)`，
  跳过 `//` 与 `*` 开头的注释行。经变量间接持有 `ProtectedString` 再读的形态看不见
  （**不得**据其绿推定「投影面已无裸读」）。
* **清单维护纪律**：新增投影面文件时必须登记；把一个文件移出投影面（改走写路径）也要同步移除，
  否则闸门会在无关文件上长期假红 —— 而假红会诱使后来者放宽判据（本仓明禁）。
* 用法：`python tools/doc/check_projection_read_safety.py [--root 目录] [--selftest]`；
  命中即退出码 1（无命中退 0）；`--selftest` 用内嵌正 / 反样本反校判据本身（**绿样本与红样本各验一次**）。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# 投影面文件清单（相对仓库根）。判据与增减纪律见模块 docstring「口径与边界」。
PROJECTION_FILES = (
    "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt",
    "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt",
)

# 裸读口：`x.readString()` / `x.readUtf8()` / `x.readChars()`。
# 刻意要求括号紧跟方法名：`readStringForDisplay()` 因方法名更长而**不会**命中（`readStringForDisplay` 后不是 `)`）。
BARE_READ_RE = re.compile(r"\.(?:readString|readUtf8|readChars)\(\s*\)")

# `--selftest` 内嵌样本：判据本身的反校（避免「闸门恒绿」这类失效）。
SELFTEST_GOOD_SOURCE = (
    "val text = field.value.readStringForDisplay()\n"
    "val bytes = seed.readUtf8ForDisplay()\n"
    "if (field.cleared) fallback()\n"
)
SELFTEST_BAD_SOURCE = "val text = field.value.readString()\n"


def scan(root: pathlib.Path) -> tuple[list[tuple[str, int, str]], int]:
    """扫描清单内文件，返回 (命中列表, 实际检查过的文件数)。

    命中项为 `(相对路径, 行号, 行文本)`；清单登记但文件缺失也计为命中（fail-closed）。
    """
    hits: list[tuple[str, int, str]] = []
    checked = 0
    for rel in PROJECTION_FILES:
        path = root / rel
        if not path.is_file():
            hits.append((rel, 0, "清单登记的投影面文件不存在（路径漂移或已搬迁）"))
            continue
        checked += 1
        for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = raw.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if BARE_READ_RE.search(raw):
                hits.append((rel, lineno, stripped))
    return hits, checked


def _write_fixture(base: pathlib.Path, source: str) -> pathlib.Path:
    """按清单路径在临时根下落一份样本文件（其余清单项落空文件，避免缺失判红干扰）。"""
    for rel in PROJECTION_FILES:
        target = base / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")
    return base


def selftest() -> int:
    """判据反校：绿样本须 0 命中，红样本须恰好 1 命中且行号正确。"""
    with tempfile.TemporaryDirectory() as tmp:
        good_root = _write_fixture(pathlib.Path(tmp) / "good", SELFTEST_GOOD_SOURCE)
        good_hits, good_checked = scan(good_root)
        bad_root = _write_fixture(pathlib.Path(tmp) / "bad", SELFTEST_BAD_SOURCE)
        bad_hits, bad_checked = scan(bad_root)

    ok = (
        good_checked == len(PROJECTION_FILES)
        and bad_checked == len(PROJECTION_FILES)
        and not good_hits
        and len(bad_hits) == len(PROJECTION_FILES)
        and all(lineno == 1 for _, lineno, _ in bad_hits)
    )
    print("[selftest] 绿样本命中=%d（须 0）；红样本命中=%d（须每文件 1 处且行号=1）"
          % (len(good_hits), len(bad_hits)))
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print("[selftest] PASS：判据可分辨合格 / 违规两种形态")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="投影面裸敏感读取复核（ISSUE-P0-531）")
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, checked = scan(root)
    print("[check_projection_read_safety] 投影面文件 %d 个（清单 %d 项）；裸读命中 %d 处"
          % (checked, len(PROJECTION_FILES), len(hits)))
    for rel, lineno, text in hits:
        print("  HIT %s:%d  %s" % (rel, lineno, text))
    if hits:
        print("[check_projection_read_safety] FAIL：投影面必须走 readStringForDisplay / "
              "readUtf8ForDisplay（理由见脚本 docstring；写路径不在本判据范围内）")
        return 1
    print("[check_projection_read_safety] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
