#!/usr/bin/env python3
"""OWASP Dependency-Check 报告的 CVSS 阈值**硬断言**（fail-closed）。

为什么需要本脚本（ISSUE-P3-32 实测发现，核实时间点 2026-09-10）：
    `.github/dependency-check.init.gradle.kts` 配置的 `failBuildOnCVSS = 7.0f` 在
    `dependencyCheckAggregate` 任务上**不会**让构建失败。证据链：
      1. GitHub Actions 运行 34335443660（`dependency-scan`，conclusion=success）的日志中
         同时出现六个工程各自的
         "One or more dependencies were identified with known vulnerabilities in <project>:"
         与 "BUILD SUCCESSFUL in 37m"；核实方式：`gh run view 34335443660 --log`；
      2. 同一次运行归档的报告 JSON（artifact `dependency-check-report`，engine 13.0.0）
         内含 188 条漏洞实例，其中 **138 条 CVSS ≥ 7.0**（51 条 ≥ 9.0）；
         核实方式：下载 artifact 后以脚本统计 `dependencies[].vulnerabilities[]`。
    即「文档声称真实阻断、实际静默放行」——本脚本把该闸门补成真实阻断。

语义（与 `failBuildOnCVSS = 7.0` 对齐，且对缺分数者更严）：
    - 有 CVSS 分数           → 分数 ≥ 7.0 即阻断；
    - 无任何 CVSS 分数但有严重度 → severity 为 CRITICAL/HIGH 即阻断（fail-closed）；
    - 报告缺失 / 不可解析 / 结构非法 → 直接失败（**无报告 = 无结论 ≠ 通过**）。

豁免通道：
    本脚本只读 dependency-check 产出的报告。命中 suppression 白名单的条目已在**报告生成
    阶段**被插件剔除，故不会计入本脚本统计。因此「把经人工核实的误报写入
    `.github/owasp-dependency-suppressions.xml` 并按维护纪律登记依据」是本闸门**唯一**的
    豁免通道——严禁通过调低阈值、删除本步骤或让脚本静默放行来变绿。

用法：
    python3 .github/check_dependency_cvss.py <report.json> [<report.json> ...]
退出码：0 = 无达阈条目；1 = 存在达阈条目或缺报告/结构非法；2 = 用法错误。
"""

import glob
import json
import os
import sys

THRESHOLD = 7.0
BLOCKING_SEVERITIES = {"CRITICAL", "HIGH"}


def _cvss_score(vulnerability):
    """取 CVSS 分数；cvssv3 优先，其次 cvssv2；都没有则返回 None。"""
    for key, field in (("cvssv3", "baseScore"), ("cvssv2", "score")):
        block = vulnerability.get(key)
        if isinstance(block, dict):
            value = block.get(field)
            if isinstance(value, (int, float)):
                return float(value)
    return None


def _package_id(dependency):
    packages = dependency.get("packages") or []
    if packages and isinstance(packages[0], dict) and packages[0].get("id"):
        return str(packages[0]["id"])
    return str(dependency.get("fileName") or "?")


def main(argv):
    if len(argv) < 2:
        print("用法：check_dependency_cvss.py <dependency-check-report.json> [...]", file=sys.stderr)
        return 2

    paths = []
    for pattern in argv[1:]:
        matched = sorted(glob.glob(pattern))
        if matched:
            paths.extend(matched)
        else:
            paths.append(pattern)

    findings = []  # (score, cve, package, severity)
    scanned = 0
    for path in paths:
        if not os.path.isfile(path):
            print(f"[FATAL] 报告不存在：{path}", file=sys.stderr)
            print("        无报告 = 无供应链结论 ≠ 通过，按 fail-closed 失败。", file=sys.stderr)
            return 1
        try:
            with open(path, encoding="utf-8") as handle:
                report = json.load(handle)
        except (OSError, ValueError) as exc:
            print(f"[FATAL] 报告不可解析：{path}（{exc}）", file=sys.stderr)
            return 1
        dependencies = report.get("dependencies")
        if dependencies is None:
            print(f"[FATAL] 报告缺少 dependencies 字段，结构非法：{path}", file=sys.stderr)
            return 1
        for dependency in dependencies:
            package_id = _package_id(dependency)
            for vulnerability in dependency.get("vulnerabilities") or []:
                scanned += 1
                score = _cvss_score(vulnerability)
                severity = str(vulnerability.get("severity") or "").upper()
                if score is not None:
                    blocking = score >= THRESHOLD
                else:
                    # 缺 CVSS 分数时按严重度兜底，宁可误红不可放过
                    blocking = severity in BLOCKING_SEVERITIES
                if blocking:
                    findings.append(
                        (score if score is not None else -1.0, severity, str(vulnerability.get("name") or "?"), package_id)
                    )

    unique = {(item[2], item[3]) for item in findings}
    print(f"已扫描报告 {len(paths)} 份；漏洞实例 {scanned} 条；达阈（CVSS ≥ {THRESHOLD}）实例 {len(findings)} 条，"
          f"去重后（CVE × 构件）{len(unique)} 条。")

    if not findings:
        print("CVSS 闸门通过：无未豁免的 HIGH/CRITICAL 依赖漏洞。")
        return 0

    print()
    print(f"::error::CVSS 闸门未通过：{len(unique)} 个未豁免的（CVE × 构件）组合达到 CVSS ≥ {THRESHOLD}。")
    print("处置方式只有两种：修依赖（升级/替换），或把**经人工核实**的误报写入")
    print(".github/owasp-dependency-suppressions.xml 并按维护纪律登记核实依据；不得回调阈值。")
    print()
    # 按构件聚合，便于逐族处置；同一 (构件, CVE) 因 aar 与其内 classes.jar 重复出现，需去重
    by_package = {}
    for score, severity, cve, package_id in findings:
        by_package.setdefault(package_id, {})[(cve, score)] = severity
    for package_id in sorted(by_package, key=lambda key: -len(by_package[key])):
        entries = sorted(by_package[package_id].items(), key=lambda item: -item[0][1])
        print(f"  {package_id}  （{len(entries)} 条）")
        for (cve, score), severity in entries:
            shown = "无 CVSS 分数" if score < 0 else f"CVSS {score:.1f}"
            print(f"      - {cve}  [{shown} / {severity}]")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
