#!/usr/bin/env python3
"""ISSUE-P2-641 两阶段**真实进程重建**验收（旧 token 重放必须被拒）。

## 为什么需要宿主脚本

`CredentialPendingIntents` 的请求登记表是**进程内** map：进程重建后，旧 Intent 里的
requestId 语法上仍然合法，但已查不到对应上下文。这条「旧 token 不得重放到新进程」
只能由外部杀进程复现——单次 instrumentation 无法「自杀后继续断言」。

## 步骤

1. `prepare` 段：建库、登记票据、断言**同进程内可用**，再把非秘密的
   （PID / requestId / 库路径）写进闸门文件；
2. 宿主读闸门、记录旧 PID，`am force-stop` 后核实 PID 已消失；
3. `verify` 段（新进程）：
   - requestId 仍是合法 URI 形态（证明拒绝原因不是解析失败）；
   - 登记表查不到旧 requestId；
   - 旧 token `bind` 必须返回 false；
   - 反向对照：同进程内**新登记**的 token 必须可用。

## 用法

    python tools/device/run_p2_641_token_replay.py [--device emulator-5554]

前置：`:app:installDebug` 与 `:app:installDebugAndroidTest` 已执行（本脚本不跑 Gradle）。

## 边界

- 只验证「登记表随进程消失 ⇒ 旧 token 拒绝」这一条；系统选择器点选与生物验证
  不在本脚本内（由 `CmSessionMatrixDeviceTest` 与门控用例分别覆盖）。
- 闸门文件只含非秘密标识（PID / 随机 requestId / 库路径），不含任何凭据。
"""
from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parents[2]
ADB = pathlib.Path("D:/Android/SDK/platform-tools/adb.exe")
ARTIFACTS = REPO / ".ci-artifacts" / "remediation-20261021" / "p2-641-token-replay"
TEST_CLASS = "com.keepasskey.app.passkey.CredentialSessionGateDeviceTest"
APP_ID = "com.keepasskey.debug"
TEST_APK_ID = "com.keepasskey.debug.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
MARKER_PATH = "files/p2-641-replay/GATE_REACHED"


def run(cmd, check=False, timeout=300):
    return subprocess.run(cmd, check=check, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def instrument(device: str, method: str, phase: str) -> subprocess.CompletedProcess:
    return run([str(ADB), "-s", device, "shell", "am", "instrument", "-w", "-r",
                "-e", "class", f"{TEST_CLASS}#{method}",
                "-e", "p2641Phase", phase,
                f"{TEST_APK_ID}/{RUNNER}"], check=False)


def write_evidence(name: str, output: str, passed: bool) -> pathlib.Path:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    stem = ARTIFACTS / name
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
    suite = ET.Element("testsuite", {
        "name": TEST_CLASS, "tests": "1",
        "failures": "0" if passed else "1", "errors": "0", "skipped": "0", "time": "0",
    })
    ET.SubElement(suite, "testcase", {"classname": TEST_CLASS, "name": name})
    ET.indent(suite, space="  ")
    path = stem.with_suffix(".xml")
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
    return path


def passed(output: str) -> bool:
    return "OK (" in output and "FAILURES!!!" not in output


def read_marker(device: str) -> str | None:
    result = run([str(ADB), "-s", device, "shell", "run-as", APP_ID, "cat", MARKER_PATH])
    if result.returncode != 0:
        return None
    text = (result.stdout or "").strip()
    return text or None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--marker-timeout", type=int, default=180)
    args = parser.parse_args()
    run_id = f"p2641-{uuid.uuid4().hex[:8]}"

    run([str(ADB), "-s", args.device, "shell", "am", "force-stop", APP_ID])
    run([str(ADB), "-s", args.device, "shell", "run-as", APP_ID, "rm", "-rf", "files/p2-641-replay"])

    print(f"[1/4] prepare 段（run_id={run_id}）", flush=True)
    prepare = instrument(args.device, "prepareRegistersOldToken", "prepare")
    prepare_out = (prepare.stdout or "") + (prepare.stderr or "")
    if not passed(prepare_out):
        path = write_evidence(f"{run_id}-prepare", prepare_out, False)
        print(f"ERROR: prepare 段失败；证据={path}\n{prepare_out[-3000:]}", file=sys.stderr)
        return 1

    deadline = time.monotonic() + args.marker_timeout
    marker = None
    while time.monotonic() < deadline:
        marker = read_marker(args.device)
        if marker:
            break
        time.sleep(0.5)
    if not marker:
        print("ERROR: prepare 段未写下闸门标记", file=sys.stderr)
        return 1
    old_pid = marker.splitlines()[0].strip()
    print(f"[2/4] 旧 token 已登记，旧 PID={old_pid}", flush=True)

    print("[3/4] am force-stop 终止应用进程", flush=True)
    run([str(ADB), "-s", args.device, "shell", "am", "force-stop", APP_ID])
    time.sleep(2)
    alive = (run([str(ADB), "-s", args.device, "shell", "pidof", APP_ID]).stdout or "").strip()
    if alive == old_pid:
        print("ERROR: force-stop 后旧 PID 仍存活，验收不成立", file=sys.stderr)
        return 1

    print("[4/4] verify 段（新进程）", flush=True)
    verify = instrument(args.device, "verifyOldTokenRejectedInNewProcess", "verify")
    output = (verify.stdout or "") + (verify.stderr or "")
    ok = passed(output)
    path = write_evidence(f"{run_id}-verify", output, ok)
    if not ok:
        print(f"ERROR: 新进程核对失败；证据={path}\n{output[-3000:]}", file=sys.stderr)
        return 1
    print(f"OK: 进程重建后旧 token 重放被拒；证据={path}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
