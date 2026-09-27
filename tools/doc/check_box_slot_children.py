"""Box 内容槽同层兄弟叠放复核（ISSUE-P3-337 编辑页 Q1 按钮叠字缺陷的机检化）。

## 为何存在

`BentoCard` 的内容槽签名是 `content: @Composable BoxScope.() -> Unit`（`ui/components/BentoCard.kt:33`），
内部是 `Box { content() }`。**Box 的兄弟节点互相叠放**（默认 `Alignment.TopStart`），不像 `Column`
那样上下流动 —— 所以往卡片里加第二个顶层子节点，调用方若按 Column 直觉写就会**重叠**，
而且**编译期与预览都可能看不出来**（真实事故：Q1 的「扫码 / 相册导入」按钮压在说明文字上，
只在真机上暴露；当时的 `@Preview` 又恰好只画了「不显示按钮」那一态）。

本脚本把这条判据做成机检：扫出「内容槽为 Box 的组件」的**所有**调用点，统计其 trailing lambda 里
**同层（深度 0）发射的子节点数**，≥ 2 即报红（这些兄弟会叠在一起，须包进 `Column`/`Row`/`Box` 显式容器）。

## 口径与边界

* **只数发射节点**：`Foo(` / `Foo {` / `if` / `when` / `for` / `while` / `x.forEach {` 计为子节点；
  `val` / `var` / 注释 / 空行 / 纯表达式（如 `val x = remember { … }`）**不**计。
* **不误报单容器**：只有一个顶层子节点（哪怕它内部再复杂）一律放行 —— 那正是绝大多数卡片的写法。
* **静态启发式**：不解析 Kotlin AST，靠括号/花括号配平与字符串/注释跳过。故
  ① 经变量间接传入的 lambda（`val slot = { … }` 后传参）看不见；② 条件里返回可组合物的自定义
  函数（`MaybeButton()` 内部发射两个节点）也看不见。**不得**据其绿推定「全仓已无叠放」。
* 用法：`python tools/doc/check_box_slot_children.py [--root 目录] [--selftest]`；
  命中即退出码 1；`--selftest` 用内嵌的已知坏样本反校判据本身（**绿样本 + 红样本都必须各验一次**）。
"""

from __future__ import annotations

import pathlib
import re
import sys
import tempfile

SLOT_RE = re.compile(r":\s*@Composable\s+(\w*Scope)\s*\.\s*\(\s*\)\s*->\s*Unit")
FUN_RE = re.compile(r"\bfun\s+([A-Z]\w*)\s*\(")
EMIT_RE = re.compile(r"^(?:[A-Z]\w*\s*[\({]|if[\s(]|for\s*\(|while\s*\(|when[\s({]|[a-z]\w*\.(?:forEach|map|let|run|apply|also)\b)")
STACK_ALIGNMENTS = ("contentAlignment", "Alignment.")  # 显式声明叠放意图的 Box 不报（此处仅作记录）
# 调用名后面可能直接跟 trailing lambda 的 `{`，也可能跟参数表的 `(`。
# 单独成常量是因为 `[\({]` 写进 rf-string 会被解析成 f-string 表达式起始（Python 3.11 口径）。
CALL_PUNCT = "[({]"
# 扫描时排除的目录段：`参考项目/` 是**只读参考实现**（AGENTS §3.3，其缺陷不归本仓整改），
# `build/` 与 `.git/` 是生成物。不排除会把参考项目的站点算进读数（实测曾混入 Monica 的 PanePrimitives）。
EXCLUDED_PARTS = {"参考项目", "build", ".git", ".gradle", "preview-exports"}


def kt_files(root: pathlib.Path) -> list[pathlib.Path]:
    # 排除口径按**相对 root 的路径段**判，不按绝对路径判（否则用户目录里恰好含 "build" 就整体漏扫）
    return [f for f in sorted(root.rglob("*.kt")) if not EXCLUDED_PARTS & set(f.relative_to(root).parts)]


def _match(src: str, i: int) -> int:
    """返回与 src[i]（'(' 或 '{'）配对的闭合下标；跳过行/块注释与字符串字符字面量。失败返回 -1。"""
    opener = src[i]
    closer = {"(": ")", "{": "}"}[opener]
    depth = 0
    n = len(src)
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
        if c in "({":
            depth += 1
        elif c in ")}":
            depth -= 1
            if c == closer and depth == 0:
                return i
        i += 1
    return -1


def box_slot_components(root: pathlib.Path) -> set[str]:
    """全仓找出「内容槽 receiver 是 BoxScope」的组件名。"""
    names: set[str] = set()
    for f in kt_files(root):
        src = f.read_text(encoding="utf-8")
        for m in SLOT_RE.finditer(src):
            if m.group(1) != "BoxScope":
                continue
            head = src[: m.start()]
            funs = list(FUN_RE.finditer(head))
            if funs:
                names.add(funs[-1].group(1))
    return names


