"""用户可见通道的硬编码中文复核（ISSUE-P3-453 的机检化门禁）。

## 为何存在

用户真机走查反馈「英文界面时有的提示依然有中文」。语料审计（代码引用键 vs `values-en` 全量比对）
证明**资源键无缺失**，残留根因是**代码硬编码中文绕过资源通道**——最典型的是 database 层
`KdbxResult.Failure(t, "保存数据库失败: ${t.message}")`：第二参是 `userMessage`，会被 UI 侧
`UiMessage(R.string.op_failed, listOf(result.message))` 直接拼进用户可见提示，
`op_failed` 的格式串是「操作失败：%1$s」⇒ 切到英文界面，前半句变英文、后半句仍是中文。

同一条通道还有第二个问题：`${t.message}` 把**裸异常细节**（可能含路径 / 端点 / 主机地址，
`ISSUE-P3-311` 同源红线）一并透出 UI。故判据对「含中文」与「含插值」一视同仁地要求改造：
中文 ⇒ 改类型化错误码；插值 ⇒ 细节只留日志面（`error` 已承载）。

## 判据（窄而准，宁可漏报也不可误报）

* **规则 A**：`KdbxResult.Failure(` 的**第二参**（`userMessage`）是含 CJK 的**字符串字面量** ⇒ 红。
  第一参是 `error`（Throwable，只进日志），第三参起视为细节，**不在本规则范围**。
* **规则 B**：`UiMessage(` 的任一参数是含 CJK 的字符串字面量 ⇒ 红。
  `UiMessage` 的 `resId` 必须是 `@StringRes`，中文只能走资源，不能走 `args`。
* **规则 C**：下层模块（`core` / `database`）`const val` 赋值为含 CJK 的字面量 ⇒ 红。
  这两个模块**没有 UI 上下文、也拿不到 `strings`**，其常量一旦是中文，
  要么是「会原样上浮 UI 的默认文案」（真实事故：`KdbxResult` 的 `DEFAULT_USER_MESSAGE = "未知错误"`），
  要么是本该本地化的日志文案。范围刻意只收这两个模块——app 层的中文常量多为日志 tag / 诊断口径，
  纳入即误报。
* **规则 D（ISSUE-P3-459 增设）**：`StringsProviderModule.kt` 中 `provideStringsProvider` 的**生产绑定**
  若①正文未出现 `localizedResourcesFor(` 派生、或②直接对传入的 `context`（`@ApplicationContext`）
  调 `getString(` ⇒ 红。
  规则 A–C 只能看见「**字面量里有没有中文**」，**看不见「取串通道本身跟不跟语言」**——
  规则 A–C 全绿、真机却整屏中文，就是规则 D 存在的理由（详见脚本尾部事故注记）。
  Application context 的 resources 按**系统 locale** 定形，既不随应用内语言重配、
  也拿不到 UI 侧 `localizedContextOf` 的 Wrapper ⇒ 经它取到的串**恒为系统语言文案**。

## 口径与边界（静态启发式，不得据绿推定「全仓已无残留」）

* 不解析 Kotlin AST，靠括号配平与字符串/注释跳过。故
  ① 经变量间接传入（`val m = "中文"; Failure(t, m)`）看不见；
  ② 跨行拼接（`"失败" + x`）只看首段字面量；
  ③ 三引号模板字符串按整体判定（含 CJK 即红）。
* **未覆盖的通道**：`Toast` / `Snackbar` 直传字面量、`Notification` 文案、`strings.xml` 自身缺 en 键
  （后者由语料审计覆盖，非本脚本职责）。本脚本只钉 `Failure` / `UiMessage` / 下层常量 /
  取串通道四条通道。
* 防空扫：扫到的调用点数为 0 时判红——那说明 `--root` 或扫描口径给错了，这种「绿」没有鉴别力。
* **规则 D 只锁这一个绑定点**，不做「全仓禁止 Application context 取串」的宽扫：后者误报率高
  （大量合法转发场景），而误报会诱使后来者放宽判据——**误报比漏报更危险**。

## 事故注记（规则 D 的由来，改判据前必读）

§413 曾把 30+ 个用户可见消费点从「硬编码中文」改为走 `StringsProvider`，
机检 A–C 全绿（中文字面量确已清零）、`test` 与门禁全过，但**英文界面真机实测五处仍是中文**
（解锁页 / 生物识别 / 本地存储 / 改主密码 / 同步失败），唯一例外是走 Compose
`stringResource(LocalContext)` 的「主密码错误」一条显示英文 `Incorrect master password…`。
识别特征＝**两条通道口径不一致**：Composable 侧拿得到 `localizedContextOf` 的 Wrapper，
`@ApplicationContext` 侧拿不到。⇒ 「没有中文字面量」**不等于**「文案已本地化」，
机检必须盯住**通道**而不只是**内容**。

用法：`python tools/audit/check_user_visible_cjk.py [--root 目录] [--selftest]`
命中即退出码 1；`--selftest` 用内嵌的已知好/坏样本反校判据本身（**红样本与绿样本各验一次**）。
"""

