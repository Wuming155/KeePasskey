#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""KeePasskey `AndroidApp` 应用关联字段的**独立实现侧**互操作对拍。

依据 AGENTS.md 规则 8：本仓 writer → 本仓 reader 的往返恒为绿，证明不了库文件级互操作。
本脚本用两个**非本仓**实现读同一份产物并交叉核对：

1. `pykeepass`（KDBX 解析独立实现）逐键读 `custom_properties`、判定 `<Value Protected="True">`
   保护位，并确认 `URL` 标准字段未被关联写入改动；
2. `keepassxc-cli`（KeePassXC 官方 CLI）`-s --all` 读同库，交叉核对键名与值；
3. 复刻 KeePassDX `AppOriginEntryField.parseFields`（0 起连续、遇缺即停）与
   `AndroidOrigin` 的「包名 + 签名摘要」归一比对，验证第三方按包名 + 签名能匹配。

产物由 JVM 探针生成（默认 `app/build/interop-probe/`）：

    ./gradlew :app:testDebugUnitTest --tests "*AndroidAppBindingInteropProbeTest*"
    python tools/kdbx-app-binding-interop/verify_app_binding.py

退出码：`0` 全部判据成立；`1` 对拍失败 / 产物缺失；`2` 环境缺失（非本仓实现不可用，
**不得当绿**）。摘要行只列实际运行过的实现。
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_PROBE_DIR = ROOT / "app" / "build" / "interop-probe"
MANIFEST_NAME = "keepasskey-app-binding-probe.expected.json"

APP_KEY = "AndroidApp"
SIG_KEY = "AndroidApp Signature"
HEX_CHARS = set("0123456789ABCDEF")


# ── KeePassDX 口径复刻（独立实现，不 import 本仓任何代码） ──────────────────────

def suffix(position: int) -> str:
    return f"_{position}" if position > 0 else ""


def parse_bindings(fields: dict[str, str]) -> list[tuple[str, str | None]]:
    """AppOriginEntryField.parseFields: 从 0 连续遍历，遇 appId 缺失即停；签名可缺。"""
    result: list[tuple[str, str | None]] = []
    position = 0
    while True:
        s = suffix(position)
        app_id = fields.get(APP_KEY + s)
        if not app_id:
            break
        signature = fields.get(SIG_KEY + s) or None
        result.append((app_id, signature))
        position += 1
    return result


def normalize_hex(value: str) -> str:
    return "".join(
        c for c in value.split("##SIG##")[0].upper() if c in HEX_CHARS
    )


def check_android_origin(
    fields: dict[str, str], package: str, fingerprint: str
) -> bool:
    """AppOrigin.checkAndroidOrigin 同口径：包名相等 +（未声明签名 or 摘要归一相等）。"""
    for app_id, signature in parse_bindings(fields):
        if app_id != package:
            continue
        if signature is None or normalize_hex(signature) == normalize_hex(fingerprint):
            return True
    return False


def selftest() -> bool:
    ok = True

    def check(label: str, actual: bool, expected: bool) -> None:
        nonlocal ok
        if actual != expected:
            print(f"  FAIL selftest {label}: 实际 {actual} 期望 {expected}")
            ok = False

    fields = {
        APP_KEY: "com.a.app",
        SIG_KEY: "AA:BB",
        f"{APP_KEY}_1": "com.b.app",
        f"{APP_KEY}_3": "com.d.app",  # 编号不连续，遇缺即停
    }
    check("连续编号遇缺即停", len(parse_bindings(fields)) == 2, True)
    check("签名归一去冒号", normalize_hex("aa:bb:cc") == "AABBCC", True)
    check("多签名取首段", normalize_hex("AA:BB##SIG##CC:DD") == "AABB", True)
    check("包名+签名命中", check_android_origin(fields, "com.a.app", "AABB"), True)
    check("签名不符不命中", check_android_origin(fields, "com.a.app", "FFFF"), False)
    check("无签名组按包名命中", check_android_origin(fields, "com.b.app", "FFFF"), True)
    check("未绑定包不命中", check_android_origin(fields, "com.zzz.app", "AABB"), False)
    check("空洞后的孤立键不解析", check_android_origin(fields, "com.d.app", "AABB"), False)
    print("  · 口径判据 selftest " + ("通过" if ok else "失败"))
    return ok


# ── 对拍 ────────────────────────────────────────────────────────────────────

def protected_flags(entry) -> dict[str, bool]:
    """从 pykeepass 解密后的 XML 元素读 `<Value Protected="True">` 属性。"""
    flags: dict[str, bool] = {}
    for string_el in entry._element.findall("String"):
        key_el = string_el.find("Key")
        value_el = string_el.find("Value")
        if key_el is None or value_el is None or key_el.text is None:
            continue
        raw = value_el.get("Protected", "False")
        flags[key_el.text] = raw.lower() == "true"
    return flags


