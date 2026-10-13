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
2. 设备 `lastUpdateTime` 换算为 UTC 秒区间后，整个区间**不早于**本地 APK 的 epoch mtime；
   整个区间早于 mtime 判红，区间与 mtime 重叠返回 2，禁止增加时间容差而放行；
3. 每个 `--expect-symbol` 都出现在 APK 的 `classes*.dex` 中——**决定性判据**：mtime 会被
   `git checkout` / 还原 / 重打等操作扰动，而「本批新增的类 / 方法名」不会；这一条直接回答
   「我要验的那段代码到底在不在包里」。

退出码：0 = 三条全过；1 = 判据不成立（红）；**2 = 无法判定**（无 adb / 无设备 / 无包信息 /
时区查询或解析失败 / DST 歧义或不存在的墙钟 / 秒精度重叠）。
「无法判定」**不得**当作绿——这正是本工具存在的意义（同 `check_resolved_index_sync` 的解析失败即红口径）。

能力边界（如实声明）：本工具**只**比对「设备安装时间 × 本地 APK mtime × APK 内符号」，因此
「源码改了但没重新打包」这类情形**只**能靠判据 3 抓到——即调用方必须把本批新增的类 / 方法名
经 `--expect-symbol` 传进来。刻意**不**引入「源码 mtime vs APK mtime」的推断：mtime 会被
`git checkout` / 还原 / 重打等操作重写（本工具立规的那次事故里，正是 git 操作与打包交错），
不可靠的度量会训练人忽略告警。

时间来源：当前 `dumpsys package` 仅给无偏移的秒级墙钟；查询前后分别读取设备
`persist.sys.timezone`，不一致即返回 2。由 ZoneInfo 按安装日期对应的历史偏移转换，
不借用宿主时区或设备当前 offset。DST 重复时刻枚举 fold 并经 UTC 往返核验，不能唯一
定位即返回 2。时间与符号不构成设备 APK 与本地 APK 的哈希相同证明。
依赖：Python >= 3.9 的 zoneinfo 及 IANA 时区数据库。Windows 通常没有系统 IANA 库，
需要预先安装 CPython 官方 tzdata 包（`python -m pip install tzdata`）；缺库返回 2，
不自动安装、不默认 UTC。--selftest 也需要时区库，缺失不能伪装成验收通过。
官方依据：https://docs.python.org/3/library/datetime.html
          https://docs.python.org/3/library/zoneinfo.html

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
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from unittest.mock import patch

try:
    from zoneinfo import ZoneInfo
except ImportError:
    ZoneInfo = None

DEFAULT_APK = os.path.join('app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
DEFAULT_PACKAGE = 'com.keepasskey.debug'
LAST_UPDATE_RE = re.compile(r'^\s*lastUpdateTime=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s*$', re.MULTILINE)
DEX_NAME_RE = re.compile(r'classes\d*\.dex')
ADB_TIMEOUT_SECONDS = 60
TIME_FORMAT = '%Y-%m-%d %H:%M:%S'
NANOSECONDS_PER_SECOND = 1_000_000_000
UTC_EPOCH = dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)


def parse_last_update_time(dumpsys_text):
    """从 `dumpsys package` 输出取设备安装时间；取不到返回 None（调用方须按「无法判定」处理）。"""
    values = LAST_UPDATE_RE.findall(dumpsys_text or '')
    if not values or len(set(values)) != 1:
        return None
    try:
        return dt.datetime.strptime(values[0], TIME_FORMAT)
    except ValueError:
        return None


def installed_utc_ns(wall_time, timezone_name):
    """按历史偏移转换秒区间下界；fold 重复或 gap 不存在的本地时刻均拒绝猜测。"""
    if wall_time is None or not timezone_name or ZoneInfo is None:
        raise ValueError('缺少安装时间、设备时区或 Python zoneinfo')
    try:
        zone = ZoneInfo(timezone_name)
    except (KeyError, ValueError) as error:
        raise ValueError(f'未知设备时区或缺少 IANA/tzdata：{timezone_name}') from error
    candidates = set()
    for fold in (0, 1):
        utc = wall_time.replace(tzinfo=zone, fold=fold).astimezone(dt.timezone.utc)
        # 直接附着 tzinfo 不验证 gap；UTC 往返才能排除不存在的本地时间。
        if utc.astimezone(zone).replace(tzinfo=None) == wall_time:
            delta = utc - UTC_EPOCH
            seconds = delta.days * 86400 + delta.seconds
            candidates.add(seconds * NANOSECONDS_PER_SECOND)
    if len(candidates) != 1:
        raise ValueError('安装墙钟处于 DST 重复/不存在区间，无法唯一换算 UTC')
    return candidates.pop()


def compare_installation_time(installed_ns, apk_mtime_ns):
    """设备秒区间为 [下界, 下界+1秒)；重叠返回 2，绝不容差放行。"""
    if installed_ns >= apk_mtime_ns:
        return 0
    if installed_ns + NANOSECONDS_PER_SECOND <= apk_mtime_ns:
        return 1
    return 2


