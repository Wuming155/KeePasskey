"""投影面敏感读取安全复核（`ISSUE-P0-531` / `ISSUE-P2-534` 的机检化）。

## 为何存在

`ProtectedString` 是**可变的共享引用**：会话层在整树替换时会对「被替换下线」的实例**就地清零**
（`KdbxGroup.clearSupersededSensitiveData`，身份集合判定）；而 UI 投影 / 检索链
（`VaultEntryQueryCoordinator.entriesFlow()` 的 `flowOn(Dispatchers.Default)`、自动填充检索与排序）
与那些擦除点**不共享锁**，存在「旧值已分发 → 擦除发生 → 读取才执行」的调度窗口。

2026-10-08 真机实测（`M332BF`，`adb logcat -b crash` + R8 mapping 还原）：该窗口内
`VaultEntryMapper.mapKdbxEntryToUi` 读 `KdbxEntry.getUserName()`（其 `fields[...]` 实例已被清零）
⇒ `ProtectedString.checkNotCleared()` 抛 `IllegalStateException: ProtectedString 已经清零，禁止继续访问`
⇒ 逃逸到协程根（当时全仓无 `CoroutineExceptionHandler`）⇒ 进程闪退（10 秒内连崩三次）。

## 三条判据（任一命中即退出码 1）

1. **投影面不得裸读 fail-fast 读口**：登记在案（见 `PROJECTION_FILES`）的文件里出现
   `x.readString()` / `x.readUtf8()` / `x.readChars()` / `useChars {` / `useUtf8 {` 即红
   —— 必须改走展示面读口。
2. **投影面不得引用 `KdbxEntry` 的四个 fail-fast getter**：`.title` / `.userName` / `.url` / `.notes`
   即红（例外见 `GETTER_EXEMPT_SNIPPETS`）。**这是最可能的复发形态**：真机崩溃点走的正是 getter
   （`KdbxEntry.getUserName` 内部即 `readString()`），把 `entry.displayUserName()`「顺手改回」
   `entry.userName` 语义完全等价、而规则 1 看不见（getter 定义在 core，不在投影面文件里）。
3. **展示读口反向白名单（fail-closed）**：仓内**任何**非测试文件若调用展示面读口
   （`displayTitle` / `displayUserName` / `displayUrl` / `displayNotes` / `readStringForDisplay` /
   `readStringForDisplayOrEmpty` / `readUtf8ForDisplay`），其文件必须在 `ALLOWED_DISPLAY_READ_FILES`
   登记；**新调用点未登记即红**。反向风险是真实存在的：这些读口是 **fail-open**（读不到即空串），
   一旦被用在序列化 / 保存 / 合并 / 导出 / 凭据下发等写路径，就会把空值写进用户的库。

## 口径与边界（**不得**据其绿推定「全仓已无裸读」）

* 规则 2 只覆盖四个**标准字段** getter；受保护**自定义字段**（`KdbxCustomField.value.readString()`）
  由规则 1 覆盖（裸读），但经变量间接传递 `ProtectedString` 再读的形态**看不见**。
* 规则 2 的接收者类型靠**行内豁免片段**（`GETTER_EXEMPT_SNIPPETS`）区分：`VaultEntryMapper` 里
  `mapUiEntryToKdbx`（写方向：`UiVaultEntry` → `KdbxEntry`）读的是 **UI 模型**的 `String` 字段，
  与 `KdbxEntry` 同名但无关。豁免片段一旦失配（该代码被改写/搬走），闸门会在原处重新判红——
  届时请**删除陈旧豁免**，而不是放宽判据。
* 规则 3 的扫描面排除 `*/src/test/**`、`*/src/androidTest/**`、`build/`、`参考项目/`（测试与生成物
  不参与生产数据路径；参考项目只读）。
* 注释行（`//` 与 KDoc 行 `*`）一律跳过——判据盯的是**调用**，不是文档里的提及。
* 用法：`python tools/doc/check_projection_read_safety.py [--root 目录] [--selftest]`；
  命中即退出码 1（无命中退 0）；`--selftest` 用内嵌正 / 反样本反校三条判据本身。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# 投影面文件清单（相对仓库根）。判据与增减纪律见模块 docstring。
PROJECTION_FILES = (
    "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt",
    "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt",
    # `ISSUE-P2-534`（§473 复核 #2）：消费面不止条目投影 —— 检索 / 排序 / 选择器 / 交付装配 /
    # 差异展示都在 `Dispatchers.Default` 上遍历条目、与会话整树替换的就地擦除并发，
    # 一律纳入「不得裸读 / 不得引 fail-fast getter」的判据面。
    "app/src/main/java/com/keepasskey/app/autofill/AutofillEntrySearch.kt",
    "app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt",
    "app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt",
    "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerScreen.kt",
    "app/src/main/java/com/keepasskey/app/autofill/AutofillStructuredDatasets.kt",
    "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockActivity.kt",
    "app/src/main/java/com/keepasskey/app/autofill/DuplicateEntryScanner.kt",
    "app/src/main/java/com/keepasskey/app/ui/screens/conflict/ConflictResolutionViewModel.kt",
)

# 规则 1：裸 fail-fast 读口。刻意要求 `()` 紧跟方法名——`readStringForDisplay()` 因方法名更长而**不会**命中。
BARE_READ_RE = re.compile(r"\.(?:readString|readUtf8|readChars)\(\s*\)")
# 规则 1 续：fail-fast 闭包读口（内部即 readChars / readUtf8）。
USE_CLOSURE_RE = re.compile(r"\buse(?:Chars|Utf8)\s*(?:\(\s*\))?\s*\{")

# 规则 2：`KdbxEntry` 的四个 fail-fast getter（名称与 UI 模型同名，故配合行内豁免区分接收者）。
FAIL_FAST_GETTER_RE = re.compile(r"\.(?:title|userName|url|notes)\b")
# (相对路径, 行内需出现的子串, 理由)。命中行含该子串即豁免。
GETTER_EXEMPT_SNIPPETS = (
    (
        "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt",
        "ProtectedString(entry.title",
        "写方向（UiVaultEntry → KdbxEntry）：接收者是 UI 模型的 String 字段，非 KdbxEntry",
    ),
    (
        "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt",
        "ProtectedString(entry.url",
        "写方向（UiVaultEntry → KdbxEntry）：接收者是 UI 模型的 String 字段，非 KdbxEntry",
    ),
    (
        "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt",
        "ProtectedString(entry.notes",
        "写方向（UiVaultEntry → KdbxEntry）：接收者是 UI 模型的 String 字段，非 KdbxEntry",
    ),
)

# 规则 3：展示面读口（fail-open）。新增调用点必须登记到白名单。
DISPLAY_READ_RE = re.compile(
    r"\b(?:displayTitle|displayUserName|displayUrl|displayNotes"
    r"|read(?:String|Utf8|Chars)ForDisplay(?:OrEmpty|OrNull)?)\s*\("
)
ALLOWED_DISPLAY_READ_FILES = frozenset(
    {
        # 读口的定义处
        "core/src/main/java/com/keepasskey/core/security/ProtectedString.kt",
        "core/src/main/java/com/keepasskey/core/security/ProtectedStringDisplayReads.kt",
        "core/src/main/java/com/keepasskey/core/model/KdbxEntry.kt",
        # 展示 / 检索 / 差异 / 交付面
        "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt",
        "app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt",
        "app/src/main/java/com/keepasskey/app/ui/screens/conflict/ConflictResolutionViewModel.kt",
        "app/src/main/java/com/keepasskey/app/autofill/AutofillEntrySearch.kt",
        "app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt",
        "app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt",
        "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerScreen.kt",
        "app/src/main/java/com/keepasskey/app/autofill/AutofillStructuredDatasets.kt",
        "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockActivity.kt",
        "app/src/main/java/com/keepasskey/app/autofill/DuplicateEntryScanner.kt",
    }
)

# 规则 3 的扫描排除面（测试源集与生成物不参与生产数据路径；参考项目只读）。
EXCLUDED_PARTS = ("/src/test/", "/src/androidTest/", "/build/", "参考项目")


def _is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def _exempt_getter(rel: str, line: str) -> bool:
    return any(f == rel and snippet in line for f, snippet, _ in GETTER_EXEMPT_SNIPPETS)


def scan(root: pathlib.Path) -> tuple[list[str], int, int]:
    """返回 (命中清单, 检查过的投影面文件数, 检查过的展示读口文件数)。"""
    hits: list[str] = []
    projection_checked = 0

    for rel in PROJECTION_FILES:
        path = root / rel
        if not path.is_file():
            hits.append("RULE0 %s：清单登记的投影面文件不存在（路径漂移或已搬迁）" % rel)
            continue
        projection_checked += 1
        for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if _is_comment(raw):
                continue
            if BARE_READ_RE.search(raw) or USE_CLOSURE_RE.search(raw):
                hits.append("RULE1 %s:%d  %s" % (rel, lineno, raw.strip()))
            if FAIL_FAST_GETTER_RE.search(raw) and not _exempt_getter(rel, raw):
                hits.append("RULE2 %s:%d  %s" % (rel, lineno, raw.strip()))

    display_files = 0
    for path in sorted(root.rglob("*.kt")):
        rel = path.relative_to(root).as_posix()
        if any(part in ("/" + rel) or part in rel for part in EXCLUDED_PARTS):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if not DISPLAY_READ_RE.search(text):
            continue
        display_files += 1
        if rel in ALLOWED_DISPLAY_READ_FILES:
            continue
        for lineno, raw in enumerate(text.splitlines(), 1):
            if _is_comment(raw) or not DISPLAY_READ_RE.search(raw):
                continue
            hits.append("RULE3 %s:%d  %s" % (rel, lineno, raw.strip()))
            break

    return hits, projection_checked, display_files


# `--selftest` 内嵌样本：三条判据各验一次（绿样本须 0 命中，红样本须各命中一次）。
SELFTEST_GOOD = (
    "package demo\n",
    "val n = entry.displayUserName()\n",
    "val d = seed.readUtf8ForDisplay()\n",
)
SELFTEST_BAD = (
    "package demo\n",
    "val n = entry.userName\n",  # RULE2
    "val t = entry.displayTitle().ifBlank { entry.title }\n",  # RULE2
    "val s = x.readString()\n",  # RULE1
    "val c = x.useUtf8 { it }\n",  # RULE1（闭包读口）
)


def _write_fixture(base: pathlib.Path, projection_body: str) -> pathlib.Path:
    for rel in PROJECTION_FILES:
        target = base / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(projection_body, encoding="utf-8")
    # 一个**已登记**的展示读口文件（刻意避开 PROJECTION_FILES —— 否则会覆盖上面写入的样本文件，
    # 使红样本的 RULE1/RULE2 少算一份，实测就是这样把 selftest 判红的）
    registered = base / sorted(ALLOWED_DISPLAY_READ_FILES - set(PROJECTION_FILES))[0]
    registered.parent.mkdir(parents=True, exist_ok=True)
    registered.write_text("val a = p.readStringForDisplay()\n", encoding="utf-8")
    return base


def selftest() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        good_root = _write_fixture(pathlib.Path(tmp) / "good", "".join(SELFTEST_GOOD))
        good_hits, good_proj, _ = scan(good_root)
        bad_root = _write_fixture(pathlib.Path(tmp) / "bad", "".join(SELFTEST_BAD))
        bad_hits, bad_proj, _ = scan(bad_root)
        # RULE3 红样本：新增一个未登记的展示读口调用点
        new_file = pathlib.Path(tmp) / "bad" / "app/src/main/java/demo/NewUser.kt"
        new_file.parent.mkdir(parents=True, exist_ok=True)
        new_file.write_text("val v = x.readStringForDisplay()\n", encoding="utf-8")
        rule3_hits, _, _ = scan(bad_root)

    rule1_in_bad = [h for h in bad_hits if h.startswith("RULE1")]
    rule2_in_bad = [h for h in bad_hits if h.startswith("RULE2")]
    rule3_in_bad = [h for h in rule3_hits if h.startswith("RULE3")]
    expect_rule1 = len(PROJECTION_FILES) * 2
    expect_rule2 = len(PROJECTION_FILES) * 2
    ok = (
        good_proj == len(PROJECTION_FILES)
        and bad_proj == len(PROJECTION_FILES)
        and not good_hits
        and len(rule1_in_bad) == expect_rule1
        and len(rule2_in_bad) == expect_rule2
        and len(rule3_in_bad) == 1
    )
    print(
        "[selftest] 绿样本命中=%d（须 0）；红样本 RULE1=%d（须 %d）、RULE2=%d（须 %d）、RULE3=%d（须 1）"
        % (
            len(good_hits),
            len(rule1_in_bad),
            expect_rule1,
            len(rule2_in_bad),
            expect_rule2,
            len(rule3_in_bad),
        )
    )
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print("[selftest] PASS：三条判据均可分辨合格 / 违规形态")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="投影面敏感读取安全复核（ISSUE-P0-531 / P2-534）")
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, projection_checked, display_files = scan(root)
    print(
        "[check_projection_read_safety] 投影面文件 %d/%d；展示读口文件 %d 个（白名单 %d）；命中 %d 处"
        % (projection_checked, len(PROJECTION_FILES), display_files, len(ALLOWED_DISPLAY_READ_FILES), len(hits))
    )
    for hit in hits:
        print("  HIT " + hit)
    if hits:
        print(
            "[check_projection_read_safety] FAIL：投影面必须走展示面读口（`displayTitle()` 等 / "
            "`readStringForDisplay()`）；展示读口的**新调用点**须登记白名单（fail-open 读口用在写路径会把空值写进库）"
        )
        return 1
    print("[check_projection_read_safety] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