def main() -> int:
    parser = argparse.ArgumentParser(description="AndroidApp binding interop probe")
    parser.add_argument("--probe-dir", type=pathlib.Path, default=DEFAULT_PROBE_DIR)
    parser.add_argument("--selftest", action="store_true", help="只跑口径判据反校")
    args = parser.parse_args()

    if args.selftest:
        return 0 if selftest() else 1
    if not selftest():
        return 1

    manifest_path = args.probe_dir / MANIFEST_NAME
    if not manifest_path.is_file():
        print(f"FAIL: 探针清单缺失 {manifest_path}", file=sys.stderr)
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    db_path = args.probe_dir / manifest["database"]
    if not db_path.is_file():
        print(f"FAIL: 探针库缺失 {db_path}", file=sys.stderr)
        return 1
    password = manifest["password"]
    expected_entries = manifest["entries"]
    fingerprints = manifest.get("caller_fingerprints", [])

    try:
        from pykeepass import PyKeePass
    except ImportError:
        print("FAIL: 缺 pykeepass（独立实现不可用，不构成互操作证据）", file=sys.stderr)
        return 2
    cli = shutil.which("keepassxc-cli")
    if cli is None:
        print("FAIL: 缺 keepassxc-cli（官方 CLI 交叉核对不可执行）", file=sys.stderr)
        return 2

    failures: list[str] = []
    ran: list[str] = []

    try:
        kp = PyKeePass(str(db_path), password=password)
    except Exception as exc:  # noqa: BLE001 - 独立实现解锁失败即判据失败
        print(f"FAIL: pykeepass 解锁失败: {exc}", file=sys.stderr)
        return 1
    ran.append("pykeepass")
    by_title = {e.title: e for e in kp.entries}
    print(f"pykeepass 解锁成功，条目数 {len(kp.entries)}")

    for expected in expected_entries:
        title = expected["title"]
        entry = by_title.get(title)
        if entry is None:
            failures.append(f"pykeepass 未找到条目 {title!r}")
            continue
        actual_url = entry.url or ""
        if actual_url != expected["url"]:
            failures.append(
                f"{title}: URL 被改动（期望 {expected['url']!r} 实际 {actual_url!r}）"
            )
        props = entry.custom_properties or {}
        flags = protected_flags(entry)
        for field in expected["fields"]:
            key, value, want_protected = field["key"], field["value"], field["protected"]
            if props.get(key) != value:
                failures.append(
                    f"{title}/{key}: 值不符（期望 {value!r} 实际 {props.get(key)!r}）"
                )
            got_protected = flags.get(key)
            if got_protected is None:
                failures.append(f"{title}/{key}: 未在 XML 中找到该字段")
            elif got_protected != want_protected:
                failures.append(
                    f"{title}/{key}: 保护位不符（期望 {want_protected} 实际 {got_protected}）"
                )
            else:
                print(f"  · {title}/{key}: 值一致，Protected={got_protected}")
        # 不该多出清单外的 AndroidApp* 键
        extra = set(props) - {f["key"] for f in expected["fields"]}
        extra_binding_keys = {k for k in extra if k.startswith(APP_KEY)}
        if extra_binding_keys:
            failures.append(f"{title}: 多出绑定键 {sorted(extra_binding_keys)}")

        # KeePassDX 口径：连续解析与包名 + 签名比对
        bindings = parse_bindings(props)
        if len(bindings) != len({k for k in props if re.fullmatch(APP_KEY + r"(_\d+)?", k)}):
            failures.append(f"{title}: 绑定序列解析数量与键数不符")
        for app_id, signature in bindings:
            if signature is None:
                if not check_android_origin(props, app_id, "00" * 32):
                    failures.append(f"{title}: 无签名组 {app_id} 按包名匹配失败")
                continue
            normalized = normalize_hex(signature)
            if not any(normalize_hex(fp) == normalized for fp in fingerprints):
                failures.append(f"{title}: 签名值 {signature} 不在调用方摘要清单中")
            if not check_android_origin(props, app_id, signature):
                failures.append(f"{title}: 包名+签名匹配失败 {app_id}")
            if check_android_origin(props, app_id, "FF" * 32):
                failures.append(f"{title}: 错误签名竟然命中 {app_id}")
        print(f"  · {title}: KeePassDX 连续解析 {len(bindings)} 组绑定")

    # 官方 CLI 交叉核对：键名与值都要出现在 show 输出里
    for expected in expected_entries:
        title = expected["title"]
        proc = subprocess.run(
            [cli, "show", "-q", "--all", "-s", str(db_path), title],
            input=password + "\n",
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            failures.append(
                f"keepassxc-cli show {title!r} 退出码 {proc.returncode}: {proc.stderr.strip()[:200]}"
            )
            continue
        output = proc.stdout
        for field in expected["fields"]:
            if field["key"] not in output:
                failures.append(f"keepassxc-cli {title}: 未见键 {field['key']}")
            if field["value"] and field["value"] not in output:
                failures.append(f"keepassxc-cli {title}: 未见值 {field['key']}")
        print(f"  · keepassxc-cli show {title!r}: 键与值交叉核对完成")
    ran.append("keepassxc-cli")

    print("实际运行实现：" + "、".join(ran))
    if failures:
        for f in failures:
            print("FAIL: " + f, file=sys.stderr)
        print(f"✗ 对拍失败（{len(failures)} 条）", file=sys.stderr)
        return 1
    print("✓ 对拍通过：两个独立实现读数与 KeePassDX 匹配口径全部成立")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
