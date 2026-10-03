#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""装机走查前置读数：**设备上跑的到底是不是你要验的那份代码**（§434 立规）。

立规缘由（实测事故）：首次真机走查报「不通过」——新 UI 完全没出现，而导入回执正常显示。
排查后为**过期二进制**：本地 APK 构建 `19:45:39`、设备 `lastUpdateTime` `19:45:42`，
而该批源码改到 `19:53`、提交在 `20:03` ⇒ 设备跑的是改动**之前**的包。
该现象与真缺陷**外观完全一致**（回执成功 + 新 UI 不存在），白走一轮走查，
因此必须做成可执行读数，而不是靠记性或纪律。

判据（三条，全部须成立；改动须同步本文件）：
1. 本地 APK 存在，且设备已安装同名包（`adb shell dumpsys package <包名>` 输出含包信息）；
2. 设备 `lastUpdateTime` **不早于**本地 APK 的文件 mtime——否则设备跑的不是这份包（本工具核心判据）；
3. 每个 `--expect-symbol` 都出现在 APK 的 `classes*.dex` 中——**决定性判据**：mtime 会被
   `git checkout` / 还原 / 重打等操作扰动，而「本批新增的类 / 方法名」不会；这一条直接回答
   「我要验的那段代码到底在不在包里」。

退出码：0 = 三条全过；1 = 判据不成立（红）；**2 = 无法判定**（无 adb / 无设备 / 无包信息）。
「无法判定」**不得**当作绿——这正是本工具存在的意义（同 `check_resolved_index_sync` 的解析失败即红口径）。

能力边界（如实声明）：本工具**只**比对「设备安装时间 × 本地 APK mtime × APK 内符号」，因此
「源码改了但没重新打包」这类情形**只**能靠判据 3 抓到——即调用方必须把本批新增的类 / 方法名
经 `--expect-symbol` 传进来。刻意**不**引入「源码 mtime vs APK mtime」的推断：mtime 会被
`git checkout` / 还原 / 重打等操作重写（本工具立规的那次事故里，正是 git 操作与打包交错），
不可靠的度量会训练人忽略告警。

用法：
    python tools/device/check_installed_build.py
    python tools/device/check_installed_build.py --expect-symbol KeyFileSourceRowContent
    python tools/device/check_installed_build.py --selftest     # 口径反校（含反面样本）
