"""按官方来源刷新内置 Public Suffix List，并机检装载前提。

来源只允许 https://publicsuffix.org/list/public_suffix_list.dat（官方文件头亦如此要求）。
本脚本只服务维护者与发布流程，**不**为运行时提供在线更新。用法、产物、退出码与过期边界
见同目录 README.md。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCE = ROOT / "app" / "src" / "main" / "resources" / "publicsuffix" / "public_suffix_list.dat"

OFFICIAL_URL = "https://publicsuffix.org/list/public_suffix_list.dat"
OFFICIAL_HOST = "publicsuffix.org"

VERSION_LINE = re.compile(r"^// VERSION: \d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_UTC$")
COMMIT_LINE = re.compile(r"^// COMMIT: [0-9a-f]{40}$")

MIN_RULE_LINES = 9_000


class InvalidList(Exception):
    """候选内容不满足装载前提（调用方按非零退出，不写盘）。"""


def parse_list(text: str, origin: str) -> dict:
    """校验一份 PSL 文本并返回其版本身份；任一前提不满足即抛 [InvalidList]。"""
    if "\r" in text:
        raise InvalidList(f"{origin}: 含 CR；装载实现只接受纯 LF")
    lines = text.split("\n")
    if not lines or not lines[0].startswith("// "):
        raise InvalidList(f"{origin}: 首行不是注释（疑似截断或错误页）")
    if not any("Mozilla Public" in line for line in lines):
        raise InvalidList(f"{origin}: 缺少 MPL-2.0 许可声明")
    if not any(OFFICIAL_URL in line for line in lines):
        raise InvalidList(f"{origin}: 缺少官方拉取地址说明")

    versions = [line for line in lines if line.startswith("// VERSION: ")]
    commits = [line for line in lines if line.startswith("// COMMIT: ")]
    if len(versions) != 1 or not VERSION_LINE.match(versions[0]):
        raise InvalidList(f"{origin}: VERSION 行缺失或格式不符")
    if len(commits) != 1 or not COMMIT_LINE.match(commits[0]):
        raise InvalidList(f"{origin}: COMMIT 行缺失或格式不符")
    for marker in ("// ===BEGIN ICANN DOMAINS===", "// ===BEGIN PRIVATE DOMAINS==="):
        if marker not in text:
            raise InvalidList(f"{origin}: 缺少官方分区标记 {marker}")

    rules = [line for line in lines if line and not line.startswith("//")]
    if len(rules) < MIN_RULE_LINES:
        raise InvalidList(f"{origin}: 规则行仅 {len(rules)} 条（下限 {MIN_RULE_LINES}），疑似截断")
    for line in rules:
        if line != line.lower():
            raise InvalidList(f"{origin}: 规则行未小写：{line!r}")
        if line != line.strip():
            raise InvalidList(f"{origin}: 规则行首尾有空白：{line!r}")
        if any(ch.isspace() for ch in line):
            raise InvalidList(f"{origin}: 规则行内含空白：{line!r}")
        rule = line[1:] if line.startswith("!") else line
        rule = rule[2:] if rule.startswith("*.") else rule
        if not rule or rule.startswith(".") or rule.endswith(".") or ".." in rule:
            raise InvalidList(f"{origin}: 规则标签结构非法：{line!r}")
        if "*" in rule or "!" in rule:
            raise InvalidList(f"{origin}: 通配 / 例外前缀只允许出现在行首：{line!r}")

    return {
        "version": versions[0][len("// VERSION: "):],
        "commit": commits[0][len("// COMMIT: "):],
        "rules": rules,
    }


def read_bytes(path: Path) -> bytes:
    try:
        return path.read_bytes()
    except OSError as exc:
        raise InvalidList(f"无法读取 {path}: {exc}") from exc


def decode(data: bytes, origin: str) -> str:
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise InvalidList(f"{origin}: 不是合法 UTF-8（{exc}）") from exc


def fetch_official(timeout: float) -> bytes:
    request = urllib.request.Request(
        OFFICIAL_URL,
        headers={"User-Agent": "KeePasskey-PSL-Refresh/1.0"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            final = urllib.parse.urlsplit(response.geturl())
            if final.scheme != "https" or final.hostname != OFFICIAL_HOST:
                raise InvalidList(f"官方地址被重定向到非官方来源：{response.geturl()}")
            if response.status != 200:
                raise InvalidList(f"官方地址返回 HTTP {response.status}")
            return response.read()
    except InvalidList:
        raise
    except Exception as exc:  # 网络层失败统一按不可用处理，不静默通过
        raise InvalidList(f"抓取官方列表失败：{exc}") from exc


def write_atomic(target: Path, data: bytes) -> None:
    """同目录临时文件 + fsync + 原子替换；失败时清理临时件并保留原文件。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=target.name + ".", suffix=".tmp", dir=target.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_name, target)
    except BaseException:
        Path(tmp_name).unlink(missing_ok=True)
        raise