def adb_output(arguments):
    """adb 查询失败必须进入无法判定；没有设备时不能解析错误输出为包信息。"""
    result = subprocess.run(['adb', 'shell', *arguments], capture_output=True,
                            text=True, timeout=ADB_TIMEOUT_SECONDS)
    if result.returncode != 0:
        raise ValueError('adb 查询失败（设备离线、未授权或多设备？）')
    return result.stdout


def read_installation_time(package):
    """读取包信息时夹取时区快照；发生时区变化直接拒绝混合读数。"""
    zone_before = adb_output(['getprop', 'persist.sys.timezone']).strip()
    dump = adb_output(['dumpsys', 'package', package])
    zone_after = adb_output(['getprop', 'persist.sys.timezone']).strip()
    if not zone_before or zone_before != zone_after:
        raise ValueError('设备时区缺失或在查询期间变化')
    wall = parse_last_update_time(dump)
    if wall is None:
        raise ValueError(f'设备未安装 {package}，或 lastUpdateTime 缺失/格式损坏')
    return installed_utc_ns(wall, zone_before), wall, zone_before


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
    checks.append(('损坏日期 ⇒ None', parse_last_update_time('lastUpdateTime=2026-13-03 20:35:10') is None))
    checks.append(('小数秒不得被静默截断', parse_last_update_time('lastUpdateTime=2026-10-03 20:35:10.123') is None))
    checks.append(('多安装时间冲突 ⇒ None', parse_last_update_time(
        'lastUpdateTime=2026-10-03 20:35:10\nlastUpdateTime=2026-10-03 20:35:11') is None))

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
        checks.extend(time_selftests(hits))
        checks.extend(adb_selftests(hits))

    for name, passed in checks:
        print(f'[{"ok" if passed else "FAIL"}] {name}')
    all_ok = all(passed for _, passed in checks)
    print(f'--- check_installed_build_selftest={"OK" if all_ok else "FAIL"} ---')
    return 0 if all_ok else 1


def mock_main(apk_path, wall_text='', zones=('Asia/Shanghai', 'Asia/Shanghai'),
              mtime_ns=0, symbols=(), adb_error=None, returncode=0, returncodes=None):
    """合成 adb 输出并进入真实 CLI 路径；不连接设备，也不调整任何文件时间。"""
    codes = returncodes if returncodes is not None else (returncode,) * 3
    responses = [subprocess.CompletedProcess([], code, value, '')
                 for code, value in zip(codes, (zones[0], wall_text, zones[1]))]
    effect = adb_error if adb_error is not None else responses
    args = ['--apk', apk_path]
    for symbol in symbols:
        args.extend(['--expect-symbol', symbol])
    with patch.object(subprocess, 'run', side_effect=effect), \
            patch.object(os, 'stat', return_value=type('Stat', (), {'st_mtime_ns': mtime_ns})()), \
            patch.object(os.path, 'isfile', return_value=True), \
            redirect_stdout(StringIO()), redirect_stderr(StringIO()):
        return main(args)


def time_selftests(apk_path):
    """在真实 main 比较结果上锁定跨区正反例、历史 DST 与纳秒边界。"""
    checks = []
    # 固定绝对时刻 2026-10-03 12:35:10 UTC；毫秒/纳秒均不经浮点取整。
    epoch_ns = 1791030910 * NANOSECONDS_PER_SECOND
    fixtures = [('GMT', '2026-10-03 12:35:10'),
                ('Asia/Shanghai', '2026-10-03 20:35:10'),
                ('America/New_York', '2026-10-03 08:35:10')]
    try:
        for zone, wall in fixtures:
            installed = installed_utc_ns(dt.datetime.strptime(wall, TIME_FORMAT), zone)
            checks.append((f'{zone} 同一绝对时刻', installed == epoch_ns))
            for suffix, mtime, expected in [('真正新包', epoch_ns - 1, 0),
                                            ('真正旧包', epoch_ns + NANOSECONDS_PER_SECOND, 1),
                                            ('同秒小数精度重叠', epoch_ns + 1, 2),
                                            ('同秒相等下界', epoch_ns, 0)]:
                code = mock_main(apk_path, f'lastUpdateTime={wall}', (zone, zone), mtime)
                checks.append((f'{zone} {suffix} ⇒ {expected}', code == expected))
        winter = installed_utc_ns(dt.datetime(2026, 1, 15, 7), 'America/New_York')
        summer = installed_utc_ns(dt.datetime(2026, 7, 15, 8), 'America/New_York')
        checks.append(('历史冬季偏移 -05:00', winter == 1768478400 * NANOSECONDS_PER_SECOND))
        checks.append(('历史夏季偏移 -04:00', summer == 1784116800 * NANOSECONDS_PER_SECOND))
    except ValueError as error:
        checks.append((f'时区自测环境缺失：{error}', False))
    for wall, label in [('2026-11-01 01:30:00', 'DST fold 重复时刻'),
                        ('2026-03-08 02:30:00', 'DST gap 不存在时刻')]:
        code = mock_main(apk_path, f'lastUpdateTime={wall}', ('America/New_York',) * 2)
        checks.append((f'{label} ⇒ 2', code == 2))
    with patch.dict(os.environ, {'TZ': 'Pacific/Honolulu'}):
        code = mock_main(apk_path, 'lastUpdateTime=2026-10-03 20:35:10', mtime_ns=epoch_ns - 1)
        checks.append(('宿主 TZ 无关 ⇒ 0', code == 0))
    return checks


