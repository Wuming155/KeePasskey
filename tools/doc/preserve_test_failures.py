#!/usr/bin/env python3
"""全量单测「首轮红」失败证据留存（`ISSUE-P3-489` AC①）。

## 为什么需要本脚本

`gate_readings.py` 堵的是「闸门存在 ≠ 闸门被执行」（`ISSUE-P3-305`）；其**下一层**缺口是
「闸门红了 ≠ 红在哪可查」——§445.6 实测：首轮 `gradlew test --rerun-tasks` 报 `BUILD FAILED`，
但截取的末 8 行输出不含肇事用例名，且 `*/build/test-results/**/*.xml` 随即被次轮
`--rerun-tasks` **全量覆盖**，肇事者永久不可考，只能记为「未定位偶发」。

本脚本把「非零退出后、重跑覆盖前」的肇事用例清单（类名 + 用例名 + 失败信息摘要）
**打印并落盘**到 `build/failure-evidence/<UTC 时间戳>.txt`，供后续定位。

## 纪律（批次结案口径）

全量 `test` 非零退出时，**先**运行本脚本落盘留痕，**再**决定重跑；`--rerun-tasks` 会覆盖
`test-results`，覆盖之后任何人无法再复原首轮肇事者。`build/` 已被 `.gitignore` 忽略，
证据为本地留存（`gradlew clean` 会一并清除，故落盘后应立即定位）。

## 用法与退出码

    python tools/doc/preserve_test_failures.py            # 扫描 + 落盘
    python tools/doc/preserve_test_failures.py --selftest # 口径反校（内嵌正/反样本）

- `0` = 扫描到的 JVM 单测 XML 中**无**失败 / 错误（无需留痕）；
- `1` = 存在失败 / 错误（已打印并落盘留痕）；
- `2` = 未找到任何 JVM 单测 XML（**不得**当绿：无法判别「没红」还是「没跑」）。

判据与 `count_test_results.py` **共用**（同一 `test<Variant>UnitTest` 目录口径），
以免与本仓「唯一尺子」漂移。
"""
import sys
import tempfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import count_test_results as ctr  # noqa: E402  （复用同一 XML 发现口径，避免漂移）

EVIDENCE_DIR = ROOT / "build" / "failure-evidence"
MODULES = ("app", "core", "crypto", "database", "sync")


def discover(module_root: Path) -> list:
    """目录名匹配 `test<Variant>UnitTest` 的 JVM 单测 XML（与唯一尺子同口径）。"""
    return [
        p for p in ctr.walk(str(module_root))
        if ctr.UNIT_TEST_DIR_RE.match(Path(p).parent.name)
    ]


def collect_failures(paths) -> list:
    """返回按 (classname, name) 排序的 `(类名, 用例名, kind, 摘要)`；kind ∈ {failure, error}。"""
    found = []
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        if root.tag != "testsuite":
            continue
        for case in root.iter("testcase"):
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                message = (node.get("message") or "").strip().replace("\n", " ")
                if len(message) > 200:
                    message = message[:200] + "…"
                found.append((
                    case.get("classname", "?"),
                    case.get("name", "?"),
                    kind,
                    message,
                ))
    found.sort()
    return found


def render(found, stamp: str) -> str:
    lines = [
        f"# 单测失败证据快照（ISSUE-P3-489） {stamp}",
        f"# 采集点：{ROOT.as_posix()}",
        f"failures={len(found)}",
    ]
    for cls, name, kind, msg in found:
        lines.append(f"[{kind}] {cls}#{name}  | {msg}")
    return "\n".join(lines) + "\n"


# —— --selftest 内嵌样本（已知值反校；正/反两向）——
SAMPLE_DIRTY = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.FooTest" tests="3" failures="1" errors="1" skipped="0">
  <testcase classname="com.example.FooTest" name="passes" time="0.01"/>
  <testcase classname="com.example.FooTest" name="boom" time="0.02">
    <failure message="expected:&lt;1&gt; but was:&lt;2&gt;">stack</failure>
  </testcase>
  <testcase classname="com.example.BarTest" name="kaboom" time="0.03">
    <error message="IllegalStateException: bad">trace</error>
  </testcase>
</testsuite>
"""

SAMPLE_CLEAN = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.CleanTest" tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="com.example.CleanTest" name="ok" time="0.01"/>
</testsuite>
"""


def _write_sample(base: Path, dir_name: str, xml_text: str) -> None:
    d = base / "app" / "build" / "test-results" / dir_name
    d.mkdir(parents=True, exist_ok=True)
    (d / "TEST-com.example.SampleTest.xml").write_text(xml_text, encoding="utf-8")


def selftest() -> int:
    checks = []
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        _write_sample(base, "testDebugUnitTest", SAMPLE_DIRTY)
        paths = discover(base / "app")
        found = collect_failures(paths)
        # 反面样本：1 failure + 1 error，字段与摘要逐项核对
        checks.append(("dirty 命中 2 条", len(found) == 2))
        checks.append(("排序后首条为 BarTest#kaboom(error)",
                       found[0][0] == "com.example.BarTest" and found[0][2] == "error"))
        checks.append(("次条为 FooTest#boom(failure)",
                       found[1][1] == "boom" and found[1][2] == "failure"))
        checks.append(("失败信息实体解码正确", "expected:<1> but was:<2>" in found[1][3]))
        # 非 JVM 目录（截图测试）必须被排除
        _write_sample(base, "updateDebugScreenshotTest", SAMPLE_DIRTY)
        checks.append(("截图测试目录被排除", len(discover(base / "app")) == 1))
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        _write_sample(base, "testDebugUnitTest", SAMPLE_CLEAN)
        checks.append(("clean 样本零命中", collect_failures(discover(base / "app")) == []))
    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"  [{'ok' if ok else 'FAIL'}] {name}")
    print("preserve_test_failures --selftest: " + ("FAIL" if failed else "PASS"))
    return 1 if failed else 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    modules = [m for m in MODULES if (ROOT / m).is_dir()]
    if not modules:
        print("未找到任何模块目录（须在仓库根运行）", file=sys.stderr)
        return 2
    paths = []
    for m in modules:
        paths.extend(discover(ROOT / m))
    if not paths:
        print("::warning::未找到任何 JVM 单测 XML（无法判别「没红」还是「没跑」）", file=sys.stderr)
        return 2
    found = collect_failures(paths)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    text = render(found, stamp)
    print(text, end="")
    if not found:
        print(f"无失败用例（已扫描 {len(paths)} 份 XML，无需留痕）")
        return 0
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    out = EVIDENCE_DIR / f"unit-test-failures-{stamp}.txt"
    out.write_text(text, encoding="utf-8")
    print(f"已落盘留痕：{out.relative_to(ROOT).as_posix()}"
          "（重跑覆盖 test-results 前请据此定位，clean 会清除本目录）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