def report(prefix: str, info: dict) -> None:
    print(f"PSL_{prefix}VERSION={info['version']}")
    print(f"PSL_{prefix}COMMIT={info['commit']}")
    print(f"PSL_{prefix}RULES={len(info['rules'])}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="校验或按官方来源刷新内置 Public Suffix List")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--verify", action="store_true", help="只校验随仓资源（默认）")
    mode.add_argument("--check", action="store_true", help="抓取官方列表并与随仓版本比较，不写盘")
    mode.add_argument("--apply", action="store_true", help="校验通过后原子替换随仓资源")
    parser.add_argument("--source", type=Path, help="用本地文件替代官方抓取（仅 --check / --apply）")
    parser.add_argument("--timeout", type=float, default=60.0, help="抓取超时秒数（默认 60）")
    args = parser.parse_args(argv)

    if args.source is not None and not (args.check or args.apply):
        parser.error("--source 只能与 --check 或 --apply 同用")

    try:
        current_bytes = read_bytes(RESOURCE)
        current = parse_list(decode(current_bytes, str(RESOURCE)), str(RESOURCE))
    except InvalidList as exc:
        print(f"PSL_ERROR={exc}", file=sys.stderr)
        return 1

    print(f"PSL_RESOURCE={RESOURCE.relative_to(ROOT).as_posix()}")
    report("", current)
    if not (args.check or args.apply):
        return 0

    try:
        candidate_bytes = read_bytes(args.source) if args.source else fetch_official(args.timeout)
        candidate = parse_list(decode(candidate_bytes, "候选列表"), "候选列表")
    except InvalidList as exc:
        print(f"PSL_ERROR={exc}", file=sys.stderr)
        return 1

    report("CANDIDATE_", candidate)
    changed = candidate_bytes != current_bytes
    print(f"PSL_UPDATE_AVAILABLE={int(changed)}")
    if args.check:
        added = set(candidate["rules"]) - set(current["rules"])
        removed = set(current["rules"]) - set(candidate["rules"])
        print(f"PSL_RULES_ADDED={len(added)}")
        print(f"PSL_RULES_REMOVED={len(removed)}")
        if candidate["version"] < current["version"]:
            print("PSL_CANDIDATE_OLDER=1")
        return 0

    if not changed:
        print("PSL_WRITTEN=0")
        return 0
    try:
        write_atomic(RESOURCE, candidate_bytes)
        written = parse_list(decode(read_bytes(RESOURCE), str(RESOURCE)), str(RESOURCE))
    except (InvalidList, OSError) as exc:
        print(f"PSL_ERROR={exc}", file=sys.stderr)
        return 1
    if written["commit"] != candidate["commit"]:
        print("PSL_ERROR=写入后复读的 COMMIT 与候选不一致", file=sys.stderr)
        return 1
    print("PSL_WRITTEN=1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