def stacked_children(src: str, call_idx: int) -> list[tuple[int, str]]:
    """给定组件调用名起点，返回其 trailing lambda 内同层发射节点（行号, 首行文本）。

    两种调用形态都要认：`Card(a = 1) { … }` 与**裸 trailing lambda** `Card { … }`
    （后者没有左括号 —— 早先版本一律 `src.index("(", …)` 去找「最近的左括号」，于是把
    lambda **内部第一个子节点**的括号当成了调用括号，配对配到别处，整站点静默漏检）。
    """
    j = call_idx
    while j < len(src) and (src[j].isalnum() or src[j] == "_"):   # 先跳过组件名本身
        j += 1
    while j < len(src) and src[j].isspace():
        j += 1
    if j >= len(src) or src[j] not in "({":
        return []
    if src[j] == "(":
        close = _match(src, j)
        if close < 0:
            return []
        j = close + 1
        while j < len(src) and src[j] in " \t\r\n":
            j += 1
    if j >= len(src) or src[j] != "{":
        return []
    body_end = _match(src, j)
    if body_end < 0:
        return []
    body = src[j + 1: body_end]
    first_line = src[:j].count("\n") + 1

    kids: list[tuple[int, str]] = []
    depth = 0
    at_line_start = True
    line_no = first_line
    k = 0
    while k < len(body):
        c = body[k]
        if c == "\n":
            line_no += 1
            at_line_start = True
            k += 1
            continue
        if at_line_start:
            rest = body[k:].split("\n", 1)[0]
            stripped = rest.strip()
            if stripped:
                at_line_start = False
                if depth == 0 and EMIT_RE.match(stripped):
                    kids.append((line_no, stripped[:60]))
        if c in "({":
            depth += 1
        elif c in ")}":
            depth -= 1
        k += 1
    return kids


def scan(root: pathlib.Path, components: set[str]) -> tuple[list[str], int]:
    """返回（报红行, **实际检查过的调用点数**）。

    调用点数必须一起报出来：否则「0 处命中」可能只是「一个站点都没看到」的假绿
    （本仓反复踩过的形态 —— 绿要有鉴别力，得能证明它看过东西）。
    """
    bad: list[str] = []
    sites = 0
    for f in kt_files(root):
        src = f.read_text(encoding="utf-8")
        for name in components:
            for m in re.finditer(rf"\b{re.escape(name)}\s*{CALL_PUNCT}", src):
                # 排除组件自身声明：`fun BentoCard(` 的函数体不是「调用方的同层子节点」
                if src[: m.start()].rstrip().endswith("fun"):
                    continue
                sites += 1
                line = src[: m.start()].count("\n") + 1
                kids = stacked_children(src, m.start())
                if len(kids) >= 2:
                    where = ", ".join(f"{ln}:{txt}" for ln, txt in kids)
                    bad.append(f"{f}:{line} {len(kids)} 个同层子节点会互相叠放 ⇒ {where}")
    return bad, sites


BAD_SAMPLE = """
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.padding(16.dp), content = content)
}

@Composable
fun Screen() {
    Card(modifier = Modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("标题")
            Button(onClick = {}) { Text("解绑") }
        }
        if (show) {
            OutlinedButton(onClick = {}) { Text("导入") }
        }
    }
}
"""

GOOD_SAMPLE = """
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.padding(16.dp), content = content)
}

@Composable
fun Screen() {
    Card {
        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("标题")
                Button(onClick = {}) { Text("解绑") }
            }
            if (show) {
                OutlinedButton(onClick = {}) { Text("导入") }
            }
        }
    }
}
"""


BAD_SAMPLE_BARE = BAD_SAMPLE.replace("    Card(modifier = Modifier) {", "    Card {")


def selftest() -> int:
    """已知坏样本必须命中、已知好样本必须不命中（判据本身反校）。

    两种调用形态各验一次：`Card { … }`（裸 trailing lambda）与 `Card(modifier = …) { … }`
    ——早先版本只认后者，前者静默漏检，正是「目测登记前提成立、实际不成立」的那类工具缺陷。
    """
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="boxslot-selftest-"))
    tmp.mkdir(parents=True, exist_ok=True)
    rc = 0
    cases = (
        ("坏样本(裸 trailing lambda 同层两兄弟)", BAD_SAMPLE_BARE, True),
        ("坏样本(带参数 + 同层两兄弟)", BAD_SAMPLE, True),
        ("好样本(同层只有一个 Column)", GOOD_SAMPLE, False),
    )
    for label, text, expect_bad in cases:
        p = tmp / f"{abs(hash(text))}.kt"
        p.write_text(text, encoding="utf-8")
        comps = box_slot_components(tmp)
        hits, _ = scan(tmp, comps)
        got = any(p.name in h for h in hits)
        ok = got == expect_bad
        print(f"[selftest] {label}: 组件={sorted(comps)} 命中={got} 期望={expect_bad} ⇒ {'OK' if ok else 'FAIL'}")
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
    comps = box_slot_components(root)
    if not comps:
        print("BOX_SLOT_COMPONENTS=0（未发现 BoxScope 内容槽组件）")
        return 0
    hits, sites = scan(root, comps)
    print(f"BoxScope 内容槽组件：{sorted(comps)}")
    print(f"检查过的调用点：{sites}")
    for h in hits:
        print(f"STACK {h}")
    print(f"box_slot_stacked_sites={len(hits)}")
    if sites == 0:
        # 有组件却零站点 ⇒ 要么调用方全在排除目录里，要么根目录给错了；这种「绿」没有鉴别力，判红
        print("⇒ 未发现任何调用点：该读数**不构成**「全仓已无叠放」的证据（很可能是 --root 或排除口径给错了）")
        return 1
    if hits:
        print("⇒ 这些卡片的 trailing lambda 里有 ≥2 个同层子节点：Box 语义下会互相叠放，须包进 Column/Row/Box。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
