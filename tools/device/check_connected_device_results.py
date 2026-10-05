#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""设备侧（instrumented）结果**非空转**断言：`tests == 0` 不得当绿（`ISSUE-P3-493` 立规）。

立规缘由（2026-10-05 实测）：AVD `emulator-5554`（`Pixel_10` / API 36，用户 0 `RUNNING_LOCKED`）上
两次 `:app:connectedDebugAndroidTest` 均产出 `<testsuites tests="0" .../>`、
`test-result-exit-code.txt` = `0`、`BUILD SUCCESSFUL`，而 `adb shell pm list packages` 显示设备上
**从未**装上 app / 测试包（UTP 未安装）。手动 `am instrument` 复现真实成因：
`INSTRUMENTATION_RESULT: shortMsg=Process crashed` + `INSTRUMENTATION_CODE: 0`。
⇒ `am instrument` 在「一个用例都没跑」时返回**成功码**，UTP 据此归为 `tests=0 + 成功`，
**整层设备门禁空转而显绿**。

这与 `ISSUE-P3-305`「闸门存在 ≠ 闸门被执行」同型，也与 `ISSUE-P2-491`「用例存在 ≠ 用例可编译」
互为两层：本条堵的是「任务绿 ≠ 用例真的跑了」。

判据（三条，改动须同步本文件）：
1. 凡建有 `src/androidTest` 的模块，`<模块>/build/outputs/androidTest-results/connected/**/TEST-*.xml`
   必须存在——否则该层**无法判定**（退出码 2），**不得**当绿；
2. 存在结果 XML 的层，各 XML 的 `tests` 之和必须 `> 0`——`tests == 0` 即**空转**，退出码 1；
   （属性缺失同样按 0 计，防「少写一个属性就蒙混过去」）
3. 逐模块打印 `tests / failures / errors / skipped` 读数，使「跑了几条」可见。

退出码：`0` = 各层 `tests > 0`；`1` = 存在 `tests == 0` 的层（空转显绿）；`2` = 无法判定（缺结果 XML）。
判据 1 与判据 2 同时命中时以 `1` 为准（空转是硬失败，优先于「判定不了」）。

能力边界（如实声明）：
- 本脚本**只**回答「这一层有没有真的跑用例」，**不**回答「跑得对不对」——失败 / 错误计数由
  Gradle 任务自身的退出码承担；`tools/doc/count_test_results.py` 是 **JVM 单测**的唯一尺子，
  两者口径不重叠（后者只统计目录名匹配 `test<Variant>UnitTest` 的 XML）。
- 也**不能**识别「跑的是过期二进制」（那是 `tools/device/check_installed_build.py` 的判据）。
- **不判新鲜度**：无法区分「本轮产出的结果」与「盘上遗留的上一轮结果」。CI 上
  `device-gate` 一次调用全模块 connected，各层结果同批产出，故不构成风险；本地**分模块单跑**
  时其余层的旧 XML 仍在盘上（属已知取舍：引入「结果 mtime vs APK mtime」判据需假设各模块
  APK 路径，假红面大于收益）。
- 另注：`Assume` 跳过在 UTP 的 XML 里记为 `<failure>`（`skipped` 恒 0，见 `AGENTS.md` §5），
  故**不得**把本脚本读出的 `failures` 当作「门槛数」——判定只取 `tests > 0` 与 Gradle 任务结果。

用法：
    python tools/device/check_connected_device_results.py
    python tools/device/check_connected_device_results.py --selftest   # 口径反校（含反面样本）
