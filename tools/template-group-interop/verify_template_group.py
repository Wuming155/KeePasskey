#!/usr/bin/env python3
"""ISSUE-P3-469 写侧对拍：Meta `EntryTemplatesGroup` 与模板组 UUID 一致性。

判据（双独立实现，缺一不可）:
  1. 官方 CLI（keepassxc-cli，C++）能解锁并导出 XML，且 `<Meta><EntryTemplatesGroup>`
     非空、指向库内一个真实分组；
  2. pykeepass（Python 独立实现）解出的 `EntryTemplatesGroup` 与官方 CLI **逐字相同**；
  3. 该 UUID 命中的分组存在，其名与 `--expect-group-name` 一致且条目数 ≥ `--expect-entry-count`
     （本仓安装的模板组含 5 个标准模板）。

用法（先由 JVM 探针 `TemplateGroupInteropProbeTest` 落盘产物，或直接对任意 .kdbx 断言）:
  python tools/template-group-interop/verify_template_group.py <db.kdbx> <password> \
      [--keepassxc-cli <path>] [--expect-group-name 模板] [--expect-entry-count 5]

退出码 0 = 全部判据成立；1 = 失败；2 = 环境缺失（无 keepassxc-cli / 无 pykeepass）。
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _find_first(elem: ET.Element, name: str):
    for child in elem.iter():
        if _local(child.tag) == name:
            return child
    return None


def _entry_templates_group(xml_bytes: bytes) -> str | None:
    root = ET.fromstring(xml_bytes)
    node = _find_first(root, "EntryTemplatesGroup")
    if node is None or node.text is None:
        return None
    return node.text.strip()


def _group_by_uuid(xml_bytes: bytes, uuid: str) -> tuple[str, int] | None:
    """按 UUID 找分组，返回 (组名, 直接条目数)；找不到返回 None。"""
    root = ET.fromstring(xml_bytes)
    for group in root.iter():
        if _local(group.tag) != "Group":
            continue
        uuid_node = None
        name_node = None
        entry_count = 0
        for child in group:
            tag = _local(child.tag)
            if tag == "UUID":
                uuid_node = child
            elif tag == "Name":
                name_node = child
            elif tag == "Entry":
                entry_count += 1
        if uuid_node is not None and (uuid_node.text or "").strip() == uuid:
            return ((name_node.text or "").strip() if name_node is not None else ""), entry_count
    return None


def _official_cli_xml(cli: str, kdbx: str, password: str) -> bytes:
    proc = subprocess.run(
        [cli, "export", "-f", "xml", kdbx],
        input=password.encode(),
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"keepassxc-cli 导出失败（rc={proc.returncode}）: {proc.stderr.decode(errors='replace').strip()}"
        )
    return proc.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description="ISSUE-P3-469 template-group interop probe")
    parser.add_argument("kdbx")
    parser.add_argument("password")
    parser.add_argument("--keepassxc-cli", default=shutil.which("keepassxc-cli"))
    parser.add_argument("--expect-group-name", default="模板")
    parser.add_argument("--expect-entry-count", type=int, default=5)
    args = parser.parse_args()

    if not args.keepassxc_cli:
        print("FAIL: 未找到 keepassxc-cli（环境缺失，不得当绿）", file=sys.stderr)
        return 2
    try:
        from pykeepass import PyKeePass
    except ImportError:
        print("FAIL: 缺少 pykeepass 依赖（环境缺失，不得当绿）", file=sys.stderr)
        return 2

    ok = True

    # ── ① 官方 CLI（C++）───────────────────────────────────────────
    try:
        cli_xml = _official_cli_xml(args.keepassxc_cli, args.kdbx, args.password)
    except RuntimeError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    cli_templates = _entry_templates_group(cli_xml)
    print(f"keepassxc-cli 解锁成功；<EntryTemplatesGroup> = {cli_templates!r}")
    if not cli_templates:
        print("FAIL: 官方 CLI 导出的 XML 中 EntryTemplatesGroup 为空/缺失")
        ok = False

    # ── ② pykeepass（独立实现）─────────────────────────────────────
    kp = PyKeePass(args.kdbx, password=args.password)
    raw = kp.xml()
    py_xml = raw if isinstance(raw, bytes) else raw.encode()
    py_templates = _entry_templates_group(py_xml)
    print(f"pykeepass 解锁成功；<EntryTemplatesGroup> = {py_templates!r}")

    if cli_templates and py_templates and cli_templates != py_templates:
        print(f"FAIL: 两实现读数不一致：cli={cli_templates!r} pykeepass={py_templates!r}")
        ok = False
    if not py_templates:
        print("FAIL: pykeepass 解出的 EntryTemplatesGroup 为空/缺失")
        ok = False

    # ── ③ 指向的分组真实存在且为模板组 ─────────────────────────────
    if py_templates:
        hit = _group_by_uuid(py_xml, py_templates)
        if hit is None:
            print(f"FAIL: EntryTemplatesGroup 指向的 UUID {py_templates!r} 在库内不存在")
            ok = False
        else:
            name, entry_count = hit
            print(f"命中分组：name={name!r} 直接条目数={entry_count}")
            if name != args.expect_group_name:
                print(f"FAIL: 分组名 {name!r} != 期望 {args.expect_group_name!r}")
                ok = False
            if entry_count < args.expect_entry_count:
                print(f"FAIL: 模板组条目数 {entry_count} < 期望 {args.expect_entry_count}")
                ok = False

    if ok:
        print("✓ 对拍通过：官方 CLI 与 pykeepass 双实现读数一致，模板组成立")
        return 0
    print("✗ 对拍失败")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