from __future__ import annotations

import pathlib
import re
import sys
import tempfile

# CJK 统一表意文字（含扩展 A）；不含标点与假名——中文提示必有汉字，标点单独出现不算
CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

FAILURE_CALL = "KdbxResult.Failure("
UIMESSAGE_CALL = "UiMessage("

# 规则 D：StringsProvider 的生产绑定必须经本地化派生 Resources（ISSUE-P3-459）
SP_MODULE_NAME = "StringsProviderModule.kt"
SP_FUN_RE = re.compile(r"\bfun\s+provideStringsProvider\s*\(")
SP_NEXT_FUN_RE = re.compile(r"\n\s+fun\s+\w+")
LOCALIZED_DERIVE = "localizedResourcesFor("

# 规则 C 的两个下层模块（相对 --root 的父级判断：按包路径匹配更稳）
LOWER_MODULES = ("core/src/main", "database/src/main")
CONST_VAL_RE = re.compile(r"\bconst\s+val\s+\w+\s*=\s*(\"[^\"]*\")")


def strip_comments_and_strings(src: str) -> tuple[str, list[tuple[int, int, str]]]:
    """返回（去掉注释与字符串内容后的骨架，字符串字面量列表[(start, end, raw)]）。

    骨架里字符串区间被替换为等长占位，保证行号与列偏移不漂移。
    """
    out: list[str] = []
    strings: list[tuple[int, int, str]] = []
    i = 0
    n = len(src)

    def blank(raw: str) -> str:
        """字符串字面量在骨架里的占位：**保留换行**（行号不漂移），其余字符换成 \\x00。

        刻意不用空格——调用方靠「裁剪首尾空白」定位参数区间，若字符串在骨架里是空格，
        整段会被当成空白裁掉，判据直接失去鉴别力（首版缺陷，自校当场抓获）。
        """
        return "".join("\n" if c == "\n" else "\x00" for c in raw)

    while i < n:
        ch = src[i]
        if ch == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "*":
            depth = 1
            out.append(" ")
            out.append(" ")
            i += 2
            while i < n and depth:
                if src[i] == "/" and i + 1 < n and src[i + 1] == "*":
                    depth += 1
                    out.append("  ")
                    i += 2
                    continue
                if src[i] == "*" and i + 1 < n and src[i + 1] == "/":
                    depth -= 1
                    out.append("  ")
                    i += 2
                    continue
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            continue
        if ch == '"':
            if src.startswith('"""', i):
                close = src.find('"""', i + 3)
                close = n if close < 0 else close + 3
                raw = src[i:close]
                strings.append((i, close, raw))
                out.append(blank(raw))
                i = close
                continue
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            raw = src[i:j]
            strings.append((i, j, raw))
            out.append(blank(raw))
            i = j
            continue
        out.append(ch)
        i += 1
    return "".join(out), strings


def string_at(strings: list[tuple[int, int, str]], start: int, end: int) -> str | None:
    """若 [start,end) 区间**整体**是一个字符串字面量，返回原文（含引号）；否则 None。"""
    txt = None
    for s, e, raw in strings:
        if s == start and e == end:
            txt = raw
            break
    return txt


def split_top_level(args_src: str) -> list[tuple[int, int]]:
    """按顶层逗号切参数（跳过括号内的逗号），返回**相对 args 起点的 (start, end) 区间**。

    刻意**不**返回 strip 后的文本：调用方要靠区间端点去比对字符串字面量的精确位置，
    strip 会让偏移整体错位（首版缺陷，自校当场抓获）。
    """
    parts: list[tuple[int, int]] = []
    depth = 0
    start = 0
    for i, ch in enumerate(args_src):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append((start, i))
            start = i + 1
    parts.append((start, len(args_src)))
    return parts


def call_args(skel: str, idx: int) -> tuple[int, int] | None:
    """给定骨架中 `(` 的下标，返回参数区间的 (start, end)；括号不闭合返回 None。"""
    depth = 0
    start = idx + 1
    for j in range(idx, len(skel)):
        if skel[j] == "(":
            depth += 1
        elif skel[j] == ")":
            depth -= 1
            if depth == 0:
                return start, j
    return None