"""
import argparse
import glob
import io
import os
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter

# 五模块（与 docs/architecture/实现约定与验证现状.md §4.1 的「设备侧覆盖现状」同源）
MODULES = ('app', 'core', 'crypto', 'database', 'sync')
ANDROID_TEST_SRC = os.path.join('src', 'androidTest')
RESULTS_REL = os.path.join('build', 'outputs', 'androidTest-results', 'connected')
KEYS = ('tests', 'failures', 'errors', 'skipped')
DEVICE_DIR = os.path.join('debug', 'Pixel_10(AVD) - 16')  # 仅自校样本用


def counts(root):
    """取一份结果 XML 的计数：AGP（UTP）产出的是 `<testsuites tests=…>` **聚合属性在根元素上**，
    故优先读根元素；根元素无 `tests` 属性时才回退到逐 `<testsuite>` 子元素累加。"""
    if root.tag == 'testsuite' or root.get('tests') is not None:
        return {key: int(root.get(key, 0) or 0) for key in KEYS}
    totals = {key: 0 for key in KEYS}
    for suite in root:
        for key in KEYS:
            totals[key] += int(suite.get(key, 0) or 0)
    return totals


def layers(modules, base='.'):
    """返回 [(模块, 结果 XML 列表, 计数 Counter)]；只取建有 `src/androidTest` 的模块。"""
    rows = []
    for module in modules:
        if not os.path.isdir(os.path.join(base, module, ANDROID_TEST_SRC)):
            continue
        pattern = os.path.join(base, module, RESULTS_REL, '**', 'TEST-*.xml')
        files = sorted(glob.glob(pattern, recursive=True))
        totals = Counter()
        for path in files:
            for key, value in counts(ET.parse(path).getroot()).items():
                totals[key] += value
        rows.append((module, files, totals))
    return rows


def judge(rows, out=sys.stdout):
    """按判据 1 / 2 给出退出码，并打印逐层读数。"""
    empty, undecidable = [], []
    for module, files, totals in rows:
        if not files:
            undecidable.append(module)
            print(f'  ✗ {module:<9} 无结果 XML ⇒ 无法判定（UTP 可能根本没装包）', file=out)
            continue
        if totals['tests'] == 0:
            empty.append(module)
        mark = '✗' if totals['tests'] == 0 else '✓'
        print(
            f'  {mark} {module:<9} xml={len(files)} tests={totals["tests"]} '
            f'failures={totals["failures"]} errors={totals["errors"]} skipped={totals["skipped"]}',
            file=out,
        )
    if empty:
        print(f'--- connected_device_results=EMPTY(空转层：{"、".join(empty)}) ---', file=out)
        return 1
    if undecidable:
        print(
            f'--- connected_device_results=UNDECIDABLE(缺结果层：{"、".join(undecidable)}) ---',
            file=out,
        )
        return 2
    print('--- connected_device_results=OK（各层 tests > 0）---', file=out)
    return 0


def build_sample(root, samples):
    """自校用：`samples = {模块: [结果 XML 正文, ...]}`，空列表 ⇒ 该模块无结果 XML。"""
    for module, xmls in samples.items():
        os.makedirs(os.path.join(root, module, ANDROID_TEST_SRC), exist_ok=True)
        out_dir = os.path.join(root, module, RESULTS_REL, DEVICE_DIR)
        os.makedirs(out_dir, exist_ok=True)
        for index, content in enumerate(xmls):
            with open(os.path.join(out_dir, f'TEST-{module}-{index}.xml'),
                      'w', encoding='utf-8') as handle:
                handle.write(content)


def _verdict(samples):
    """自校用：建临时样本树并返回判据退出码。"""
    with tempfile.TemporaryDirectory() as tmp:
        build_sample(tmp, samples)
        return judge(layers(MODULES, base=tmp), out=io.StringIO())


def selftest():
    """口径反校：正样本 + 五个反面样本（聚合属性 / 子元素累加 / 空转 / 缺属性 / 缺结果层）。"""
    ok_suite = '<testsuites tests="18" failures="0" errors="0" skipped="0"/>'
    zero_suite = '<testsuites tests="0" failures="0" errors="0" skipped="0"/>'
    # 无根 `tests` 属性、只有子 `<testsuite>`（另一合法形态 ⇒ 必须回退累加，不得误判空转）
    child_suite = ('<testsuites><testsuite name="A" tests="2"/>'
                   '<testsuite name="B" tests="3"/></testsuites>')
    checks = [
        ('正样本：各层 tests > 0 ⇒ 0',
         _verdict({'app': [ok_suite], 'database': [ok_suite]}) == 0),
        ('正样本：聚合属性在子元素上（回退累加）⇒ 0',
         _verdict({'app': [child_suite], 'database': [ok_suite]}) == 0),
        ('反面样本一：tests="0" ⇒ 1（空转不得当绿）',
         _verdict({'app': [zero_suite], 'database': [ok_suite]}) == 1),
        ('反面样本二：tests 属性缺失（按 0 计）⇒ 1',
         _verdict({'app': ['<testsuites><testsuite name="X"/></testsuites>'],
                   'database': [ok_suite]}) == 1),
        ('反面样本三：整层缺结果 XML ⇒ 2（无法判定，不得当绿）',
         _verdict({'app': [], 'database': [ok_suite]}) == 2),
        ('反面样本四：空转 + 缺结果并存 ⇒ 1（硬失败优先）',
         _verdict({'app': [zero_suite], 'database': []}) == 1),
    ]
    for name, passed in checks:
        print(f'[{"ok" if passed else "FAIL"}] {name}')
    all_ok = all(passed for _, passed in checks)
    print(f'--- check_connected_device_results_selftest={"OK" if all_ok else "FAIL"} ---')
    return 0 if all_ok else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description='设备侧结果非空转断言（tests == 0 不得当绿）')
    parser.add_argument('--selftest', action='store_true', help='口径反校（不触碰设备 / 构建产物）')
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if not os.path.isdir('app'):
        print('无法判定：须在仓库根运行（找不到 app 模块目录）', file=sys.stderr)
        return 2

    rows = layers(MODULES)
    if not rows:
        print('无法判定：未发现任何 `src/androidTest` 源集', file=sys.stderr)
        return 2

    print('=== 设备侧（instrumented）结果非空转断言（ISSUE-P3-493）===')
    return judge(rows)


if __name__ == '__main__':
    sys.exit(main())
