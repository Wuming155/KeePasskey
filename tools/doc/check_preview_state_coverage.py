"""`@Preview` 状态覆盖普查（ISSUE-P3-340）。

## 为何存在

真实事故（`ISSUE-P3-337` 2026-09-27 留痕）：编辑页 Passkey 区块新增「扫码 / 相册导入」按钮后与说明文字
**在真机上重叠**，而当时 `@Preview` 只画了 `canImportPasskey = false` 那一态 ⇒ **预览导出图完全正常**，
`:app:compileDebugScreenshotTestKotlin` 也全绿（它只验包装能编译，不验布局）。也就是说：
**「预览有没有覆盖到那一态」是唯一在守的关口，而这个关口此前无人守**。

## 判据（口径要窄，宁可漏报不误报）

对每个「**带字面量默认值的 `Boolean` 参数**」的 `@Composable`（可见性开关最常见的形态），
问一句：它的 `@Preview` 调用点里，**有没有哪一处把该参数显式设成与默认值相反的字面量**？

* 默认 `false` ⇒ 需要某处预览写 `param = true`（省略该参数的调用天然给出 false 态，故 false 态必覆盖）；
* 默认 `true` ⇒ 需要某处预览写 `param = false`；
* 该组件**从未**出现在任何 `@Preview` 里 ⇒ 归入「无预览」桶，单独报数（这是更弱的一类缺口，不与
  「有预览但漏态」混计）。

**边界（不得越）**：只认字面量 `true` / `false`；`param = someFlag`、`when` 计算出的值、经函数间接传入的
开关一律看不见（保守 ⇒ 可能把「其实覆盖了」判成未覆盖，**不会**反向假绿）。故本脚本首版**只出读数**、
不挂 `hygiene-gate`：覆盖率类判据必须先拿到真实读数才能定阈值，否则 CI 长期红只会逼出「为绿而改预览」的失真动作。

## 用法

`python tools/doc/check_preview_state_coverage.py [--root app/src/main] [--strict] [--top N]`
`--strict` 时「有预览但漏态」非 0 即退出码 1（本地复查用；CI 暂不挂）。
`--selftest` 用内嵌的已知坏 / 好样本反校判据本身（**两向都验**）。
"""

from __future__ import annotations

import pathlib
import re
import sys
import tempfile

EXCLUDED_PARTS = {"参考项目", "build", ".git", ".gradle", "preview-exports"}
PREVIEW_ANN = re.compile(r"@(?:androidx\.compose\.ui\.tooling\.preview\.)?Preview\b")
FUN_RE = re.compile(r"\bfun\s+([A-Z]\w*)\s*\(")
BOOL_PARAM = re.compile(r"^(\w+)\s*:\s*Boolean\s*\??\s*=\s*(true|false)$")
NAME_RE = re.compile(r"[A-Za-z_]\w*")


def kt_files(root: pathlib.Path) -> list[pathlib.Path]:
    return [f for f in sorted(root.rglob("*.kt")) if not EXCLUDED_PARTS & set(f.relative_to(root).parts)]


def match_delim(src: str, i: int) -> int:
    """返回与 src[i]（'(' / '{' / '['）配对的闭合下标；跳过注释与字符串。失败返回 -1。"""
    closer = {"(": ")", "{": "}", "[": "]"}[src[i]]
    depth, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i: i + 2]
        if two == "//":
            nl = src.find("\n", i)
            if nl < 0:
                return -1
            i = nl
            continue
        if two == "/*":
            end = src.find("*/", i + 2)
            if end < 0:
                return -1
            i = end + 2
            continue
        if c == '"':
            if src.startswith('"""', i):
                end = src.find('"""', i + 3)
                i = n if end < 0 else end + 3
            else:
                i += 1
                while i < n and src[i] != '"':
                    i += 2 if src[i] == "\\" else 1
                i += 1
            continue
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == "\\" else 1
            i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if c == closer and depth == 0:
                return i
        i += 1
    return -1


def split_top_level(params: str) -> list[str]:
    """按**同层**逗号切参数表（嵌套括号与 `<...>` 内的逗号不算分隔）。

    ⚠️ `->` 里的 `>` **不是**尖括号闭合：早先版本把 `<` / `>` 一律计入深度，于是
    `onTogglePasskey: () -> Unit,` 之后深度变成 −1，**后面所有参数都不再被切开** ——
    第一版就因此漏掉了 `EntryEditPasskeySection.canImportPasskey`（正是本条要抓的那一个）。
    故遇到 `->` 直接跳过两个字符。
    """
    parts, buf, depth, i = [], [], 0, 0
    while i < len(params):
        c = params[i]
        if c == "-" and params[i + 1: i + 2] == ">":
            i += 2
            continue
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(c)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf))
    return [p.strip().removeprefix("@Composable ").strip() for p in parts]


def switch_params(src: str, fun_open: int) -> list[tuple[str, str]]:
    """返回该 `@Composable` 的「带字面量默认值的 Boolean 参数」：[(名字, 默认值)]。"""
    close = match_delim(src, fun_open)
    if close < 0:
        return []
    out = []
    for p in split_top_level(src[fun_open + 1: close]):
        m = BOOL_PARAM.match(p.replace("\n", " ").strip())
        if m:
            out.append((m.group(1), m.group(2)))
    return out