"""
import argparse
import datetime as dt
import os
import re
import subprocess
import sys
import tempfile
import zipfile

DEFAULT_APK = os.path.join('app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
DEFAULT_PACKAGE = 'com.keepasskey.debug'
LAST_UPDATE_RE = re.compile(r'lastUpdateTime=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})')
DEX_NAME_RE = re.compile(r'classes\d*\.dex')
ADB_TIMEOUT_SECONDS = 60
TIME_FORMAT = '%Y-%m-%d %H:%M:%S'


def parse_last_update_time(dumpsys_text):
    """从 `dumpsys package` 输出取设备安装时间；取不到返回 None（调用方须按「无法判定」处理）。"""
    match = LAST_UPDATE_RE.search(dumpsys_text or '')
    if match is None:
        return None
    return dt.datetime.strptime(match.group(1), TIME_FORMAT)


def apk_contains_symbol(apk_path, symbol):
    """APK 的 `classes*.dex` 是否含该符号名（debug 包不混淆，类 / 方法名原样保留）。"""
    needle = symbol.encode('utf-8')
    with zipfile.ZipFile(apk_path) as apk:
        for name in apk.namelist():
            if DEX_NAME_RE.fullmatch(name) and needle in apk.read(name):
                return True
    return False


def selftest():
    """口径反校：解析样例 + 符号判据**正反两向**（防「永远为真」的断言，ISSUE-P3-195 立规精神）。"""
    checks = []

    sample = (
        '    versionName=0.1.0\n'
        '    lastUpdateTime=2026-10-03 20:35:10\n'
        '      firstInstallTime=2026-10-01 16:48:11'
    )
    checks.append(
        ('解析 lastUpdateTime', parse_last_update_time(sample) == dt.datetime(2026, 10, 3, 20, 35, 10))
    )
    checks.append(('无 lastUpdateTime ⇒ None（不得当绿）', parse_last_update_time('no such line') is None))

    with tempfile.TemporaryDirectory() as tmp:
        hits = os.path.join(tmp, 'hits.apk')
        miss = os.path.join(tmp, 'miss.apk')
        with zipfile.ZipFile(hits, 'w') as archive:
            archive.writestr('classes.dex', b'xxKeyFileSourceRowContentxx')
        with zipfile.ZipFile(miss, 'w') as archive:
            archive.writestr('classes.dex', b'yySomethingElseEntirelyyy')
            archive.writestr('classes2.dex', b'zzThirdDexzz')
        checks.append(('正样本：含符号即判「在」', apk_contains_symbol(hits, 'KeyFileSourceRowContent')))
        checks.append(
            ('反面样本：不含符号必须判「不在」', not apk_contains_symbol(miss, 'KeyFileSourceRowContent'))
        )
        checks.append(
            ('多 dex：非首个 dex 命中也要判「在」', apk_contains_symbol(miss, 'ThirdDex'))
        )

    for name, passed in checks:
        print(f'[{"ok" if passed else "FAIL"}] {name}')
    all_ok = all(passed for _, passed in checks)
    print(f'--- check_installed_build_selftest={"OK" if all_ok else "FAIL"} ---')
    return 0 if all_ok else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description='装机走查前置读数：设备上跑的是不是你要验的那份代码')
    parser.add_argument('--apk', default=DEFAULT_APK, help=f'本地 APK 路径（默认 {DEFAULT_APK}）')
    parser.add_argument('--package', default=DEFAULT_PACKAGE, help=f'设备包名（默认 {DEFAULT_PACKAGE}）')
    parser.add_argument('--expect-symbol', action='append', default=[],
                        help='本批新增的类 / 方法名，可重复；出现即判「在包里」')
    parser.add_argument('--selftest', action='store_true', help='口径反校（不触碰设备 / APK）')
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if not os.path.isfile(args.apk):
        print(f'无法判定：本地 APK 不存在 —— {args.apk}（先跑 assembleDebug）', file=sys.stderr)
        return 2
    apk_mtime = dt.datetime.fromtimestamp(os.path.getmtime(args.apk))

    try:
        dump = subprocess.run(
            ['adb', 'shell', 'dumpsys', 'package', args.package],
            capture_output=True, text=True, timeout=ADB_TIMEOUT_SECONDS
        )
    except FileNotFoundError:
        print('无法判定：找不到 adb（platform-tools 未安装或不在 PATH）', file=sys.stderr)
        return 2
    except subprocess.TimeoutExpired:
        print('无法判定：adb 超时（设备离线或未授权？）', file=sys.stderr)
        return 2

    installed = parse_last_update_time(dump.stdout)
    if installed is None:
        print(f'无法判定：设备未安装 {args.package}，或 dumpsys 输出无 lastUpdateTime', file=sys.stderr)
        return 2

    ok = True
    print(f'本地 APK        = {args.apk}')
    print(f'  构建时间      = {apk_mtime:{TIME_FORMAT}}')
    print(f'设备安装时间    = {installed:{TIME_FORMAT}}  （包 {args.package}）')
    if installed < apk_mtime:
        ok = False
        print('✗ 设备上的包**早于**本地 APK ⇒ 设备跑的不是这份包：先 adb install -r', file=sys.stderr)
    else:
        print('✓ 设备安装时间不早于本地 APK')

    for symbol in args.expect_symbol:
        found = apk_contains_symbol(args.apk, symbol)
        print(f'{"✓" if found else "✗"} APK 内符号 {symbol}：{"在" if found else "不在"}')
        ok = ok and found

    print(f'--- check_installed_build={"OK" if ok else "FAIL"} ---')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
