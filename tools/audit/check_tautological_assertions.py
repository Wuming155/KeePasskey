#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""「永远为真的断言」机检（ISSUE-P3-299 立规，§275 建立）。

要治的病症（§299 实测样本，`SecurityTest.kt` 的重言断言）：

```kotlin
val elapsedMillis = 100_000L - 30_000L   // 测试体内本地算出
val timeoutMillis = 60 * 1000L           // 测试体内本地算出
assertTrue(elapsedMillis >= timeoutMillis)   // 不触任何生产代码 ⇒ 恒为真
```

该断言对被测行为**零鉴别力**：删掉生产实现它照样绿，唯一作用是让用例数 +1。
本仓的覆盖真相由此被**高估**——这是比「没测」更坏的一类失真（§147 测试资产纪律的对偶：
资产只增不删的前提是「它确实在测东西」）。

判据（三条，改动须同步本文件与 `AGENTS.md` §5）：

1. **判定单位＝测试函数体**（带 `@Test` 标注的函数，按大括号配平切块）；跨函数取值的
   局部量一律按**不纯**处理（宁可漏报，不可误报）。
2. **「纯局部值」**＝函数体内 `val NAME = EXPR`（**`var` 一律不算**——见下文反校留痕），且 `EXPR`
   只由数值 / 字符串 / 布尔 / null 字面量、已判定的纯局部值、**同文件纯字面量 `const val`**、
   以及**白名单类型转换**（`toInt/toLong/toShort/toByte/toFloat/toDouble/toChar/toString`）
   经算术 / 比较 / 逻辑 / 范围（`..` / `until` / `downTo`）运算符组合而成。
   **出现任何其它调用或属性访问即出局**——因为那才可能触到生产代码。
3. 断言实参可按上述口径**全部**求值 ⇒ 判为「永远为真的断言」，以 `path:line` 报出、退出码 1。

已知边界（**如实声明，勿据本机检推定「无重言断言」**）：

- 本机检是**静态启发式**，不是求值器：只做**同函数体内**的符号解析，
  形如「`val x = 常量运算` + `assertTrue(x >= 0)`」会被抓到，
  而「`val list = listOf(...)` + `assertTrue(list.size > 3)`」这类经标准库容器的比较
  **会被漏报**（`listOf` 不是白名单转换 ⇒ 视为不纯）。
- **`var` 一律不判**（首版曾把 `var` 也当纯局部值 ⇒ 实测 65 处命中里绝大多数是误报：
  `var calls = 0` 这类**观测通道**由生产代码经回调写入，`assertEquals(1, calls)` 恰恰是
  最有鉴别力的断言形态）。凡「生产代码把值写进测试侧变量」的断言一律豁免。
- `assertThrows` / `assertFailsWith` / 含 lambda 实参的断言不参与判定（其形参是待测调用本身）。
- `assertEquals` 只判**后两个实参**（可能的消息串不参与），`assertTrue/False` 只判最后一个。
- 恒真的 `assertTrue(true)` 会被抓（属同类，无需豁免）。
- 本机检**只回答「该断言是否不经生产代码」**，不回答「该断言是否有价值」——
  经生产代码的弱断言不在本口径内。

反校（度量工具一律用已知值反校，§190 口径）：

    python tools/audit/check_tautological_assertions.py --selftest

内嵌正样本（必须抓到）与负样本（必须放过）各三条，自校验失败即退出码 2。

用法：

    python tools/audit/check_tautological_assertions.py            # 扫各模块 */src/{test,androidTest}
    python tools/audit/check_tautological_assertions.py <文件…>     # 只扫指定文件
