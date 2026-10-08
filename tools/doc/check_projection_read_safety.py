"""投影面 / 解析面敏感读取安全复核（`ISSUE-P0-531` / `ISSUE-P2-534` / `ISSUE-P1-537` / `ISSUE-P2-539` 的机检化）。

## 为何存在

`ProtectedString` 是**可变的共享引用**：会话层在整树替换时会对「被替换下线」的实例**就地清零**
（`KdbxGroup.clearSupersededSensitiveData`，身份集合判定）；而 UI 投影 / 检索链
（`VaultEntryQueryCoordinator.entriesFlow()` 的 `flowOn(Dispatchers.Default)`、自动填充检索与排序）
与那些擦除点**不共享锁**，存在「旧值已分发 → 擦除发生 → 读取才执行」的调度窗口。

2026-10-08 真机实测（`M332BF`，`adb logcat -b crash` + R8 mapping 还原）：该窗口内
`VaultEntryMapper.mapKdbxEntryToUi` 读 `KdbxEntry.getUserName()`（其 `fields[...]` 实例已被清零）
⇒ `ProtectedString.checkNotCleared()` 抛 `IllegalStateException: ProtectedString 已经清零，禁止继续访问`
⇒ 逃逸到协程根（当时全仓无 `CoroutineExceptionHandler`）⇒ 进程闪退（10 秒内连崩三次）。

## 五条判据（任一命中即退出码 1）

1. **登记面不得裸读 fail-fast 读口**：登记在案（见 `PROJECTION_FILES`）的文件里出现
   `x.readString()` / `x.readUtf8()` / `x.readChars()` / `useChars {` / `useUtf8 {` 即红
   —— 必须改走展示面读口（例外见 `RULE1_EXEMPT_SNIPPETS`）。
2. **登记面不得引用 `KdbxEntry` 的四个 fail-fast getter**：`.title` / `.userName` / `.url` / `.notes`
   即红（例外见 `GETTER_EXEMPT_SNIPPETS`）。**这是最可能的复发形态**：真机崩溃点走的正是 getter
   （`KdbxEntry.getUserName` 内部即 `readString()`），把 `entry.displayUserName()`「顺手改回」
   `entry.userName` 语义完全等价、而规则 1 看不见（getter 定义在 core，不在投影面文件里）。
3. **展示读口反向白名单（fail-closed）**：仓内**任何**非测试文件若调用展示面读口
   （`displayTitle` / `displayUserName` / `displayUrl` / `displayNotes` / `readStringForDisplay` /
   `readStringForDisplayOrEmpty` / `readStringForDisplayOrNull` / `readUtf8ForDisplay` /
   `readCharsForDisplayOrNull` / `useUtf8ForDisplayOrNull`），其文件必须在 `ALLOWED_DISPLAY_READ_FILES`
   登记；**新调用点未登记即红**。反向风险是真实存在的：这些读口是 **fail-open**（读不到即空串 / null），
   一旦被用在序列化 / 保存 / 合并 / 导出 / 凭据下发等写路径，就会把空值写进用户的库。
4. **「先查 `cleared` 再裸读」的两步式形态（`ISSUE-P1-537` / `ISSUE-P2-540` 裁决②）**：
   `takeUnless { … .cleared }` / `takeIf { !….cleared }` 即红。两步之间**不是原子的**——标志尚不可见
   时读侧仍会走到 fail-fast 读并抛（`ISSUE-P1-537` 的 TOCTOU 即此形态）。合法需求改走**单点判定**读口
   （`readStringForDisplay*` / `ProtectedString.takeIfReadable()`——判据与状态同处一类）。
5. **非交付面对 `password` 字段使用 fail-open 读口（`ISSUE-P2-539` ⑤）**：形如
   `….password?.readStringForDisplay()` 的**字段访问器**直读在 `DELIVERY_FACE_FILES` 之外的文件里即红
   —— 交付面已按「`cleared` 精确预判 + 读后复核」承担该降级（登记豁免，理由见该清单），
   其余面若 fail-open 读口令，会把「不可读」折叠成空串而误判「该条目不携带口令」。

## 口径与边界（**不得**据其绿推定「全仓已无裸读 / 无两步式」）

* 规则 2 只覆盖四个**标准字段** getter；受保护**自定义字段**（`KdbxCustomField.value.readString()`）
  由规则 1 覆盖（裸读），但经变量间接传递 `ProtectedString` 再读的形态**看不见**。
* 规则 2 的接收者类型靠**行内豁免片段**（`GETTER_EXEMPT_SNIPPETS`）区分：`VaultEntryMapper` 里
  `mapUiEntryToKdbx`（写方向：`UiVaultEntry` → `KdbxEntry`）读的是 **UI 模型**的 `String` 字段，
  与 `KdbxEntry` 同名但无关。豁免片段一旦失配（该代码被改写/搬走），闸门会在原处重新判红——
  届时请**删除陈旧豁免**，而不是放宽判据。
* 规则 4 只钉**文本形态**：把同样的两步式藏进「先 `if (x.cleared) …` 再读」的展开写法**看不见**。
  它的价值是让最常见的写法无法悄悄回归；真正的安全性由「所有读口判据落在读取自身」保证。
* 规则 5 只钉**字段访问器直读**（`….password?.readXForDisplay()`）；把口令实例先赋给局部变量再
  fail-open 读的形态**看不见**（`AutofillUnlockedCandidates` 即此形态，其保守判据由用例锁定）。
* 规则 3/4/5 的扫描面排除 `*/src/test/**`、`*/src/androidTest/**`、`build/`、`参考项目/`（测试与生成物
  不参与生产数据路径；参考项目只读）。
* 注释行（`//` 与 KDoc 行 `*`）一律跳过——判据盯的是**调用**，不是文档里的提及。
* 用法：`python tools/doc/check_projection_read_safety.py [--root 目录] [--selftest]`；
  命中即退出码 1（无命中退 0）；`--selftest` 用内嵌正 / 反样本反校五条判据本身。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# 登记面（相对仓库根）。判据与增减纪律见模块 docstring。由「投影面」扩为
# 「投影 / 检索 / 交付 / 解析」共用的敏感读取面（`ISSUE-P2-534` §474 起，`ISSUE-P1-537` /
# `ISSUE-P2-539` §477 续扩：解析面 `PasskeyData` 与填充 / 结构策略两文件同属消费面）。
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
    # `ISSUE-P1-537`：通行密钥解析面（`fromCustomFields` 逐字段读取自定义字段）；
    # `ISSUE-P2-539`：填充策略面（口令存在性探测 / 结构化取值）。
    "core/src/main/java/com/keepasskey/core/model/PasskeyData.kt",
    "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockedCandidates.kt",
    "app/src/main/java/com/keepasskey/app/autofill/StructuredFieldPolicy.kt",
)

# 规则 1：裸 fail-fast 读口。刻意要求 `()` 紧跟方法名——`readStringForDisplay()` 因方法名更长而**不会**命中。
BARE_READ_RE = re.compile(r"\.(?:readString|readUtf8|readChars)\(\s*\)")
# 规则 1 续：fail-fast 闭包读口（内部即 readChars / readUtf8）。
USE_CLOSURE_RE = re.compile(r"\buse(?:Chars|Utf8)\s*(?:\(\s*\))?\s*\{")
# (相对路径, 行内需出现的子串, 理由)。命中行含该子串即豁免。
RULE1_EXEMPT_SNIPPETS = (
    (
        "core/src/main/java/com/keepasskey/core/model/PasskeyData.kt",
        "firstOrNull { it.key == FIELD_SIGN_COUNT }",
        "写侧计数器读取（断言递增前取库内现值）：此处 fail-fast 是**正确契约**——擦除时递增必须失败，"
        "绝不 fail-open 取到缺省值再写回（那会把单调计数器**降级**）",
    ),
)

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
    r"|read(?:String|Utf8|Chars)ForDisplay(?:OrEmpty|OrNull)?"
    r"|useUtf8ForDisplay(?:OrEmpty|OrNull)?)\s*\("
)
ALLOWED_DISPLAY_READ_FILES = frozenset(
    {
        # 读口的定义处
        "core/src/main/java/com/keepasskey/core/security/ProtectedString.kt",
        "core/src/main/java/com/keepasskey/core/security/ProtectedStringDisplayReads.kt",
        "core/src/main/java/com/keepasskey/core/model/KdbxEntry.kt",
        "core/src/main/java/com/keepasskey/core/model/PasskeyData.kt",
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
        "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockedCandidates.kt",
        "app/src/main/java/com/keepasskey/app/autofill/StructuredFieldPolicy.kt",
    }
)

# 规则 4：「先查 cleared 再裸读」的两步式形态（`ISSUE-P1-537` / `ISSUE-P2-540` 裁决②）。
TWO_STEP_RE = re.compile(r"\btake(?:Unless|If)\s*\{[^{}]*\.cleared\b")

# 规则 5：非交付面对 `password` 字段的 fail-open 直读（`ISSUE-P2-539` ⑤）。
PASSWORD_FAIL_OPEN_RE = re.compile(
    r"\.password\s*\??\.\s*(?:read(?:String|Utf8|Chars)ForDisplay\w*|useUtf8ForDisplay\w*)\s*\("
)
# 交付面豁免清单：这些文件已按「`cleared` 精确预判 + 读后复核 + 整条跳过」承担口令降级语义
# （`AutofillDatasetBuilders.buildCandidateDataset`：先 `hasClearedFields()` 粗判、再按 `PASSWORD`
# 键自身 `cleared` 精判，两次都走「返回 null ⇒ 不交付半份」同一出口）。理由写在此处而非注释在调用点，
# 以免「为什么这处 fail-open 读是安全的」在代码里被误当作普遍允许。
DELIVERY_FACE_FILES = frozenset(
    {
        "app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt",
    }
)

# 规则 3/4/5 的扫描排除面（测试源集与生成物不参与生产数据路径；参考项目只读）。
EXCLUDED_PARTS = ("/src/test/", "/src/androidTest/", "/build/", "参考项目")


def _is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def _exempt_getter(rel: str, line: str) -> bool:
    return any(f == rel and snippet in line for f, snippet, _ in GETTER_EXEMPT_SNIPPETS)


def _exempt_rule1(rel: str, line: str) -> bool:
    return any(f == rel and snippet in line for f, snippet, _ in RULE1_EXEMPT_SNIPPETS)


def scan(root: pathlib.Path) -> tuple[list[str], int, int]:
    """返回 (命中清单, 检查过的登记面文件数, 检查过的展示读口文件数)。"""
    hits: list[str] = []
    projection_checked = 0

    for rel in PROJECTION_FILES:
        path = root / rel
        if not path.is_file():
            hits.append("RULE0 %s：清单登记的读取面文件不存在（路径漂移或已搬迁）" % rel)
            continue
        projection_checked += 1
        for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if _is_comment(raw):
                continue
            if (BARE_READ_RE.search(raw) or USE_CLOSURE_RE.search(raw)) and not _exempt_rule1(rel, raw):
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
        registered = rel in ALLOWED_DISPLAY_READ_FILES
        is_delivery_face = rel in DELIVERY_FACE_FILES
        if DISPLAY_READ_RE.search(text):
            display_files += 1
        rule3_reported = False
        for lineno, raw in enumerate(text.splitlines(), 1):
            if _is_comment(raw):
                continue
            if (not registered and not rule3_reported and DISPLAY_READ_RE.search(raw)):
                hits.append("RULE3 %s:%d  %s" % (rel, lineno, raw.strip()))
                rule3_reported = True
            if TWO_STEP_RE.search(raw):
                hits.append("RULE4 %s:%d  %s" % (rel, lineno, raw.strip()))
            if not is_delivery_face and PASSWORD_FAIL_OPEN_RE.search(raw):
                hits.append("RULE5 %s:%d  %s" % (rel, lineno, raw.strip()))

    return hits, projection_checked, display_files


# `--selftest` 内嵌样本：五条判据各验一次（绿样本须 0 命中，红样本各命中预期次数）。
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
# 恶化层（只写入**已登记**的展示读口文件，故不触发 RULE3）：RULE5 的字段访问器直读 + RULE4 的两步式。
SELFTEST_BAD_REGISTERED = (
    "val a = p.readStringForDisplay()\n",
    "val b = entry.password?.readStringForDisplay()\n",  # RULE5
    "val c = x.takeUnless { it.cleared }\n",  # RULE4
)


def _write_fixture(base: pathlib.Path, body: str, registered_body: str) -> pathlib.Path:
    for rel in PROJECTION_FILES:
        target = base / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding="utf-8")
    # 一个**已登记**的展示读口文件（刻意避开 PROJECTION_FILES —— 否则会覆盖上面写入的样本文件，
    # 使红样本的 RULE1/RULE2 少算一份，实测就是这样把 selftest 判红的）
    registered = base / sorted(ALLOWED_DISPLAY_READ_FILES - set(PROJECTION_FILES))[0]
    registered.parent.mkdir(parents=True, exist_ok=True)
    registered.write_text(registered_body, encoding="utf-8")
    return base


def selftest() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        good_root = _write_fixture(
            pathlib.Path(tmp) / "good", "".join(SELFTEST_GOOD), "val a = p.readStringForDisplay()\n"
        )
        good_hits, good_proj, _ = scan(good_root)
        bad_root = _write_fixture(
            pathlib.Path(tmp) / "bad", "".join(SELFTEST_BAD), "".join(SELFTEST_BAD_REGISTERED)
        )
        bad_hits, bad_proj, _ = scan(bad_root)
        # RULE3 红样本：新增一个未登记的展示读口调用点
        new_file = pathlib.Path(tmp) / "bad" / "app/src/main/java/demo/NewUser.kt"
        new_file.parent.mkdir(parents=True, exist_ok=True)
        new_file.write_text("val v = x.readStringForDisplay()\n", encoding="utf-8")
        rule3_hits, _, _ = scan(bad_root)

    rule1_in_bad = [h for h in bad_hits if h.startswith("RULE1")]
    rule2_in_bad = [h for h in bad_hits if h.startswith("RULE2")]
    rule3_in_bad = [h for h in rule3_hits if h.startswith("RULE3")]
    rule4_in_bad = [h for h in bad_hits if h.startswith("RULE4")]
    rule5_in_bad = [h for h in bad_hits if h.startswith("RULE5")]
    expect_rule1 = len(PROJECTION_FILES) * 2
    expect_rule2 = len(PROJECTION_FILES) * 2
    ok = (
        good_proj == len(PROJECTION_FILES)
        and bad_proj == len(PROJECTION_FILES)
        and not good_hits
        and len(rule1_in_bad) == expect_rule1
        and len(rule2_in_bad) == expect_rule2
        and len(rule3_in_bad) == 1
        and len(rule4_in_bad) == 1
        and len(rule5_in_bad) == 1
    )
    print(
        "[selftest] 绿样本命中=%d（须 0）；红样本 RULE1=%d（须 %d）、RULE2=%d（须 %d）、"
        "RULE3=%d（须 1）、RULE4=%d（须 1）、RULE5=%d（须 1）"
        % (
            len(good_hits),
            len(rule1_in_bad),
            expect_rule1,
            len(rule2_in_bad),
            expect_rule2,
            len(rule3_in_bad),
            len(rule4_in_bad),
            len(rule5_in_bad),
        )
    )
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print("[selftest] PASS：五条判据均可分辨合格 / 违规形态")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="投影 / 解析面敏感读取安全复核（ISSUE-P0-531 / P2-534 / P1-537 / P2-539）"
    )
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, projection_checked, display_files = scan(root)
    print(
        "[check_projection_read_safety] 登记面文件 %d/%d；展示读口文件 %d 个（白名单 %d）；命中 %d 处"
        % (
            projection_checked,
            len(PROJECTION_FILES),
            display_files,
            len(ALLOWED_DISPLAY_READ_FILES),
            len(hits),
        )
    )
    for hit in hits:
        print("  HIT " + hit)
    if hits:
        print(
            "[check_projection_read_safety] FAIL：消费面必须走单点判定的安全读口"
            "（`displayTitle()` / `readStringForDisplayOrNull()` 等）；展示读口的**新调用点**须登记白名单；"
            "禁止「先查 `cleared` 再裸读」的两步式；非交付面禁止 fail-open 直读 `password`"
        )
        return 1
    print("[check_projection_read_safety] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
