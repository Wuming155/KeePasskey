#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""单元测试结果聚合计数（**固定尺子**）。

为什么要有这个脚本（§190 实测教训）：全量 `test` 之后仓里 `*/build/test-results/**/*.xml`
**不止**单元测试一种——`app/build/test-results/updateDebugScreenshotTest/` 之下还有
截图包装用例的 XML，且它是**上一批遗留的旧文件**（`--rerun-tasks` 不会重跑它、也不会删它）。
把它一并扫进来会凭空多出 1 份 XML / 2 条用例，于是「2203 → 2207」这种**跨批不可比**的数字
就这么进了文档。同类失真此前已由 §165 §7（测量点早于写盘）、§175（一次性脚本漏报 10 条）、
§178（字符串里的括号毁配平）各立过一次规矩。

判据（四条，改动须同步本文件）：
1. **只统计目录名匹配 `test<Variant>UnitTest` 的 XML**（AGP 的 JVM 单测输出目录形态）；
   截图测试（`updateDebugScreenshotTest` / `*ScreenshotTest`）、instrumented 结果一律排除；
2. 一个 `testsuite` 元素 = 一个测试类；`tests` / `failures` / `errors` / `skipped` 逐份相加；
3. **额外报告**被排除的 XML 清单——排除项必须可见，否则「少了一类」会静默变成「数字对不上」；
4. **零 XML / 零 `testsuite` 元素 = exit 2（不得当绿）**（`ISSUE-P3-514`）：
   「`failures=0 errors=0`」在**没跑**与**跑了且全绿**两种情形下输出一致，靠人读输出行区分
   等于把判据外包给读者。与 `preserve_test_failures.py:27` 的 exit-2 立据同型
   （「无法判别『没红』还是『没跑』」），故此处也必须 fail-closed。

退出码：
- `0` = 有证据且零 failures / errors（真绿）；
- `1` = 有 failures / errors；
- `2` = 无证据（未找到任何模块目录 / 零 XML / 零 `testsuite` 元素）。

用法：
    python tools/doc/count_test_results.py            # 汇总
    python tools/doc/count_test_results.py --excluded # 只看被排除的目录
    python tools/doc/count_test_results.py --selftest # 口径反校（内嵌正/反样本，含零证据态）

输入只读本仓自产 `build/test-results/**`（非外部输入），故用标准库解析器即可。
"""
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

UNIT_TEST_DIR_RE = re.compile(r'^test.*UnitTest$')


def walk(root='app'):
    for dirpath, dirnames, filenames in os.walk(root):
        for name in filenames:
            if name.startswith('TEST-') and name.endswith('.xml'):
                yield os.path.join(dirpath, name)


def aggregate(include_dirs):
    """对给定模块根做聚合，返回 `(totals, included, excluded)`——与 main 同一口径，供 selftest 复用。"""
    included, excluded = [], []
    for module in include_dirs:
        for path in walk(module):
            dir_name = os.path.basename(os.path.dirname(path))
            (included if UNIT_TEST_DIR_RE.match(dir_name) else excluded).append(path)
    totals = Counter()
    for path in included:
        root = ET.parse(path).getroot()
        if root.tag != 'testsuite':
            continue
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(root.get(key, 0) or 0)
        totals['files'] += 1
    return totals, included, excluded


def verdict(totals) -> int:
    """退出码判据：`2` = 无证据（不得当绿），`1` = 有红，`0` = 有证据且全绿。"""
    if totals['files'] == 0:
        return 2
    return 0 if totals['failures'] == 0 and totals['errors'] == 0 else 1


# —— --selftest 内嵌样本（已知值反校；含「零证据」反样本，锁 ISSUE-P3-514）——
SAMPLE_SUITE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.FooTest" tests="2" failures="0" errors="0" skipped="0">
  <testcase classname="com.example.FooTest" name="ok1" time="0.01"/>
  <testcase classname="com.example.FooTest" name="ok2" time="0.01"/>
</testsuite>
"""


def _write_sample(base: Path, module: str, dir_name: str, xml_text: str) -> None:
    d = base / module / "build" / "test-results" / dir_name
    d.mkdir(parents=True, exist_ok=True)
    (d / "TEST-com.example.SampleTest.xml").write_text(xml_text, encoding="utf-8")


def selftest() -> int:
    checks = []
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        _write_sample(base, "app", "testDebugUnitTest", SAMPLE_SUITE)
        totals, included, excluded = aggregate([str(base / "app")])
        checks.append(("正向样本计入 1 份 / 2 条用例",
                       totals['files'] == 1 and totals['tests'] == 2))
        checks.append(("正向样本判绿（exit 0）", verdict(totals) == 0))
        # 反样本①：只有被排除目录（截图测试）⇒ 零证据，必须为 2
        _write_sample(base, "app", "updateDebugScreenshotTest", SAMPLE_SUITE)
        totals2, _, _ = aggregate([str(base / "app")])
        checks.append(("截图目录不计入主计数", totals2['files'] == 1))
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        (base / "app").mkdir(parents=True)
        totals3, _, _ = aggregate([str(base / "app")])
        checks.append(("零 XML ⇒ exit 2（不得当绿）", verdict(totals3) == 2))
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        # 反样本②：目录名匹配但根元素非 testsuite ⇒ 同样零证据
        _write_sample(base, "app", "testDebugUnitTest",
                      '<?xml version="1.0" encoding="UTF-8"?>\n<testsuites/>')
        totals4, _, _ = aggregate([str(base / "app")])
        checks.append(("根元素非 testsuite ⇒ exit 2", verdict(totals4) == 2))
    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"  [{'ok' if ok else 'FAIL'}] {name}")
    print("count_test_results --selftest: " + ("FAIL" if failed else "PASS"))
    return 1 if failed else 0


def main() -> int:
    if '--selftest' in sys.argv:
        return selftest()
    module_roots = [m for m in ('app', 'core', 'crypto', 'database', 'sync')
                    if os.path.isdir(m)]
    if not module_roots:
        print('未找到任何模块目录（须在仓库根运行）', file=sys.stderr)
        return 2

    totals, included, excluded = aggregate(module_roots)

    if '--excluded' in sys.argv:
        for path in excluded:
            print(path)
        print(f'excluded_xml={len(excluded)}')
        # 与汇总分支同口径（ISSUE-P3-514）：本分支的「零」语义是「无被排除项」，属正常态
        # （仓里没有截图测试遗留 XML 即为 0），故不按无证据处理，但**同步提示**主计数是否有证据。
        if totals['files'] == 0:
            print('::warning::主计数同样为零证据——请确认是否已跑 `gradlew test`')
        return 0

    print(
        f"xml={totals['files']} tests={totals['tests']} "
        f"failures={totals['failures']} errors={totals['errors']} skipped={totals['skipped']}"
    )
    if excluded:
        kinds = Counter(os.path.basename(os.path.dirname(p)) for p in excluded)
        print(f"已排除非 JVM 单测 XML：{dict(kinds)}"
              f"（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）")
    code = verdict(totals)
    if code == 2:
        print("::error::未计入任何 JVM 单测 testsuite——无法判别「没红」还是「没跑」，"
              "按 ISSUE-P3-514 判无证据（exit 2，不得当绿）", file=sys.stderr)
    return code


if __name__ == '__main__':
    sys.exit(main())