"""
import os
import re
import sys

MODULES = ('app', 'core', 'crypto', 'database', 'sync')

# 断言名 → 参与判定的实参个数（从**右**数起；None = 不判定）
ASSERT_ARITY = {
    'assertTrue': (1, '最后一个实参'),
    'assertFalse': (1, '最后一个实参'),
    'assertNull': (1, '最后一个实参'),
    'assertNotNull': (1, '最后一个实参'),
    'assertEquals': (2, '后两个实参'),
    'assertNotEquals': (2, '后两个实参'),
    'assertSame': (2, '后两个实参'),
    'assertNotSame': (2, '后两个实参'),
    'assertArrayEquals': (2, '后两个实参'),
    'assertContentEquals': (2, '后两个实参'),
}

# 白名单「纯转换」：出现即视为不触生产代码
PURE_CONVERSIONS = ('toInt', 'toLong', 'toShort', 'toByte',
                    'toFloat', 'toDouble', 'toChar', 'toString')
# 白名单「纯中缀」：范围 / 步进，均属 stdlib 语法糖
PURE_INFIX = ('until', 'downTo', 'step', 'rangeTo')

STRING_RE = re.compile(r'"[^"\n]*"|\'[^\'\n]*\'')
CONVERSION_CALL_RE = re.compile(r'\.(?:' + '|'.join(PURE_CONVERSIONS) + r')\(\)')
CALL_RE = re.compile(r'\b[A-Za-z_]\w*\s*\(')
IDENT_RE = re.compile(r'\b[A-Za-z_]\w*\b')
DECL_RE = re.compile(
    r'^\s*(?:(?:private|internal|public|protected)\s+)?(?:const\s+)?val\s+(\w+)\s*'
    r'(?::\s*[^=\n]+?)?=\s*(.+)$'
)
TEST_FUN_RE = re.compile(r'@Test\b')
FUN_RE = re.compile(r'\bfun\s+(?:`[^`]*`|[\w.]+)\s*\(')


def mask(source: str) -> str:
    """把注释与字符串**内容**抹成空格（保留换行与引号），使结构解析免受内容干扰。

    抹内容而非删行 ⇒ 字符偏移与行号完全不变，报出行号即可直接定位。
    """
    out = list(source)
    i, n = 0, len(source)
    state = 'code'
    while i < n:
        ch = source[i]
        two = source[i:i + 2]
        three = source[i:i + 3]
        if state == 'code':
            if two == '//':
                state = 'line'
                out[i] = out[i + 1] = ' '
                i += 2
                continue
            if two == '/*':
                state = 'block'
                out[i] = out[i + 1] = ' '
                i += 2
                continue
            if three == '"""':
                state = 'raw'
                out[i] = out[i + 1] = out[i + 2] = ' '
                i += 3
                continue
            if ch == '"':
                state = 'string'
                i += 1
                continue
            if ch == "'":
                state = 'char'
                i += 1
                continue
            i += 1
            continue
        if state == 'line':
            if ch == '\n':
                state = 'code'
            else:
                out[i] = ' '
            i += 1
            continue
        if state == 'block':
            if two == '*/':
                out[i] = out[i + 1] = ' '
                i += 2
                state = 'code'
                continue
            if ch != '\n':
                out[i] = ' '
            i += 1
            continue
        if state == 'raw':
            if three == '"""':
                out[i] = out[i + 1] = out[i + 2] = ' '
                i += 3
                state = 'code'
                continue
            if ch != '\n':
                out[i] = ' '
            i += 1
            continue
        if state == 'string':
            if ch == '\\':
                out[i] = out[i + 1] = ' '
                i += 2
                continue
            if ch == '"' or ch == '\n':
                state = 'code'
            else:
                out[i] = ' '
            i += 1
            continue
        if state == 'char':
            if ch == '\\':
                out[i] = out[i + 1] = ' '
                i += 2
                continue
            if ch == "'" or ch == '\n':
                state = 'code'
            else:
                out[i] = ' '
            i += 1
            continue
    return ''.join(out)


def match_paren(text: str, open_idx: int) -> int:
    """返回与 text[open_idx]（必为 `(`）配对的 `)` 下标；不配平则返回 -1。"""
    depth = 0
    for idx in range(open_idx, len(text)):
        if text[idx] == '(':
            depth += 1
        elif text[idx] == ')':
            depth -= 1
            if depth == 0:
                return idx
    return -1


