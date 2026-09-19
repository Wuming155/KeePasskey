#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""单元测试结果聚合计数（**固定尺子**）。

为什么要有这个脚本（§190 实测教训）：全量 `test` 之后仓里 `*/build/test-results/**/*.xml`
**不止**单元测试一种——`app/build/test-results/updateDebugScreenshotTest/` 之下还有
截图包装用例的 XML，且它是**上一批遗留的旧文件**（`--rerun-tasks` 不会重跑它、也不会删它）。
把它一并扫进来会凭空多出 1 份 XML / 2 条用例，于是「2203 → 2207」这种**跨批不可比**的数字
就这么进了文档。同类失真此前已由 §165 §7（测量点早于写盘）、§175（一次性脚本漏报 10 条）、
§178（字符串里的括号毁配平）各立过一次规矩。

判据（三条，改动须同步本文件）：
1. **只统计目录名匹配 `test<Variant>UnitTest` 的 XML**（AGP 的 JVM 单测输出目录形态）；
   截图测试（`updateDebugScreenshotTest` / `*ScreenshotTest`）、instrumented 结果一律排除；
2. 一个 `testsuite` 元素 = 一个测试类；`tests` / `failures` / `errors` / `skipped` 逐份相加；
3. **额外报告**被排除的 XML 清单——排除项必须可见，否则「少了一类」会静默变成「数字对不上」。

用法：
    python tools/doc/count_test_results.py            # 汇总
    python tools/doc/count_test_results.py --excluded # 只看被排除的目录

输入只读本仓自产 `build/test-results/**`（非外部输入），故用标准库解析器即可。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

UNIT_TEST_DIR_RE = re.compile(r'^test.*UnitTest$')


def walk(root='app'):
    for dirpath, dirnames, filenames in os.walk(root):
        for name in filenames:
            if name.startswith('TEST-') and name.endswith('.xml'):
                yield os.path.join(dirpath, name)


def main() -> int:
    module_roots = [m for m in ('app', 'core', 'crypto', 'database', 'sync')
                    if os.path.isdir(m)]
    if not module_roots:
        print('未找到任何模块目录（须在仓库根运行）', file=sys.stderr)
        return 2

    included, excluded = [], []
    for module in module_roots:
        for path in walk(module):
            dir_name = os.path.basename(os.path.dirname(path))
            (included if UNIT_TEST_DIR_RE.match(dir_name) else excluded).append(path)

    if '--excluded' in sys.argv:
        for path in excluded:
            print(path)
        print(f'excluded_xml={len(excluded)}')
        return 0

    totals = Counter()
    for path in included:
        root = ET.parse(path).getroot()
        if root.tag != 'testsuite':
            continue
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(root.get(key, 0) or 0)
        totals['files'] += 1
    print(
        f"xml={totals['files']} tests={totals['tests']} "
        f"failures={totals['failures']} errors={totals['errors']} skipped={totals['skipped']}"
    )
    if excluded:
        kinds = Counter(os.path.basename(os.path.dirname(p)) for p in excluded)
        print(f"已排除非 JVM 单测 XML：{dict(kinds)}"
              f"（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）")
    return 0 if totals['failures'] == 0 and totals['errors'] == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
