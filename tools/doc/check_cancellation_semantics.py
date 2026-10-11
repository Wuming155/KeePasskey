"""协程上下文中的 `catch (Throwable|Exception)` 与裸 `runCatching { }` 必须显式处理取消（机检）。

## 为何存在

`kotlinx.coroutines.CancellationException` 在 JVM 上是 `java.util.concurrent.CancellationException`
的别名，而后者继承 `IllegalStateException` ⇒ 裸 `catch (t: Throwable)` / `catch (e: Exception)` /
裸 `runCatching { }` 会把它一并吞掉。取消被归一为业务失败后，上层会拿到看似正常的失败值
（`KdbxResult.Failure` / `Result.failure` / 空列表）并按**业务失败**处置——一次「用户退出页面 /
换库」被记成一次操作失败，结构化并发的收敛契约同时被破坏（`runCatchingCancellable` 的立规意图见
`sync/src/main/java/com/keepasskey/sync/network/CancellationAwareRunCatching.kt`）。

`ISSUE-P3-555`（§482）只收敛了 `sync` 模块的 provider / 上传重试与 `markResolvedAndUpload`；
`ISSUE-P3-568`（§483）只补了 `SettingsKdfBenchmarkController` 一处；`ISSUE-P3-569`（§484）把
`catch` 面逐处收口并立起本机检；`ISSUE-P3-570`（§486）把 **`runCatching` 面**并入本机检
（同型语义：取消被 `Result.failure` / 默认值归一），并把 **`LaunchedEffect`**（body 恒为
挂起 lambda）补进协程构造——该构造缺失曾使 §484 的 catch 面漏判 `TotpScanDialog` 相机启动一处。

## 判据（命中即退出码 1）

扫描面＝**五个生产模块**（`MODULES`）各自 `src/main/**` 下的 `.kt`（与 `count_line_tiers.py` 同口径，
**不用 `*/src/main/**` 通配**——通配会把未跟踪的只读参考树 `参考项目/` 扫进来，读数不可复现，§479 更正）。

**catch 面**：对每个 `catch (<v>: Throwable)` / `catch (<v>: Exception)` 子句，**同时**满足以下四条即命中：

1. **处于协程上下文**：自该子句向上找到最近的非注释行，若它先命中协程构造
   （`suspend fun` / `launch` / `async` / `withContext` / `runBlocking` / `coroutineScope` /
   `supervisorScope` / `channelFlow` / `callbackFlow` / `produce` / `LaunchedEffect`）即为是；
   先命中普通 `fun` 即为否。
2. **自身子句体未保留取消**：子句体（按花括号配对取值）内既不含 `CancellationException`，
   也不含「原样重抛」`throw <被捕获变量>`（`throw t` / `throw ex` 已保留取消语义）。
3. **同 `try` 的前置兄弟分支未处理取消**：自该子句向上找到最近的 `try {`，若「try 行 → 本子句行」
   之间已存在 `catch (... : CancellationException)` 兄弟分支，视为已覆盖（**这正是
   `SyncGuardedLaunch` 的正确形态**，`ISSUE-P3-569` 明令判据不得误报它）。
4. **该点位未登记「不适用」**：`catch` 行或**其上一行**含标注 `cancel-n/a:`（其后写理由）。

**runCatching 面**（`ISSUE-P3-570`）：对每个裸 `runCatching` **调用**（词边界；`runCatchingCancellable`
因后随字母不匹配词边界而天然放行），**同时**满足以下四条即命中：

1. **处于协程上下文**：同上（与 catch 面同一判据函数）。
2. **保护段及后缀链未保留取消**：自调用行起做 `{`/`}` 与 `(`/`)` **配平走查**取整个表达式区域
   （`runCatching { … }.getOrElse { … }` / `.onFailure { … }` 的后缀链因此整体入区域），
   区域内不含 `CancellationException`。
3. **非声明行**：`fun … runCatching(` 形态的**声明**（如 `KdbxResult` 伴生的自定义包装）不算调用。
4. **该点位未登记「不适用」**：调用行或其上一行含 `cancel-n/a:`。

## 「不适用」标注（`cancel-n/a:`）

判为不适用者（保护段内**无挂起点**，取消只会在该保护段边界外观测 ⇒ 补守卫是死代码）在该行尾
（或上一行）写 `// cancel-n/a: <理由>`，理由**必须**说明「保护段为何不含挂起点」。两面共用同一标注；
标注随代码走，故**新增**同型点不会被静默放行（fail-closed）；理由一览同时登记在批次文档。

## 口径声明（**不得**据其绿推定「全仓已无吞取消面」）

本机检只钉**子句形态与 `runCatching` 调用形态**，看不见：① 经自定义包装函数间接吞掉取消
（`KdbxResult.runCatching` 已于 `ISSUE-P3-570` 改为重抛取消；新增同名包装不受本判据约束）；
② 子句体 / 链式 handler 虽提到 `CancellationException` 却未真正重抛（区域文本匹配，宽于语义）；
③ 挂起 lambda（`suspend (T) -> R`）参数体内的 `catch` / `runCatching`——其上溯只见到普通 `fun`，
本判据会**漏判**（假阴性）；④ `runCatching` 的处理写在**非链式**的后续语句里（区域只覆盖
表达式自身的配平范围）；⑤ 跨行拼接的 `try` / `catch` 写法与块注释内的不配对括号（按行取值、
单行掩码的既有限界）；⑥ `DisposableEffect` / `remember { }` 等**非挂起** lambda 体不属协程语境
（取消不可达，无需守卫）。语境上溯的「最近行命中」语义对「`launch { }` 收口之后的后续语句」
仍会判为协程语境——该结构误报由 `cancel-n/a:` 标注吸收（§484 同法）。

用法：`python tools/doc/check_cancellation_semantics.py [--root 目录] [--selftest]`；
命中即退出码 1（无命中退 0）；`--selftest` 用内嵌正 / 反样本反校判据本身。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# 扫描面：五个生产模块的 `src/main`（与 count_line_tiers.py / check_raw_coroutine_scope.py 同口径）。
MODULES = ("app", "database", "crypto", "sync", "core")

# 目标子句：`catch (<v>: Throwable)` / `catch (<v>: Exception)`。
CATCH_RE = re.compile(r"\bcatch\s*\(\s*([A-Za-z_][A-Za-z0-9_]*|_)\s*:\s*(Throwable|Exception)\s*\)")

# 裸 `runCatching` 调用（词边界；`runCatchingCancellable` 后随字母不匹配 `\s*[\(\{]` 而天然放行）。
RUN_CATCHING_RE = re.compile(r"\brunCatching\s*[\(\{]")

# `runCatching` 的**声明**形态（`fun <T> runCatching(…)` / `fun R.runCatching(…)`）不算调用。
RUN_CATCHING_DECL_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s+)?[\w.]*\.?runCatching\s*[\(\{]")

# 协程上下文构造（命中即视为「处于协程上下文」）。
# `LaunchedEffect` 的 body 恒为挂起 lambda（`ISSUE-P3-570` 补入；`DisposableEffect` body 非挂起，不收）。
COROUTINE_RE = re.compile(
    r"\b(suspend\s+fun"
    r"|launch\s*[\(\{]"
    r"|async\s*[\(\{]"
    r"|withContext\s*\("
    r"|runBlocking\s*\{"
    r"|coroutineScope\s*\{"
    r"|supervisorScope\s*\{"
    r"|channelFlow\b"
    r"|callbackFlow\b"
    r"|produce\s*\("
    r"|LaunchedEffect\s*[\(\{])"
)

# 普通函数声明（语境上溯的终止条件）。
PLAIN_FUN_RE = re.compile(r"\bfun\s")

# 拥有本子句的 `try`（允许 `val x = try {` 与裸 `try {` 两种形态）。
TRY_RE = re.compile(r"\btry\s*\{")

# 兄弟取消分支（前置即视为已覆盖）。允许全限定名（`kotlinx.coroutines.CancellationException`）。
SIBLING_CANCEL_RE = re.compile(
    r"\bcatch\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*:\s*"
    r"(?:[A-Za-z_][A-Za-z0-9_]*\.)*CancellationException\s*\)"
)

# 「不适用」标注。
MARKER = "cancel-n/a:"


def _is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def _mask(line: str) -> str:
    """抹掉注释与字符串字面量，避免花括号计数被其内容干扰（单行级，够用即可）。"""
    out = []
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if c == "/" and i + 1 < n and line[i + 1] == "/":
            break
        if c == '"':
            i += 1
            while i < n and line[i] != '"':
                if line[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _brace_span(lines: list[str], start: int) -> int:
    """`start` 行含 `{`；返回配对 `}` 所在行号（找不到则返回末行）。"""
    depth = 0
    seen = False
    for i in range(start, len(lines)):
        if _is_comment(lines[i]):
            continue
        for ch in _mask(lines[i]):
            if ch == "{":
                depth += 1
                seen = True
            elif ch == "}" and seen:
                # `} catch (...) {` 形态的前导 `}` 属于上一块，计入会立刻误判「体已结束」
                depth -= 1
        if seen and depth <= 0:
            return i
    return len(lines) - 1


def _expression_end(lines: list[str], start: int, cap: int = 200) -> int:
    """`runCatching` 调用表达式的结束行：自 `start` 起 `{}`/`()` 配平走查，归零即止。

    `runCatching { … }.getOrElse { … }` 的后缀链因此整体入区域（链上 handler 的取消处理
    才能被看见）：深度归零后若下一非注释行以 `.` 开头，视为链式续行继续走查；
    上限 `cap` 行防解析失配时失控。
    """
    depth = 0
    seen = False
    end = min(len(lines) - 1, start + cap - 1)
    i = start
    while i <= end:
        if not _is_comment(lines[i]):
            for ch in _mask(lines[i]):
                if ch in "{(":
                    depth += 1
                    seen = True
                elif ch in "})":
                    depth -= 1
            if seen and depth <= 0:
                j = i + 1
                while j <= end and (_is_comment(lines[j]) or not lines[j].strip()):
                    j += 1
                if j <= end and lines[j].lstrip().startswith("."):
                    i = j
                    continue
                return i
        i += 1
    return end


def _upper_context(lines: list[str], idx: int) -> bool:
    """自 `idx` 向上取最近的非注释行：先命中协程构造 ⇒ True；先命中普通 `fun` ⇒ False。

    `idx` 行**匹配位置之前**的同行文本先看一眼（`suspend fun … = runCatching {` /
    `fun f() = try { … } catch` 的单行形态不出此判据的盲区）。
    """
    prefix = lines[idx]
    if COROUTINE_RE.search(prefix):
        return True
    if PLAIN_FUN_RE.search(prefix):
        return False
    for j in range(idx - 1, max(-1, idx - 200), -1):
        line = lines[j]
        if _is_comment(line) or not line.strip():
            continue
        if COROUTINE_RE.search(line):
            return True
        if PLAIN_FUN_RE.search(line):
            return False
    return False


def _owning_try(lines: list[str], idx: int) -> int | None:
    """自 `idx` 向上找最近的 `try {` 行号。"""
    for j in range(idx - 1, max(-1, idx - 200), -1):
        if TRY_RE.search(_mask(lines[j])):
            return j
    return None


def _has_cancel_handling(text: str) -> bool:
    return "CancellationException" in text


def _marked(lines: list[str], idx: int) -> bool:
    if MARKER in lines[idx]:
        return True
    j = idx - 1
    while j >= 0 and not lines[j].strip():
        j -= 1
    return j >= 0 and MARKER in lines[j]


def _catch_hits(lines: list[str], rel: str) -> list[str]:
    hits: list[str] = []
    for i, raw in enumerate(lines):
        m = CATCH_RE.search(raw)
        if not m or _is_comment(raw):
            continue
        if not _upper_context(lines, i):
            continue
        body_end = _brace_span(lines, i)
        body = "\n".join(lines[i:body_end + 1])
        if _has_cancel_handling(body):
            continue
        var = m.group(1)
        # 原样重抛（`throw t` / `throw ex`）已保留取消语义，不算吞掉
        if var != "_" and re.search(r"\bthrow\s+" + re.escape(var) + r"\b", body):
            continue
        try_line = _owning_try(lines, i)
        if try_line is not None:
            region = "\n".join(lines[try_line:i])
            if SIBLING_CANCEL_RE.search(region):
                continue
        if _marked(lines, i):
            continue
        hits.append("SWALLOWED_CANCEL %s:%d  %s" % (rel, i + 1, raw.strip()))
    return hits


def _run_catching_hits(lines: list[str], rel: str) -> list[str]:
    hits: list[str] = []
    for i, raw in enumerate(lines):
        if _is_comment(raw) or not RUN_CATCHING_RE.search(_mask(raw)):
            continue
        if RUN_CATCHING_DECL_RE.search(raw):
            continue
        if not _upper_context(lines, i):
            continue
        region_end = _expression_end(lines, i)
        region = "\n".join(lines[i:region_end + 1])
        if _has_cancel_handling(region):
            continue
        if _marked(lines, i):
            continue
        hits.append("RC_SWALLOWED_CANCEL %s:%d  %s" % (rel, i + 1, raw.strip()))
    return hits


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
            lines = text.split("\n")
            hits.extend(_catch_hits(lines, rel))
            hits.extend(_run_catching_hits(lines, rel))
    return hits, checked


# `--selftest` 内嵌样本（写入 app 模块的假文件，走与生产同一条 scan 通路）。
SELFTEST_GOOD = "".join((
    "package demo\n",
    "\n",
    "import kotlinx.coroutines.CancellationException\n",
    "\n",
    # ① 前置取消分支（SyncGuardedLaunch 形态）——不得误报
    "suspend fun guarded(block: suspend () -> Unit) {\n",
    "    try {\n",
    "        block()\n",
    "    } catch (e: CancellationException) {\n",
    "        throw e\n",
    "    } catch (e: Throwable) {\n",
    "        report(e)\n",
    "    }\n",
    "}\n",
    "\n",
    # ② 自身已处理取消
    "suspend fun handled() = withContext(Dispatchers.IO) {\n",
    "    try {\n",
    "        call()\n",
    "    } catch (t: Throwable) {\n",
    "        if (t is CancellationException) throw t\n",
    "        null\n",
    "    }\n",
    "}\n",
    "\n",
    # ③ 非协程上下文（普通 fun）
    "fun blocking() {\n",
    "    try {\n",
    "        call()\n",
    "    } catch (e: Exception) {\n",
    "        null\n",
    "    }\n",
    "}\n",
    "\n",
    # ④ 已登记「不适用」
    "suspend fun annotated() = withContext(Dispatchers.IO) {\n",
    "    try {\n",
    "        blockingIo()\n",
    "    } catch (e: Exception) { // cancel-n/a: 保护段无挂起点\n",
    "        null\n",
    "    }\n",
    "}\n",
    "\n",
    # ⑤ 原样重抛（清理后上抛）
    "suspend fun rethrowAfterCleanup() = withContext(Dispatchers.IO) {\n",
    "    try {\n",
    "        download()\n",
    "    } catch (t: Throwable) {\n",
    "        cleanupTmp()\n",
    "        throw t\n",
    "    }\n",
    "}\n",
    "\n",
    # ⑥ `runCatchingCancellable` 不属裸 `runCatching`（词边界放行）
    "suspend fun cancellable() = withContext(Dispatchers.IO) {\n",
    "    runCatchingCancellable { download() }.getOrNull()\n",
    "}\n",
    "\n",
    # ⑦ `runCatching` 后缀链上处理取消（多行链整体入区域）
    "suspend fun chainedHandler() = withContext(Dispatchers.IO) {\n",
    "    runCatching { download() }\n",
    "        .onFailure { if (it is CancellationException) throw it }\n",
    "        .getOrNull()\n",
    "}\n",
    "\n",
    # ⑧ `LaunchedEffect` 内 `runCatching` 已标注「不适用」
    "@Composable\n",
    "fun Previewish() {\n",
    "    LaunchedEffect(Unit) { // cancel-n/a: 保护段 requestFocus() 为非挂起 UI 调用\n",
    "        runCatching { focusRequester.requestFocus() }\n",
    "    }\n",
    "}\n",
    "\n",
    # ⑨ `runCatching` 的声明行不是调用（伴生自定义包装）
    "object Holder {\n",
    "    inline fun <T> runCatching(block: () -> T): String = \"x\"\n",
    "}\n",
    "\n",
    # ⑩ `LaunchedEffect` 内 catch 自身已处理取消
    "@Composable\n",
    "fun Cameraish() {\n",
    "    LaunchedEffect(preview) {\n",
    "        try {\n",
    "            provider.await()\n",
    "        } catch (t: Throwable) {\n",
    "            if (t is CancellationException) throw t\n",
    "            error = true\n",
    "        }\n",
    "    }\n",
    "}\n",
    "\n",
    # ⑪ 非协程语境的裸 `runCatching`
    "fun parseIt(): Int? = runCatching { \"1\".toInt() }.getOrNull()\n",
))

SELFTEST_BAD = "".join((
    "package demo\n",
    "\n",
    "suspend fun swallows() = withContext(Dispatchers.IO) {\n",
    "    try {\n",
    "        networkCall()\n",
    "    } catch (e: Exception) {\n",
    "        Result.failure(e)\n",
    "    }\n",
    "}\n",
    "\n",
    # 裸 `runCatching` 吞取消（保护段含挂起点）
    "suspend fun rcSwallows(vault: Vault) {\n",
    "    scope.launch {\n",
    "        val title = runCatching { vault.getEntry(id)?.title }.getOrDefault(\"\")\n",
    "        use(title)\n",
    "    }\n",
    "}\n",
    "\n",
    # `LaunchedEffect` 内裸 `runCatching` 吞取消（`ISSUE-P3-570` 补入的构造）
    "@Composable\n",
    "fun PreviewSwallow() {\n",
    "    LaunchedEffect(Unit) {\n",
    "        val ok = runCatching { prepare() }.isSuccess\n",
    "        mark(ok)\n",
    "    }\n",
    "}\n",
    "\n",
    # `LaunchedEffect` 内 catch 吞取消（`ISSUE-P3-570` 前该形态漏判，§484 漏网）
    "@Composable\n",
    "fun CameraSwallow() {\n",
    "    LaunchedEffect(preview) {\n",
    "        try {\n",
    "            provider.await()\n",
    "        } catch (t: Throwable) {\n",
    "            cameraError = true\n",
    "        }\n",
    "    }\n",
    "}\n",
))


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

    bad_catch = [h for h in bad_hits if h.startswith("SWALLOWED_CANCEL")]
    bad_rc = [h for h in bad_hits if h.startswith("RC_SWALLOWED_CANCEL")]
    ok = (
        good_checked == 1
        and bad_checked == 1
        and not good_hits
        and len(bad_catch) == 2
        and len(bad_rc) == 2
    )
    print(
        "[selftest] 绿样本命中=%d（须 0：前置取消分支 / 自身处理 / 非协程 / 已标注 / 原样重抛 / "
        "runCatchingCancellable / 链式处理 / 声明行，检查 %d 文件）；"
        "红样本命中=%d（须 4：catch×2 + runCatching×2，检查 %d 文件）"
        % (len(good_hits), good_checked, len(bad_hits), bad_checked)
    )
    if not ok:
        for hit in good_hits:
            print("  [selftest] 绿样本误报: " + hit)
        for hit in bad_hits:
            print("  [selftest] 红样本: " + hit)
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print("[selftest] PASS：判据可分辨「前置取消分支 / 已处理 / 非协程 / 已标注 / 原样重抛 / "
          "runCatchingCancellable / 链式处理 / 声明行」与「裸吞取消（catch 与 runCatching 两面）」")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="协程取消语义机检（ISSUE-P3-569 / ISSUE-P3-570）")
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, checked = scan(root)
    print(
        "[check_cancellation_semantics] 检查文件 %d 个（*/src/main/**）；命中 %d 处"
        % (checked, len(hits))
    )
    for hit in hits:
        print("  HIT " + hit)
    if hits:
        print(
            "[check_cancellation_semantics] FAIL：协程上下文中的 catch(Throwable/Exception) 与裸 runCatching "
            "必须显式处理取消（catch 就地补 `if (t is CancellationException) throw t`；runCatching 换 "
            "runCatchingCancellable 或链 `.onFailure { if (it is CancellationException) throw it }`；"
            "判为不适用者标注 `cancel-n/a: <理由>`）"
        )
        return 1
    print("[check_cancellation_semantics] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
