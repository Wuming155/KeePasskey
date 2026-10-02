"""Box 内容槽同层兄弟叠放复核（ISSUE-P3-337 编辑页 Q1 按钮叠字缺陷的机检化）。

## 为何存在

`BentoCard` 的内容槽签名是 `content: @Composable BoxScope.() -> Unit`（`ui/components/BentoCard.kt:33`），
内部是 `Box { content() }`。**Box 的兄弟节点互相叠放**（默认 `Alignment.TopStart`），不像 `Column`
那样上下流动 —— 所以往卡片里加第二个顶层子节点，调用方若按 Column 直觉写就会**重叠**，
而且**编译期与预览都可能看不出来**（真实事故：Q1 的「扫码 / 相册导入」按钮压在说明文字上，
只在真机上暴露；当时的 `@Preview` 又恰好只画了「不显示按钮」那一态）。

本脚本把这条判据做成机检：扫出「内容槽为 Box 的组件」的**所有**调用点，统计其 trailing lambda 里
**同层（深度 0）发射的子节点数**，≥ 2 即报红（这些兄弟会叠在一起，须包进 `Column`/`Row`/`Box` 显式容器）。

## ISSUE-P3-457 扩面：框架 Box 槽 + 「多发射助手函数」

真机叠字事故（解锁页 `supportingText` 四行文案压成一行乱码）暴露了原判据的两处盲区，
故本脚本新增两条互补规则：

* **规则 A（框架 Box 槽同层多子节点）**：Material3 的 `OutlinedTextField` / `TextField` /
  `SecureTextField` 把 `label` / `prefix` / `suffix` / `leadingIcon` / `trailingIcon` / `supportingText`
  **各自包在 `Box(...)` 里**（`material3/internal/TextFieldImpl.kt` 的 `TextFieldLayout` 与
  `CutoutTextFieldLayout`，2026-10-02 逐行核对 1.5.0-alpha27 源码）。这些槽与 `BentoCard` 同病：
  ≥2 个同层子节点即叠放，而 `supportingText` 槽外层只 `heightIn(min = MinSupportingTextLineHeight)`
  ⇒ 量到的是**最高子节点**，多行不会互相撑开，直接压在同一 y 上。
* **规则 B（多发射助手函数作为 Box 槽唯一子节点）**：`supportingText = { Helper(x) }` 只发射 1 个
  节点，规则 A 看不见；但 `Helper` 若自身顶层发射 ≥2 个节点（「多发射助手」），这些兄弟同样直接落进
  那个 Box。⇒ 先全仓解析「顶层发射 ≥2 节点的 `@Composable`」（判据与 lambda 同源），再对每个 Box 槽
  「唯一子节点是一次对该类助手的调用」报红。**这正是 P3-457 的原形**：`supportingText = { UnlockPasswordSupportingText(uiState) }`
  加 4 个平铺 `Text` —— 只看调用点是绿，看穿调用点才是红。

## 口径与边界

* **只数发射节点**：`Foo(` / `Foo {` / `if` / `when` / `for` / `while` / `x.forEach {` 计为子节点；
  `val` / `var` / 注释 / 空行 / 纯表达式（如 `val x = remember { … }`）**不**计。
  `if` / `when` 计 1（运行时只走一条分支），故「单分支择一发射」不误报。
* **不误报单容器**：只有一个顶层子节点（哪怕它内部再复杂）一律放行 —— 那正是绝大多数卡片的写法
  （规则 B 只对「该唯一子节点是**多发射助手**」这一种情况收紧）。
* **`label` / `leadingIcon` 等槽为何不在规则 A 名单里**：它们在 MD3 文本框里确实是 Box 槽，
  但同名形参在本仓被大量**非文本框**组件复用（`NavigationBarItem` / `FilterChip` / 自绘底栏等，
  2026-10-02 实测 `label = {` 72 处），其内容槽并非 Box。闸门宁可窄而准，不可宽而误报——
  误报会诱使后来者放宽判据（本仓明令禁止）。`supportingText` 9 处调用点全部是文本框，独占名单。
* **静态启发式**：不解析 Kotlin AST，靠括号/花括号配平与字符串/注释跳过。故
  ① 经变量间接传入的 lambda（`val slot = { … }` 后传参）看不见；② 经 `if` / `when` 择一包一层的
  lambda（如 `supportingText = if (err) { { Text(…) } } else null`，本仓 `EntryEditFormSections`
  即此形态）看不见；③ 多层嵌套的间接（助手再调助手）只解一层；④ 非 `@Composable` 标注的发射器
  （返回 `@Composable` 的高阶函数）看不见。**不得**据其绿推定「全仓已无叠放」。
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
# 「发射节点」的行判定拆成三条**线性**检查（刻意不用单条大正则：`x.a?.let {` 这类接收者链
# 用嵌套量词表达会被 CodeQL 判 py/redos，本仓 §355/§356 已因同类写法吃过告警）。
CONTROL_LINE_RE = re.compile(r"^(?:if|for|while|when)\b")
UPPER_CALL_LINE_RE = re.compile(r"^[A-Z]\w*\s*[({]")
# 作用域函数：`x.let {` / `x?.forEach {` 等——**接收者可任意长**，故先切 `{` 再判后缀
SCOPE_FUN_NAMES = ("forEach", "map", "let", "run", "apply", "also")
STACK_ALIGNMENTS = ("contentAlignment", "Alignment.")  # 显式声明叠放意图的 Box 不报（此处仅作记录）
# 调用名后面可能直接跟 trailing lambda 的 `{`，也可能跟参数表的 `(`。
# 单独成常量是因为 `[\({]` 写进 rf-string 会被解析成 f-string 表达式起始（Python 3.11 口径）。
CALL_PUNCT = "[({]"
# 扫描时排除的目录段：`参考项目/` 是**只读参考实现**（AGENTS §3.3，其缺陷不归本仓整改），
# `build/` 与 `.git/` 是生成物。不排除会把参考项目的站点算进读数（实测曾混入 Monica 的 PanePrimitives）。
EXCLUDED_PARTS = {"参考项目", "build", ".git", ".gradle", "preview-exports"}

# 规则 A：框架（Material3 文本框）Box 内容槽的形参名。名单只有 `supportingText` 一项是**刻意**的
# ——见模块 docstring「`label` / `leadingIcon` 等槽为何不在名单里」：同名形参在本仓被大量非文本框
# 组件复用（那些槽不是 Box），把它们纳入会产出误报，而误报会诱使后来者放宽/摘除判据（本仓明禁）。
FRAMEWORK_BOX_SLOT_PARAMS = ("supportingText",)
# 槽形参直传 lambda：`supportingText = { … }`。`= if (…) { { … } } else null` 这类择一形态
# 静态看不见，已在 docstring 的边界里如实登记（宁可漏报不可误报）。
SLOT_LAMBDA_RES = tuple(
    re.compile(rf"\b{name}\s*=\s*\{{") for name in FRAMEWORK_BOX_SLOT_PARAMS
)
COMPOSABLE_FUN_RE = re.compile(
    r"\bfun\s+(?P<name>[A-Za-z_]\w*)\s*\("
)
# ISSUE-P3-457：**零布局节点**的组合体（副作用 / 状态工具）。它们不发射任何参与排版的节点，
# 若计入「顶层发射数」会把 `SecurePasswordField`（`LaunchedEffect` + `DisposableEffect` +
# `OutlinedTextField`）这类正常实现误判成「多发射助手」——实测第一版即命中
# `EntryEditComponents.kt:86 BentoCard { SecurePasswordField(...) }` 的误报。
# 名单按平台已知 API 枚举；**出现新同类 API 时补名单，而不是放宽规则**（误报会诱使后来者
# 怀疑整个判据，本仓明禁借误报放宽）。
NO_LAYOUT_NODE_CALLS = (
    "LaunchedEffect",
    "DisposableEffect",
    "SideEffect",
    "produceState",
    "rememberUpdatedState",
)
MODIFIER_ONLY_RE = re.compile(
    r"(?:(?:public|internal|private|protected|expect|actual|inline|suspend|operator|override|external)\b\s*)*"
)


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


def is_scope_call(line: str) -> bool:
    """`x.let {` / `x?.forEach {` / `x.map {` 形态（接收者任意长，故先切 `{` 再判后缀）。"""
    brace = line.find("{")
    seg = (line[:brace] if brace >= 0 else line).rstrip()
    return any(seg.endswith("." + n) or seg.endswith("?." + n) for n in SCOPE_FUN_NAMES)


def is_emitter_line(stripped: str) -> bool:
    """一行顶层语句是否**可能发射节点**：控制流 / 具名组件调用 / 作用域函数调用。

    `val` / `var` / 赋值 / 注释一律不是发射行 —— 早先口径漏掉了「接收者带链」的作用域函数
    （`uiState.errorMessage?.let { … }` 不匹配旧 `[a-z]\\w*\\.(let|…)`），于是
    `UnlockPasswordSupportingText` 只数出 1 个节点、规则 B 对 P3-457 原形**无鉴别力**
    （实测：拿修复前源码反校，`box_slot_stacked_sites=0` 假绿）。修正后同一份修复前源码命中。
    """
    if CONTROL_LINE_RE.match(stripped) or UPPER_CALL_LINE_RE.match(stripped):
        return True
    return is_scope_call(stripped)


def counts_as_node(body: str, idx: int, stripped: str) -> bool:
    """一行顶层语句是否**真的发射一个布局节点**。

    * 具名组件调用 → 只要不是 [NO_LAYOUT_NODE_CALLS] 里的「零节点」组合体，就算 1 个节点；
    * 控制流 / 作用域函数（`if` / `when` / `for` / `while` / `x.forEach|let|…`）→ **看块内**：
      块里没有任何会发射节点的调用就计 0（如 `if (cond) { x = y }` 纯赋值），否则计 1。
      这条细化是必须的：`SecurePasswordField` 的首个 `if` 只做预填赋值，
      早期口径把它当成一个节点，于是「单节点组件」被误判为多发射助手。
    """
    callee = callee_name(stripped)
    if callee:
        return callee not in NO_LAYOUT_NODE_CALLS
    brace = body.find("{", idx)
    if brace < 0:
        return False
    end = _match(body, brace)
    if end < 0:
        return False
    return len(top_level_emits(body[brace + 1: end], 1)) >= 1


def top_level_emits(body: str, first_line: int) -> list[tuple[int, str]]:
    """统计一段「组合体」（lambda 体 / 函数块体）内**同层（深度 0）**发射的节点（行号, 首行文本）。

    发射判定见 [is_emitter_line] + [counts_as_node]：具名组件调用计 1（零节点组合体除外）；
    控制流 / 作用域函数计 1 当且仅当其块内确有节点（`if` / `when` 运行时只走一条分支，
    故不会重复计数）。`val` / `var` / 注释 / 空行不计。
    """
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
                if (
                    depth == 0
                    and is_emitter_line(stripped)
                    and counts_as_node(body, k, stripped)
                ):
                    kids.append((line_no, stripped[:60]))
        if c in "({":
            depth += 1
        elif c in ")}":
            depth -= 1
        k += 1
    return kids


def emits_at_brace(src: str, brace_idx: int) -> list[tuple[int, str]]:
    """给定 `{` 下标，返回其块体内同层发射节点。"""
    if brace_idx >= len(src) or src[brace_idx] != "{":
        return []
    end = _match(src, brace_idx)
    if end < 0:
        return []
    return top_level_emits(src[brace_idx + 1: end], src[:brace_idx].count("\n") + 1)


def callee_name(node_line: str) -> str:
    """从发射节点首行取出「被调用的组件名」；非大写开头的裸调用返回空串。"""
    m = re.match(r"^([A-Z]\w*)\s*[({]", node_line)
    return m.group(1) if m else ""


def is_composable_fun(src: str, fun_idx: int) -> bool:
    """`fun` 关键字所在处是否带 `@Composable`（同一行前缀 / 上方注解行，允许夹注释与其它注解）。"""
    lines = src[:fun_idx].split("\n")
    tail = lines[-1].strip()
    if tail:
        if tail.startswith("@Composable"):
            return True
        # 同行还有修饰符前缀（`internal ` 之类）⇒ 继续往上看
        if not MODIFIER_ONLY_RE.fullmatch(tail):
            return False
    i = len(lines) - 2
    while i >= 0:
        t = lines[i].strip()
        if not t or t.startswith("//"):
            i -= 1
            continue
        if t.startswith("@"):
            if t.startswith("@Composable"):
                return True
            i -= 1
            continue
        return False
    return False


def multi_emit_composables(root: pathlib.Path) -> set[str]:
    """全仓「多发射助手函数」：块体**顶层发射 ≥2 个节点**的 `@Composable` 函数名。

    这类函数不能被当作 Box 内容槽的**唯一**子节点（见模块 docstring 规则 B）——它发射的兄弟会
    直接落进那个 Box，而调用点自身只有 1 个节点，规则 A 看不见。
    """
    names: set[str] = set()
    for f in kt_files(root):
        src = f.read_text(encoding="utf-8")
        if "@Composable" not in src:
            continue
        for m in COMPOSABLE_FUN_RE.finditer(src):
            if not is_composable_fun(src, m.start()):
                continue
            open_paren = m.end() - 1
            close = _match(src, open_paren)
            if close < 0:
                continue
            # `)` 与 `{` 之间只允许「空白 + 返回类型注解」；出现 `=`（表达式体）、`(`（高阶返回类型）
            # 或跨行者一律不认（宁可漏报：把非块体误当块体会给错判据找借口）。
            seg = ""
            j = close + 1
            while j < len(src) and src[j] not in "{=":
                seg += src[j]
                j += 1
            if j >= len(src) or src[j] != "{" or "(" in seg or seg.count("\n") > 1:
                continue
            if len(emits_at_brace(src, j)) >= 2:
                names.add(m.group("name"))
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
    return emits_at_brace(src, j)


def scan(
    root: pathlib.Path, components: set[str]
) -> tuple[list[str], int, int, set[str]]:
    """返回（报红行, BoxScope 组件调用点数, 框架槽调用点数, 多发射助手集合）。

    调用点数必须一起报出来：否则「0 处命中」可能只是「一个站点都没看到」的假绿
    （本仓反复踩过的形态 —— 绿要有鉴别力，得能证明它看过东西）。
    """
    bad: list[str] = []
    sites = 0
    slot_sites = 0
    multi = multi_emit_composables(root)
    for f in kt_files(root):
        src = f.read_text(encoding="utf-8")
        # ---- 原有规则：本仓 BoxScope 内容槽组件 ----
        for name in components:
            for m in re.finditer(rf"\b{re.escape(name)}\s*{CALL_PUNCT}", src):
                # 排除组件自身声明：`fun BentoCard(` 的函数体不是「调用方的同层子节点」
                if src[: m.start()].rstrip().endswith("fun"):
                    continue
                sites += 1
                line = src[: m.start()].count("\n") + 1
                kids = stacked_children(src, m.start())
                where = ", ".join(f"{ln}:{txt}" for ln, txt in kids)
                if len(kids) >= 2:
                    bad.append(f"{f}:{line} [BoxScope 内容槽] {len(kids)} 个同层子节点会互相叠放 ⇒ {where}")
                elif len(kids) == 1 and callee_name(kids[0][1]) in multi:
                    bad.append(
                        f"{f}:{line} [BoxScope 内容槽] 唯一子节点 {callee_name(kids[0][1])}() "
                        f"顶层发射 ≥2 节点 ⇒ 兄弟会落进同一个 Box（规则 B）"
                    )
        # ---- ISSUE-P3-457 规则 A/B：框架（Material3 文本框）Box 内容槽 ----
        for rx in SLOT_LAMBDA_RES:
            for m in rx.finditer(src):
                slot_sites += 1
                line = src[: m.start()].count("\n") + 1
                brace = src.index("{", m.start())
                kids = emits_at_brace(src, brace)
                where = ", ".join(f"{ln}:{txt}" for ln, txt in kids)
                if len(kids) >= 2:
                    bad.append(
                        f"{f}:{line} [框架 Box 内容槽] {len(kids)} 个同层子节点会互相叠放（规则 A）⇒ {where}"
                    )
                elif len(kids) == 1 and callee_name(kids[0][1]) in multi:
                    bad.append(
                        f"{f}:{line} [框架 Box 内容槽] 唯一子节点 {callee_name(kids[0][1])}() "
                        f"顶层发射 ≥2 节点 ⇒ 兄弟会落进同一个 Box（规则 B）"
                    )
    return bad, sites, slot_sites, multi


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

# ---- ISSUE-P3-457 两条新规则的样本 ----
# 规则 A 坏样本：框架文本框 supportingText 槽里平铺两个 Text（真机事故原形）
BAD_SAMPLE_FRAMEWORK_SLOT = """
@Composable
fun Screen() {
    OutlinedTextField(
        value = "",
        onValueChange = {},
        supportingText = {
            Text("生物识别验证未通过或已取消")
            Text("已自动载入记住的密钥文件: usr.dat")
        }
    )
}
"""

# 规则 B 坏样本：槽里只发一个节点，但那个节点是「顶层发射 ≥2 节点」的助手函数
BAD_SAMPLE_MULTI_EMIT_HELPER = """
@Composable
private fun SupportingText(showError: Boolean) {
    Text("错误文案")
    if (showError) {
        Text("已自动载入记住的密钥文件")
    }
}

