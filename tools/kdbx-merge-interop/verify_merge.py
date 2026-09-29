#!/usr/bin/env python3
"""ISSUE-P3-384 AC④：合并产物 pykeepass 独立实现对拍。

用法（先由 JVM 探针 / 单测落盘产物，或直接对任意 .kdbx 断言条目集合）:
  python tools/kdbx-merge-interop/verify_merge.py <merged.kdbx> <password> --expect-entry TITLE [--expect-entry TITLE2 ...]

退出码 0 = 全部判据成立；1 = 失败。
"""
from __future__ import annotations

import argparse
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description="pykeepass merge interop probe")
    parser.add_argument("kdbx")
    parser.add_argument("password")
    parser.add_argument("--expect-entry", action="append", default=[], dest="expect_entries")
    parser.add_argument("--expect-min-entries", type=int, default=0)
    args = parser.parse_args()

    try:
        from pykeepass import PyKeePass
    except ImportError:
        print("FAIL: 缺少 pykeepass 依赖", file=sys.stderr)
        return 1

    kp = PyKeePass(args.kdbx, password=args.password)
    titles = [e.title for e in kp.entries]
    print(f"pykeepass 解锁成功，条目数 {len(titles)}")
    for t in titles:
        print(f"  · {t}")

    ok = True
    if len(titles) < args.expect_min_entries:
        print(f"FAIL: 条目数 {len(titles)} < 期望下限 {args.expect_min_entries}")
        ok = False
    for expected in args.expect_entries:
        if expected not in titles:
            print(f"FAIL: 未找到期望条目 {expected!r}")
            ok = False
        else:
            print(f"  · 命中期望条目 {expected!r}")

    if ok:
        print("✓ 对拍通过：pykeepass 独立实现读数满足全部判据")
        return 0
    print("✗ 对拍失败")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
