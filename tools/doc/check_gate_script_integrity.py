#!/usr/bin/env python3
"""门禁脚本集合的 SHA-256 完整性基线校验（`ISSUE-P2-574` 立规；批次 §489）。

## 为何存在

`ISSUE-P2-574`（数据流安全审查 2026-10-11 发现 2，verdict=adjusted）指出：CI 全部门禁的
**判定本体**——`hygiene-gate` 现列机检脚本、CVSS 硬断言 `.github/check_dependency_cvss.py`
（`dependency-scan.yml` 直接调用）、设备侧非空转断言 `tools/device/check_connected_device_results.py`、
通行密钥互操作对拍 `tools/passkey-interop/verify_interop.py`——**都是 PR 可改文件**，且此前
无任何自我完整性护栏。三个 workflow 均仅 `schedule` + `workflow_dispatch`（`ISSUE-P3-316`
有意移除 push/PR 触发），**合并前零 CI 自动检测**：一个「看起来无害的脚本维护」PR 单行削弱
任一脚本判据（例：`THRESHOLD = 7.0` → `10.0`）被人工合并后，自下一次定时巡检起即以被削判据
**持续假绿**——`fail-closed` 只拦非 0 退出码，不校验判据语义。

本脚本把「门禁脚本集合」与一份独立受审清单
[`tools/doc/gate_scripts.sha256`](gate_scripts.sha256) 对拍：清单是脚本字节的 SHA-256 钉定值，
**任一脚本被改动而其清单未同步更新即红**。于是「削弱判据」不再是无结构信号的单行 diff——
它必须**同时**改动脚本与清单（两处敏感面），在评审中获得结构性可见性。

**行尾归一（必须）**：哈希按**行尾归一后**的字节（CRLF / CR → LF）计算，而非原始字节。缘由：
本仓 `.gitattributes` 约定 `* text=auto eol=lf`，CI 与全新检出恒为 LF；但个别脚本在开发机工作区可能
是 CRLF（`text=auto` 下 git 视为未改动、不重写工作区）——按原始字节哈希会让同一脚本在本地与 CI
算出**不同**值，造成本机假绿 / CI 假红（§489 实测三个脚本 `count_line_tiers.py` /
`check_launch_language_seed.py` / `verify_interop.py` 工作区为 CRLF、索引为 LF）。归一后两种检出一致。

## 判据（四条，任一命中即退出码 1）

1. **清单存在性**：`tools/doc/gate_scripts.sha256` 必须存在且可解析（缺失 / 行格式非法即红）；
2. **覆盖集一致**：清单登记的脚本集合必须**逐条等于**「期望门禁脚本集合」
   （期望集 = `build.yml` 的 `hygiene-gate` 段落现列 `python3 tools/...` 命令
   ∪ 三个跨 workflow 脚本 `EXTRA_SCRIPTS`）——缺项（脚本未登记）与多项（清单登记了非期望脚本）
   均判红，防「删一条机检命令」与「清单一并缩水」静默通过；
3. **脚本存在性**：期望集内每个脚本必须存在于工作区（缺失即红）；
4. **哈希一致**：每个脚本的实际 SHA-256 必须等于清单登记值（失配即红）。

> 期望集**从 `build.yml` 现跑解析**（与 `tools/doc/gate_readings.py` 同源口径），不另抄一份；
> 故「往 hygiene-gate 加一条机检」会因清单缺项而红，**迫使**同批更新清单并在批次文档登记变更。

## 能力边界（**不得**据其绿推定「门禁不可篡改」）

清单与校验脚本**自身**同样处于 PR 可改面：一个**同时**修改脚本与其清单的 PR，除「两处敏感面
diff 在评审中可见」这一结构性信号外，**没有**仓内机制能独立发现——仓库内不存在（也不可能存在）
独立信任根。该盲区已如实登记 `docs/architecture/已知工程限界.md` §42，**不得**写为已证。

## 用法与退出码

    python tools/doc/check_gate_script_integrity.py [--root 目录]
    python tools/doc/check_gate_script_integrity.py --selftest   # 判据反校（内嵌正 / 反样本）

退出码（fail-closed）：`0` = 四条判据全过；`1` = 任一判据命中（清单缺失 / 格式非法 / 覆盖集
不一致 / 脚本缺失 / 哈希失配）；`2` = 用法错误。
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import re
import sys
import tempfile

# 仓库根（tools/doc/check_gate_script_integrity.py → parents[2]）
DEFAULT_ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST_REL = "tools/doc/gate_scripts.sha256"
WORKFLOW_REL = ".github/workflows/build.yml"
SELF_REL = "tools/doc/check_gate_script_integrity.py"

# 跨 workflow、**不在** build.yml hygiene-gate 段落内的门禁脚本（`ISSUE-P2-574` 明确点名）
EXTRA_SCRIPTS = (
    ".github/check_dependency_cvss.py",
    "tools/device/check_connected_device_results.py",
    "tools/passkey-interop/verify_interop.py",
)

# 与 gate_readings.py 同源：hygiene-gate 段落内形如 `python3 tools/xxx.py` 的机检命令
COMMAND = re.compile(r"^\s*python3\s+(tools/\S+\.py)\s*$", re.M)
JOB_START = re.compile(r"^  hygiene-gate:\s*$", re.M)
NEXT_JOB = re.compile(r"^  \S", re.M)
# sha256sum 行长：64 位十六进制 + 空白 + 相对路径
HASH_RE = re.compile(r"^[0-9a-f]{64}$")


def hygiene_commands(text: str) -> list[str]:
    """解析 `hygiene-gate` job 内的机检脚本相对路径（与 gate_readings.py 同口径）。"""
    start = JOB_START.search(text)
    if not start:
        return []
    tail = text[start.end():]
    nxt = NEXT_JOB.search(tail)
    section = tail[: nxt.start()] if nxt else tail
    return [rel.replace("\\", "/") for rel in COMMAND.findall(section)]


def expected_scripts(root: pathlib.Path) -> set[str]:
    """期望门禁脚本集合 = hygiene-gate 现列机检 ∪ EXTRA_SCRIPTS。解析不到机检命令则空集。"""
    workflow = root / WORKFLOW_REL
    if not workflow.is_file():
        return set()
    commands = hygiene_commands(workflow.read_text(encoding="utf-8"))
    return set(commands) | set(EXTRA_SCRIPTS)


def sha256_file(path: pathlib.Path) -> str:
    """按**行尾归一后**的字节计算 SHA-256（CRLF / CR → LF）。

    归一而非按原始字节，是为了让本地（工作区可能是 CRLF）与 CI（恒 LF）对同一脚本算出一致哈希
    ——见模块 docstring「行尾归一（必须）」。`.gitattributes` 的 `text=auto eol=lf` 只保证**入库 /
    检出**为 LF，不保证任何时刻的工作区字节都是 LF。
    """
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        data = handle.read()
    digest.update(data.replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
    return digest.hexdigest()


def load_manifest(path: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    """读取基线清单 ⇒ (路径→哈希, 违规清单)。文件缺失或行格式非法均计入违规（fail-closed）。"""
    if not path.is_file():
        return {}, ["[清单缺失] 找不到门禁脚本基线清单：%s" % path]
    entries: dict[str, str] = {}
    violations: list[str] = []
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        if len(parts) != 2 or not HASH_RE.match(parts[0]):
            violations.append("[清单格式非法] 第 %d 行：%r" % (lineno, raw))
            continue
        digest, rel = parts
        entries[rel.strip().replace("\\", "/")] = digest.lower()
    return entries, violations


def verify(
    entries: dict[str, str],
    expected: set[str],
    root: pathlib.Path,
    hasher=sha256_file,
) -> tuple[list[str], dict[str, int]]:
    """纯判据函数（供 `--selftest` 直接反校）。返回 (违规清单, 读数计数)。"""
    violations: list[str] = []
    missing = sorted(expected - set(entries))
    extra = sorted(set(entries) - expected)
    for rel in missing:
        violations.append("[清单缺失条目] 期望覆盖但清单未登记：%s" % rel)
    for rel in extra:
        violations.append("[清单多余条目] 清单登记了非期望脚本：%s" % rel)
    mismatched = 0
    absent = 0
    for rel in sorted(expected):
        target = root / rel
        if not target.is_file():
            absent += 1
            violations.append("[脚本缺失] %s" % rel)
            continue
        actual = hasher(target)
        if rel in entries and entries[rel] != actual:
            mismatched += 1
            violations.append("[哈希失配] %s 实际=%s 清单=%s" % (rel, actual, entries[rel]))
    stats = {"manifest": len(entries), "expected": len(expected), "mismatch": mismatched, "absent": absent}
    return violations, stats


def selftest() -> int:
    """内嵌正 / 反样本反校四条判据（不依赖仓内真实文件）。"""
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / "tools/doc").mkdir(parents=True)
        (root / ".github").mkdir(parents=True)
        good_rel = "tools/doc/demo_gate.py"
        (root / good_rel).write_text("# demo gate\n", encoding="utf-8")
        good_sha = sha256_file(root / good_rel)
        expected = {good_rel}

        # 正样本：清单 ↔ 脚本一致 ⇒ 放行
        ok, stats = verify({good_rel: good_sha}, expected, root)
        if ok or stats["mismatch"] or stats["absent"]:
            failures.append("正样本应放行，却命中：%s" % ok)

        # 反样本 1：哈希漂移 ⇒ 抓红
        drift, _ = verify({good_rel: "0" * 64}, expected, root)
        if not any("[哈希失配]" in v for v in drift):
            failures.append("哈希漂移未被抓红：%s" % drift)

        # 反样本 2：脚本缺失 ⇒ 抓红
        gone, stats2 = verify({good_rel: good_sha}, {"tools/doc/not_here.py"}, root)
        if not any("[脚本缺失]" in v for v in gone) or stats2["absent"] != 1:
            failures.append("脚本缺失未被抓红：%s" % gone)

        # 反样本 3：清单缺项（删除一条机检命令后清单未同步）⇒ 抓红
        miss, _ = verify({}, expected, root)
        if not any("[清单缺失条目]" in v for v in miss):
            failures.append("清单缺项未被抓红：%s" % miss)

        # 反样本 4：清单多项（登记了非期望脚本）⇒ 抓红
        more, _ = verify({good_rel: good_sha, "tools/doc/ghost.py": "0" * 64}, expected, root)
        if not any("[清单多余条目]" in v for v in more):
            failures.append("清单多余项未被抓红：%s" % more)

        # 反样本 5：清单文件缺失 ⇒ 抓红（load_manifest 面）
        entries, mv = load_manifest(root / "tools/doc/nope.sha256")
        if entries or not mv:
            failures.append("清单文件缺失未被抓红：entries=%r violations=%r" % (entries, mv))

        # 反样本 6：清单行格式非法 ⇒ 抓红
        bad_manifest = root / "tools/doc/bad.sha256"
        bad_manifest.write_text("not-a-hash  %s\n" % good_rel, encoding="utf-8")
        _, bv = load_manifest(bad_manifest)
        if not any("[清单格式非法]" in v for v in bv):
            failures.append("清单行格式非法未被抓红：%s" % bv)

        # 正样本 2（行尾归一）：同一内容的 CRLF 与 LF 两种字节必须算出同一哈希
        # ——否则本地（工作区可能 CRLF）与 CI（恒 LF）会对同一脚本得到不同哈希，
        # 造成本机假绿 / CI 假红（§489 实测三个脚本即此形态）。
        crlf = root / "tools/doc/eol_crlf.py"
        lf = root / "tools/doc/eol_lf.py"
        crlf.write_bytes(b"a = 1\r\nb = 2\r\n")
        lf.write_bytes(b"a = 1\nb = 2\n")
        if sha256_file(crlf) != sha256_file(lf):
            failures.append("行尾归一失效：CRLF 与 LF 同内容算出不同哈希（本机假绿 / CI 假红）")

    if failures:
        print("[selftest] FAIL：判据与样本不符（闸门读数不可信）")
        for item in failures:
            print("  [selftest] " + item)
        return 1
    print("[selftest] PASS：正样本（含 CRLF/LF 同哈希的行尾归一）放行，哈希漂移 / 脚本缺失 / 清单缺项 / 清单多项 / 清单缺失 / 清单格式非法 六反样本均被抓红")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="门禁脚本 SHA-256 完整性基线校验（ISSUE-P2-574）")
    parser.add_argument("--root", default=str(DEFAULT_ROOT), help="仓库根目录（默认本脚本所在仓库）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    entries, violations = load_manifest(root / MANIFEST_REL)
    expected = expected_scripts(root)
    if not expected:
        violations.append(
            "[期望集为空] 未能从 %s 的 hygiene-gate 段落解析到机检命令（不得据此当绿）" % WORKFLOW_REL
        )
    elif SELF_REL not in expected:
        violations.append(
            "[期望集异常] %s 未出现在 hygiene-gate 现列机检中——本校验未被接为门禁首步" % SELF_REL
        )
    else:
        sub_violations, stats = verify(entries, expected, root)
        violations.extend(sub_violations)

    stats_entries = len(entries)
    print(
        "[check_gate_script_integrity] 清单 %d 条 / 期望 %d 条；命中 %d 处"
        % (stats_entries, len(expected), len(violations))
    )
    for item in violations:
        print("  HIT " + item)
    if violations:
        print(
            "[check_gate_script_integrity] FAIL：门禁脚本与其 SHA-256 基线清单不一致——"
            "削弱判据的改动必须**同批**更新 %s 并在批次文档登记；不得静默漂移" % MANIFEST_REL
        )
        return 1
    print(
        "[check_gate_script_integrity] PASS：门禁脚本集合 %d 条与 SHA-256 基线清单逐条一致"
        % stats_entries
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