@Composable
fun Screen() {
    OutlinedTextField(
        value = "",
        onValueChange = {},
        supportingText = { SupportingText(showError = true) }
    )
}
"""

# 规则 A/B 好样本：槽里只有一个 Column（助手自身也不再是「多发射」，故规则 B 亦放行）
GOOD_SAMPLE_FRAMEWORK_SLOT = """
@Composable
private fun SupportingText(showError: Boolean) {
    Column {
        Text("错误文案")
        if (showError) {
            Text("已自动载入记住的密钥文件")
        }
    }
}

@Composable
fun Screen() {
    OutlinedTextField(
        value = "",
        onValueChange = {},
        supportingText = { SupportingText(showError = true) }
    )
}
"""


def selftest() -> int:
    """已知坏样本必须命中、已知好样本必须不命中（判据本身反校）。

    两种调用形态各验一次：`Card { … }`（裸 trailing lambda）与 `Card(modifier = …) { … }`
    ——早先版本只认后者，前者静默漏检，正是「目测登记前提成立、实际不成立」的那类工具缺陷。
    ISSUE-P3-457 扩面后，规则 A（框架槽同层多子节点）与规则 B（槽里放「多发射助手函数」）
    各自也必须有独立样本：判据扩面而样本不扩面，等于新判据从未被反校过。
    """
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="boxslot-selftest-"))
    tmp.mkdir(parents=True, exist_ok=True)
    rc = 0
    cases = (
        ("坏样本(裸 trailing lambda 同层两兄弟)", BAD_SAMPLE_BARE, True),
        ("坏样本(带参数 + 同层两兄弟)", BAD_SAMPLE, True),
        ("好样本(同层只有一个 Column)", GOOD_SAMPLE, False),
        ("坏样本(规则A：框架槽 supportingText 平铺两 Text)", BAD_SAMPLE_FRAMEWORK_SLOT, True),
        ("坏样本(规则B：框架槽放多发射助手函数)", BAD_SAMPLE_MULTI_EMIT_HELPER, True),
        ("好样本(规则A/B：框架槽放 Column 包好的助手)", GOOD_SAMPLE_FRAMEWORK_SLOT, False),
    )
    for label, text, expect_bad in cases:
        p = tmp / f"{abs(hash(text))}.kt"
        p.write_text(text, encoding="utf-8")
        comps = box_slot_components(tmp)
        hits, _sites, _slot_sites, multi = scan(tmp, comps)
        got = any(p.name in h for h in hits)
        ok = got == expect_bad
        print(
            f"[selftest] {label}: 组件={sorted(comps)} 多发射助手={sorted(multi)} "
            f"命中={got} 期望={expect_bad} ⇒ {'OK' if ok else 'FAIL'}"
        )
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
    hits, sites, slot_sites, multi = scan(root, comps)
    print(f"BoxScope 内容槽组件：{sorted(comps)}")
    print(f"多发射助手函数（顶层发射 ≥2 节点的 @Composable）：{len(multi)} 个")
    print(f"检查过的调用点：BoxScope 组件={sites}  框架 supportingText 槽={slot_sites}")
    for h in hits:
        print(f"STACK {h}")
    print(f"box_slot_stacked_sites={len(hits)}")
    if comps and sites == 0:
        # 有组件却零站点 ⇒ 要么调用方全在排除目录里，要么根目录给错了；这种「绿」没有鉴别力，判红
        print("⇒ 未发现任何调用点：该读数**不构成**「全仓已无叠放」的证据（很可能是 --root 或排除口径给错了）")
        return 1
    if not comps and slot_sites == 0:
        # 两侧都没看到任何站点 ⇒ 同样是没有鉴别力的「绿」，不得当作通过
        print("⇒ 既未发现 BoxScope 内容槽组件、也未发现框架槽调用点：该读数同样不构成证据")
        return 1
    if hits:
        print("⇒ 上述内容槽的同层子节点（或助手的顶层发射）会互相叠放，须包进 Column/Row/Box。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