def split_args(arg_text: str) -> list:
    """按顶层逗号切分实参（括号 / 方括号 / 大括号三层配平）。"""
    args, depth, start = [], 0, 0
    for idx, ch in enumerate(arg_text):
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        elif ch == ',' and depth == 0:
            args.append(arg_text[start:idx])
            start = idx + 1
    tail = arg_text[start:]
    if tail.strip() or args:
        args.append(tail)
    return args


def block_of(text: str, start: int) -> str:
    """从 start 起找首个 `{` 并返回配平块内容（用于取函数体）。"""
    open_idx = text.find('{', start)
    if open_idx < 0:
        return ''
    depth = 0
    for idx in range(open_idx, len(text)):
        if text[idx] == '{':
            depth += 1
        elif text[idx] == '}':
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:idx]
    return text[open_idx + 1:]


def collect_declarations(body: str) -> list:
    """收集函数体内的 `val/var NAME = EXPR`（多行初始化式按括号配平续行）。"""
    decls, lines = [], body.split('\n')
    idx = 0
    while idx < len(lines):
        m = DECL_RE.match(lines[idx])
        if not m:
            idx += 1
            continue
        name, expr = m.group(1), m.group(2)
        depth = expr.count('(') + expr.count('[') + expr.count('{') \
            - expr.count(')') - expr.count(']') - expr.count('}')
        while depth > 0 and idx + 1 < len(lines):
            idx += 1
            expr += '\n' + lines[idx]
            depth = expr.count('(') + expr.count('[') + expr.count('{') \
                - expr.count(')') - expr.count(']') - expr.count('}')
        decls.append((name, expr))
        idx += 1
    return decls


def is_pure(expr: str, pure_names: set) -> bool:
    """表达式是否只由字面量 / 纯局部值 / 纯 const / 白名单转换与运算符构成。"""
    text = STRING_RE.sub('""', expr)
    # 数值字面量：去掉小数点与下划线分隔，避免与成员访问混淆
    text = re.sub(r'\b\d[\d_]*\.\d[\d_]*\b', '1', text)
    text = re.sub(r'\b\d[\d_]*[fFdDLl]\b', '1', text)
    text = CONVERSION_CALL_RE.sub('', text)
    for word in PURE_INFIX:
        text = re.sub(r'\b' + word + r'\b', ' ', text)
    if '.' in text:                      # 残余的点 ⇒ 属性访问
        return False
    if CALL_RE.search(text):            # 残余的调用 ⇒ 出局
        return False
    for ident in IDENT_RE.findall(text):
        if ident in ('true', 'false', 'null'):
            continue
        if ident in pure_names:
            continue
        return False
    return True


def file_const_names(masked: str) -> set:
    """同文件的纯字面量 `const val`（顶层与 companion object 内的都算）。"""
    pure = set()
    for line in masked.split('\n'):
        m = re.search(r'\bconst\s+val\s+(\w+)\s*(?::[^=]+)?=\s*(.+)$', line)
        if m:
            expr = m.group(2)
            if '.' not in STRING_RE.sub('""', expr) and not CALL_RE.search(expr):
                pure.add(m.group(1))
    return pure


def analyse(masked: str, const_names: set) -> list:
    """扫描一个（已 mask 的）测试源文件，返回命中清单 [(行号, 断言名, 判据说明)]。"""
    hits = []
    for tm in TEST_FUN_RE.finditer(masked):
        fm = FUN_RE.search(masked, tm.end())
        if not fm:
            continue
        body = block_of(masked, fm.end())
        if not body:
            continue
        body_offset = masked.index(body, fm.end()) if body in masked else fm.end()
        decls = collect_declarations(body)
        pure_names = set(const_names)
        for _ in range(len(decls) + 1):          # 迭代至不动点
            changed = False
            for name, expr in decls:
                if name not in pure_names and is_pure(expr, pure_names):
                    pure_names.add(name)
                    changed = True
            if not changed:
                break
        for am in re.finditer(r'(?<![\w.])(' + '|'.join(ASSERT_ARITY) + r')\s*\(', body):
            name = am.group(1)
            open_idx = am.end() - 1
            close_idx = match_paren(body, open_idx)
            if close_idx < 0:
                continue
            arg_text = body[open_idx + 1:close_idx]
            if '{' in arg_text:                  # lambda 实参：形参是待测调用本身
                continue
            args = [a.strip() for a in split_args(arg_text)]
            need = ASSERT_ARITY[name][0]
            considered = args[-need:]
            if len(considered) < need:
                continue
            if all(a and is_pure(a, pure_names) for a in considered):
                line = masked.count('\n', 0, body_offset + open_idx) + 1
                hits.append((line, name, ASSERT_ARITY[name][1]))
    return hits