def preview_bodies(src: str) -> list[str]:
    """收集所有 `@Preview` 标注函数的**函数体文本**（含其内部再嵌套的调用）。"""
    bodies = []
    for m in PREVIEW_ANN.finditer(src):
        fm = FUN_RE.search(src, m.end())
        if not fm:
            continue
        open_brace = src.find("{", fm.end() - 1)
        if open_brace < 0 or "\n" in src[fm.end(): open_brace]:
            continue                      # 参数跨行时才允许往后找 `{`，否则视为异常形态
        end = match_delim(src, open_brace)
        if end > open_brace:
            bodies.append(src[open_brace + 1: end])
    return bodies


def analyze(root: pathlib.Path) -> tuple[list[str], list[str], int]:
    """返回（漏态清单, 无预览清单, 开关参数总数）。"""
    # 第一遍：全仓 @Preview 调用点里出现过的 (组件名, 参数名 = 字面量) 集合
    flipped: dict[str, set[str]] = {}
    called: set[str] = set()
    for f in kt_files(root):
        src = f.read_text(encoding="utf-8")
        for body in preview_bodies(src):
            # 调用点不是声明：不能用 FUN_RE（它要求 `fun ` 关键字），否则预览里的调用一个都认不出，
            # 结果就是「所有开关都缺反向态」——一片红同样是没有鉴别力的读数。
            for cm in re.finditer(r"\b([A-Z]\w*)\s*[\({]", body):
                name = cm.group(1)
                called.add(name)
                nxt = body[cm.end() - 1]
                if nxt == "(":
                    close = match_delim(body, cm.end() - 1)
                    args = body[cm.end(): close + 1] if close > 0 else ""
                else:
                    args = ""
                for pname, pval in re.findall(r"(\w+)\s*=\s*(true|false)\b", args):
                    flipped.setdefault(f"{name}.{pname}", set()).add(pval)
    missing, no_preview, total = [], [], 0
    for f in kt_files(root):
        src = f.read_text(encoding="utf-8")
        declared = {fm.group(1) for fm in FUN_RE.finditer(src)}
        for fm in FUN_RE.finditer(src):
            name = fm.group(1)
            params = switch_params(src, fm.end() - 1)
            if not params:
                continue
            for pname, default in params:
                total += 1
                line = src[: fm.start()].count("\n") + 1
                if name not in called:
                    no_preview.append(f"{f}:{line} {name}（开关参数 {pname} = {default}）从未出现在任何 @Preview")
                    continue
                need = "true" if default == "false" else "false"
                if need not in flipped.get(f"{name}.{pname}", set()):
                    missing.append(f"{f}:{line} {name}.{pname} 默认 {default} ⇒ 缺 {need} 态预览")
    return missing, no_preview, total


BAD_SAMPLE = """
@Composable
fun SectionCard(
    onToggle: () -> Unit,
    canImport: Boolean = false,
    labels: Map<String, Int> = emptyMap(),
    onImport: (IntArray) -> Unit = {}
) {
    if (canImport) { Text("导入") }
}

@Preview
@Composable
fun SectionCardPreview() {
    Column {
        SectionCard(onToggle = {})
        SectionCard(onToggle = {}, labels = mapOf("a" to 1))
    }
}
"""

GOOD_SAMPLE = BAD_SAMPLE.replace("        SectionCard(onToggle = {}, labels = mapOf(\"a\" to 1))",
                                 "        SectionCard(onToggle = {}, canImport = true)")

NO_PREVIEW_SAMPLE = """
@Composable
fun LoneCard(showExtra: Boolean = false) {
    if (showExtra) { Text("额外") }
}

@Preview
@Composable
fun OtherPreview() {
    Text("与 LoneCard 无关")
}
"""


def selftest() -> int:
    """坏样本必须报漏态、好样本必须不报、无预览样本必须落进「无预览」桶（三向反校）。"""
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="preview-coverage-selftest-"))
    rc = 0
    cases = (
        ("坏样本(只画默认态)", BAD_SAMPLE, "missing"),
        ("好样本(补了反向态)", GOOD_SAMPLE, "none"),
        ("无预览样本", NO_PREVIEW_SAMPLE, "no_preview"),
    )
    for label, text, expect in cases:
        p = tmp / f"{abs(hash(text))}.kt"
        p.write_text(text, encoding="utf-8")
        missing, no_prev, total = analyze(tmp)
        bucket = "missing" if missing else ("no_preview" if no_prev else "none")
        ok = bucket == expect
        print(f"[selftest] {label}: 开关参数={total} 落桶={bucket} 期望={expect} ⇒ {'OK' if ok else 'FAIL'}")
        if not ok:
            rc = 1
        p.unlink()
    tmp.rmdir()
    return rc


def main() -> int:
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    root = pathlib.Path("app/src/main")
    if "--root" in args:
        root = pathlib.Path(args[args.index("--root") + 1])
    top = int(args[args.index("--top") + 1]) if "--top" in args else 20
    missing, no_prev, total = analyze(root)
    print(f"扫描根：{root}")
    print(f"带字面量默认值的 Boolean 开关参数：{total}")
    print(f"preview_missing_state={len(missing)}")
    print(f"preview_no_preview_at_all={len(no_prev)}")
    if total:
        both = total - len(missing) - len(no_prev)
        print(f"两态齐比率 = {both}/{total} = {both / total:.1%}（口径：只认字面量反向态，保守 ⇒ 只会低估覆盖率）")
    print(f"--- 漏态清单（前 {top} 条）---")
    for line in missing[:top]:
        print(f"MISSING {line}")
    print(f"--- 完全无预览清单（前 {top} 条）---")
    for line in no_prev[:top]:
        print(f"NOPREVIEW {line}")
    if "--strict" in args and missing:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