def adb_selftests(apk_path):
    """查询失败、时区与符号的独立出口均经 CLI 验证，防止假绿。"""
    checks = []
    sample = 'lastUpdateTime=2026-10-03 20:35:10'
    scenarios = [('未知时区', {'zones': ('Unknown/Zone',) * 2}),
                 ('时区缺失', {'zones': ('', '')}),
                 ('查询期间时区改变', {'zones': ('GMT', 'Asia/Shanghai')}),
                 ('包信息缺失', {'wall_text': 'no package'}),
                 ('日期损坏', {'wall_text': 'lastUpdateTime=2026-13-03 20:35:10'}),
                 ('无 adb', {'adb_error': FileNotFoundError()}),
                 ('adb 超时', {'adb_error': subprocess.TimeoutExpired('adb', 60)}),
                 ('无设备/非零退出', {'returncode': 1}),
                 ('dumpsys 非零但有安装行', {'returncodes': (0, 1, 0)}),
                 ('第二次时区查询失败', {'returncodes': (0, 0, 1)})]
    for label, overrides in scenarios:
        arguments = {'wall_text': sample, **overrides}
        checks.append((f'{label} ⇒ 2', mock_main(apk_path, **arguments) == 2))
    with patch.object(sys.modules[__name__], 'ZoneInfo', None):
        checks.append(('无 zoneinfo ⇒ 2', mock_main(apk_path, sample) == 2))
    with patch.object(sys.modules[__name__], 'ZoneInfo', side_effect=KeyError('tzdata')):
        checks.append(('无 IANA/tzdata ⇒ 2', mock_main(apk_path, sample) == 2))
    for symbol, expected in [('KeyFileSourceRowContent', 0), ('MissingSymbol', 1)]:
        checks.append((f'符号 {symbol} 独立判据 ⇒ {expected}',
                       mock_main(apk_path, sample, symbols=(symbol,)) == expected))
    checks.append(('多个符号任一缺失 ⇒ 1', mock_main(
        apk_path, sample, symbols=('KeyFileSourceRowContent', 'MissingSymbol')) == 1))
    overlap_ns = 1791030910 * NANOSECONDS_PER_SECOND + 1
    checks.append(('时间重叠且符号存在仍 ⇒ 2', mock_main(
        apk_path, sample, mtime_ns=overlap_ns, symbols=('KeyFileSourceRowContent',)) == 2))
    with patch.object(os.path, 'isfile', return_value=False), \
            patch.object(subprocess, 'run') as runner, redirect_stderr(StringIO()):
        code = main(['--apk', apk_path])
        checks.append(('缺本地 APK ⇒ 2 且不查询 adb', code == 2 and not runner.called))
    return checks


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
    try:
        apk_mtime_ns = os.stat(args.apk).st_mtime_ns
        installed_ns, wall, zone = read_installation_time(args.package)
    except OSError as error:
        print(f'无法判定：APK 读取失败或找不到 adb：{error}', file=sys.stderr)
        return 2
    except subprocess.TimeoutExpired:
        print('无法判定：adb 超时（设备离线或未授权？）', file=sys.stderr)
        return 2
    except (ValueError, OverflowError) as error:
        print(f'无法判定：{error}', file=sys.stderr)
        return 2
    code = compare_installation_time(installed_ns, apk_mtime_ns)
    print(f'本地 APK        = {args.apk}')
    print(f'  mtime epoch ns = {apk_mtime_ns}')
    print(f'设备安装墙钟    = {wall:{TIME_FORMAT}} [{zone}] （包 {args.package}）')
    print(f'设备 UTC 区间 ns = [{installed_ns}, {installed_ns + NANOSECONDS_PER_SECOND})')
    if code == 1:
        print('✗ 设备上的包早于本地 APK：先 adb install -r', file=sys.stderr)
    elif code == 2:
        print('无法判定：设备秒级安装时间与 APK mtime 重叠，需补充身份验证', file=sys.stderr)
    elif code == 0:
        print('✓ 设备安装时间不早于本地 APK')

    try:
        for symbol in args.expect_symbol:
            found = apk_contains_symbol(args.apk, symbol)
            print(f'{"✓" if found else "✗"} APK 内符号 {symbol}：{"在" if found else "不在"}')
            if not found:
                code = 1
    except (OSError, zipfile.BadZipFile) as error:
        print(f'无法判定：APK 无法读取：{error}', file=sys.stderr)
        return 2
    label = {0: 'OK', 1: 'FAIL', 2: 'UNKNOWN'}[code]
    print(f'--- check_installed_build={label} ---')
    return code


if __name__ == '__main__':
    sys.exit(main())