def scan_strings_provider_binding(
    path: pathlib.Path, src: str, line_of
) -> list[str]:
    """规则 D：`StringsProviderModule.kt` 的 provideStringsProvider 必须走本地化派生。

    判据取两条，命中任意一条即红：
    ① 函数体（到下一个顶层 `fun` 为止）里没有 `localizedResourcesFor(`；
    ② 存在 `context.getString(` 且其前 60 字符内没有 `localizedResourcesFor(`。

    只看「有没有 `localizedResourcesFor`」不足以锁死（写法形形色色），故同时保留②——
    两者取并集，宁可把「派生后取串」这种写法也判红（届时改判据而不放水），
    也不放过「裸 Application context 取串」这条真正的回潮路径。
    """
    hits: list[str] = []
    m = SP_FUN_RE.search(src)
    if m is None:
        return hits
    start = m.end()
    nxt = SP_NEXT_FUN_RE.search(src, start)
    end = nxt.start() if nxt is not None else len(src)
    seg = src[start:end]
    if LOCALIZED_DERIVE not in seg:
        hits.append(
            f"{path}:{line_of(start)} 规则D StringsProvider 生产绑定未派生本地化 Resources"
            f"（缺少 {LOCALIZED_DERIVE}）：经它取到的串恒按系统 locale 解析"
        )
    for gm in re.finditer(r"\bcontext\.getString\(", seg):
        pre = seg[max(0, gm.start() - 60) : gm.start()]
        if LOCALIZED_DERIVE not in pre:
            hits.append(
                f"{path}:{line_of(start + gm.start())} 规则D StringsProvider 直接对 Application context 取串"
                "（应用内语言切英文后仍是中文）"
            )
    return hits


def scan_file(path: pathlib.Path) -> list[str]:
    src = path.read_text(encoding="utf-8", errors="replace")
    skel, strings = strip_comments_and_strings(src)
    hits: list[str] = []

    def line_of(pos: int) -> int:
        return src.count("\n", 0, pos) + 1

    def cjk_literal(seg_start: int, seg_end: int) -> bool:
        return bool(cjk_literals_within(seg_start, seg_end))

    def cjk_literals_within(seg_start: int, seg_end: int) -> list[str]:
        """区间内**所有**含 CJK 的字符串字面量。

        刻意查「区间内」而非「区间整体就是一个字面量」：`listOf("未知原因")` 这类嵌套写法
        下，参数整体不是字面量，只看整体会漏判（首版缺陷，自校当场抓获）。
        """
        return [
            raw
            for s, e, raw in strings
            if s >= seg_start and e <= seg_end and CJK_RE.search(raw)
        ]

    def trim(s: int, e: int) -> tuple[int, int]:
        while s < e and skel[s] in " \t\r\n":
            s += 1
        while e > s and skel[e - 1] in " \t\r\n":
            e -= 1
        return s, e

    # ---- 规则 A / B：调用点参数 ----
    for call, rule in ((FAILURE_CALL, "A"), (UIMESSAGE_CALL, "B")):
        pos = 0
        while True:
            k = skel.find(call, pos)
            if k < 0:
                break
            pos = k + len(call)
            paren = k + len(call) - 1
            span = call_args(skel, paren)
            if span is None:
                continue
            a_start, a_end = span
            for idx, (rs, re_) in enumerate(split_top_level(skel[a_start:a_end])):
                p_start, p_end = trim(a_start + rs, a_start + re_)
                if not cjk_literal(p_start, p_end):
                    continue
                if rule == "A":
                    # 规则 A 只看第二参（userMessage）：第一参是 error，只进日志
                    if idx != 1:
                        continue
                    what = "KdbxResult.Failure 第二参（userMessage）"
                else:
                    what = "UiMessage 参数"
                hits.append(
                    f"{path}:{line_of(p_start)} 规则{rule} {what} 为硬编码中文："
                    f"{', '.join(cjk_literals_within(p_start, p_end))}"
                )

    # ---- 规则 D：StringsProvider 生产绑定必须经本地化派生 ----
    if path.name == SP_MODULE_NAME:
        hits.extend(scan_strings_provider_binding(path, src, line_of))

    # ---- 规则 C：下层模块 const val 中文字面量 ----
    rel = path.as_posix()
    if any(m in rel for m in LOWER_MODULES):
        # 在**原文**（src）上匹配：骨架里字符串已换成 \x00 占位，正则的 `"..."` 在那里永远匹配不到
        # （首版缺陷，自校当场抓获）。再用骨架回验该位置确是真实字面量（而非注释里被跳过的写法）。
        for m in CONST_VAL_RE.finditer(src):
            s, e = m.span(1)
            if skel[s] != "\x00":
                continue
            if CJK_RE.search(m.group(1)):
                hits.append(
                    f"{path}:{line_of(s)} 规则C 下层模块 const val 为硬编码中文：{m.group(1)}"
                )
    return hits