def scan_file(path: str) -> list:
    with open(path, 'r', encoding='utf-8') as handle:
        masked = mask(handle.read())
    return analyse(masked, file_const_names(masked))


def default_targets() -> list:
    targets = []
    for module in MODULES:
        if not os.path.isdir(module):
            continue
        for kind in ('test', 'androidTest'):
            root = os.path.join(module, 'src', kind)
            for dirpath, _dirs, files in os.walk(root):
                targets.extend(os.path.join(dirpath, f)
                               for f in sorted(files) if f.endswith('.kt'))
    return sorted(targets)


SELFTEST_POSITIVE = '''
class Positive {
    private val THIRTY = 30
    @Test
    fun `本地算出的比较`() {
        val now = 100_000L
        val background = 30_000L
        val elapsed = now - background
        val timeout = 60 * 1000L
        assertTrue(elapsed >= timeout)
        assertFalse(elapsed < timeout)
    }

    @Test
    fun `同文件 const 也算本地`() {
        val n = THIRTY * 1000L
        assertTrue(n >= 30_000L)
    }

    @Test
    fun `assertEquals 双常量`() {
        assertEquals(60, 30 * 2)
    }
}
'''

SELFTEST_NEGATIVE = '''
class Negative {
    private val alias = KeystoreManager(null)
    @Test
    fun `走生产判据`() {
        val elapsed = 100_000L - 30_000L
        assertTrue(AutoLockTimeoutPolicy.isExpired(60, elapsed))
    }

    @Test
    fun `成员访问不算本地`() {
        val ciphertext = ByteArray(4)
        assertEquals(4, ciphertext.size)
    }

    @Test
    fun `含 lambda 实参不判`() {
        assertThrows(IllegalStateException::class.java) { fail("boom") }
    }

    @Test
    fun `var 观测通道不判`() {
        var calls = 0
        guard.register { calls++ }
        assertEquals(1, calls)
    }
}
'''


def selftest() -> int:
    pos = analyse(mask(SELFTEST_POSITIVE), file_const_names(mask(SELFTEST_POSITIVE)))
    neg = analyse(mask(SELFTEST_NEGATIVE), file_const_names(mask(SELFTEST_NEGATIVE)))
    print(f'正样本（应命中 3 处）：{len(pos)} 处 {[(l, n) for l, n, _ in pos]}')
    print(f'负样本（应命中 0 处）：{len(neg)} 处 {[(l, n) for l, n, _ in neg]}')
    if len(pos) != 3 or neg:
        print('自校验失败：机检口径与内嵌样本不符（改动判据后须同步本节样本）', file=sys.stderr)
        return 2
    print('自校验通过：正样本全中 / 负样本零误报')
    return 0


def main() -> int:
    if '--selftest' in sys.argv:
        return selftest()
    args = [a for a in sys.argv[1:] if not a.startswith('-')]
    targets = args if args else default_targets()
    if not targets:
        print('未找到任何测试文件（须在仓库根运行）', file=sys.stderr)
        return 2
    hits = 0
    for path in targets:
        for line, name, caliber in scan_file(path):
            hits += 1
            print(f'HIT {path}:{line}  {name}  ← {caliber}全部为测试体内局部表达式（不经生产代码）')
    print(f'汇总：命中 {hits} 处 / 扫描 {len(targets)} 个测试文件')
    if hits:
        print('⇒ 判据：断言的操作数全部可在测试体内求值 ⇒ 对被测行为零鉴别力。'
              '修法＝改走生产判据（禁删除用例，测试资产纪律 ①）。')
    return 1 if hits else 0


if __name__ == '__main__':
    sys.exit(main())