DEFAULT_ROOTS = (
    "core/src/main",
    "database/src/main",
    "sync/src/main",
    "app/src/main",
)


def scan(roots: list[pathlib.Path]) -> tuple[list[str], int]:
    hits: list[str] = []
    sites = 0
    for root in roots:
        if not root.exists():
            print(f"⇒ 扫描根目录不存在：{root}")
            return hits, 0
        for path in sorted(root.rglob("*.kt")):
            if "build" in path.parts:
                continue
            src = path.read_text(encoding="utf-8", errors="replace")
            sites += src.count(FAILURE_CALL) + src.count(UIMESSAGE_CALL)
            hits.extend(scan_file(path))
    return hits, sites


GOOD_SAMPLE = '''
package demo
val ok1 = KdbxResult.Failure(t, KdbxError.SAVE_READ_ONLY)
val ok2 = KdbxResult.Failure(IllegalStateException("数据库以只读模式打开"))
val ok3 = UiMessage(R.string.op_failed, listOf(ResArg(R.string.err_unknown)), isError = true)
const val DEFAULT_USER_MESSAGE = "kdbx.unknown"
'''

BAD_SAMPLE = '''
package demo
val bad1 = KdbxResult.Failure(t, "保存数据库失败: ${t.message}")
val bad2 = UiMessage(R.string.op_failed, listOf("未知原因"))
const val DEFAULT_USER_MESSAGE = "未知错误"
'''


SELFTEST_SP_GOOD = '''
package demo
fun provideStringsProvider(@ApplicationContext context: Context, t: AppLocaleTracker): StringsProvider =
    StringsProvider { id, args -> localizedResourcesFor(context, t.snapshot()).getString(id, *args) }
'''

SELFTEST_SP_BAD = '''
package demo
fun provideStringsProvider(@ApplicationContext context: Context): StringsProvider =
    StringsProvider { id, args -> context.getString(id, *args) }
'''


def selftest() -> int:
    rc = 0
    tmp = pathlib.Path(tempfile.mkdtemp())
    # 规则 C 按**路径**判定下层模块（core/src/main、database/src/main），样本必须落在真实目录结构里，
    # 否则该规则在自校中恒不触发——那是一种「假绿」。
    for rel, body, expect in (
        ("app/src/main/Good.kt", GOOD_SAMPLE, 0),
        ("app/src/main/Bad.kt", BAD_SAMPLE, 2),
        ("core/src/main/BadConst.kt", BAD_SAMPLE, 3),
        # 规则 D 只看**文件名**，样本必须叫 StringsProviderModule.kt 才能反校到该判据
        ("app/src/main/StringsProviderModule.kt", SELFTEST_SP_GOOD, 0),
        ("app/src/main/StringsProviderModule.kt", SELFTEST_SP_BAD, 2),
    ):
        p = tmp / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")
        hits = scan_file(p)
        ok = len(hits) == expect
        print(f"[selftest] {rel}: 命中={len(hits)} 期望={expect} ⇒ {'OK' if ok else 'FAIL'}")
        if not ok:
            for h in hits:
                print(f"           {h}")
            rc = 1
    for d in sorted(tmp.rglob("*"), key=lambda x: len(x.parts), reverse=True):
        if d.is_dir():
            d.rmdir()
        else:
            d.unlink()
    tmp.rmdir()
    return rc


def main() -> int:
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    if "--root" in args:
        roots = [pathlib.Path(args[args.index("--root") + 1])]
    else:
        roots = [pathlib.Path(r) for r in DEFAULT_ROOTS]
    hits, sites = scan(roots)
    print(f"扫描根目录：{', '.join(r.as_posix() for r in roots)}  调用点（Failure/UiMessage）={sites}")
    for h in hits:
        print(f"CJK_IN_UI_CHANNEL {h}")
    print(f"user_visible_cjk_sites={len(hits)}")
    if sites == 0:
        print("⇒ 未发现任何 Failure/UiMessage 调用点：该读数**不构成**证据（--root 或扫描口径可能给错）")
        return 1
    if hits:
        print("⇒ 上述中文会绕过资源通道直达用户可见提示：改类型化错误码（下层）或改 @StringRes（app 层）。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
