#!/usr/bin/env python3
"""ISSUE-P2-636 两阶段**真实进程死亡**验收编排（同步缓存崩溃窗口）。

## 为什么需要宿主脚本

问题形态是「`.cache` 已换成新字节、`.version` 还是旧值（或缺失），进程随即消失」。
这种窗口只能由**外部**终止进程来复现——单次 instrumentation 无法「自杀后继续断言」。
本脚本只做四件事，全部围绕同一台设备、同一份应用数据：

1. `prepare` 段：建立一致基线，再写入新字节而**不**刷新 `.version`（等价于真实崩溃发生在
   rename 与写 version 之间），写 `GATE_REACHED` 后退出；
2. 轮询 `GATE_REACHED`（`run-as` 读私有目录），确认后记录旧 PID；
3. `adb shell am force-stop` 终止进程，并核实 PID 已消失；
4. `verify` 段：**新进程**核对——崩溃后 `hasLocalChanges` 必须为 true、`.cache` 可整份读回且
   逐字节等于崩溃前写入、无 `.tmp` 残留、缺失 `.version` 同样判脏、基线前移后负对照为 false。

只终止**自己拉起的**应用进程；不按镜像名批量杀进程。

## 用法

    python tools/device/run_p2_636_crash.py [--device emulator-5554]

前置：`:app:installDebug` 与 sync 模块的 androidTest APK 已安装（本脚本不跑 Gradle）。

## 边界

- 只覆盖「进程在窗口内消失」这一形态；跨进程并发写仍依赖各文件的原子替换与 SHA-256 校验
  （已登记的工程限界）。
- 不驱动真实远端：ETag / 回滚状态的一致性由 `SyncCacheMaintenance` 的既有 JVM 矩阵覆盖，
  本脚本核对的是崩溃后**本地判据**是否仍成立。
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
ARTIFACTS = REPO / ".ci-artifacts" / "remediation-20261021" / "p2-636"
TEST_CLASS = "com.keepasskey.sync.engine.SyncCacheCrashRecoveryDeviceTest"
APP_ID = "com.keepasskey.sync.test"
TEST_APK_ID = "com.keepasskey.sync.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
MARKER_PATH = "files/p2-636-crash/GATE_REACHED"


def run(cmd, check=False, timeout=300):
    return subprocess.run(cmd, check=check, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def instrument(device: str, method: str, phase: str, wait: bool) -> subprocess.CompletedProcess:
    args = [str(ADB), "-s", device, "shell", "am", "instrument"]
    if wait:
        # `-w` 必须紧跟 `instrument`，否则 adb 会把测试输出丢掉（verify 段判定不了成败）。
        args.append("-w")
    args += [
        "-e", "class", f"{TEST_CLASS}#{method}",
        "-e", "p2636Phase", phase,
        f"{TEST_APK_ID}/{RUNNER}",
    ]
    return run(args, check=False)


def write_evidence(name: str, output: str, exit_code: int) -> pathlib.Path:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    stem = ARTIFACTS / name
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
    passed = "OK (" in output and "FAILURES!!!" not in output
    suite = ET.Element("testsuite", {
        "name": TEST_CLASS,
        "tests": "1",
        "failures": "0" if passed else "1",
        "errors": "0",
        "skipped": "0",
        "time": "0",
    })
    ET.SubElement(suite, "testcase", {"classname": TEST_CLASS, "name": name})
    ET.indent(suite, space="  ")
    xml_path = stem.with_suffix(".xml")
    ET.ElementTree(suite).write(xml_path, encoding="utf-8", xml_declaration=True)
    return xml_path


def read_marker(device: str) -> str | None:
    result = run([str(ADB), "-s", device, "shell", "run-as", APP_ID, "cat", MARKER_PATH])
    if result.returncode != 0:
        return None
    text = (result.stdout or "").strip()
    return text or None


def process_pid(device: str) -> str:
    out = run([str(ADB), "-s", device, "shell", "pidof", APP_ID]).stdout or ""
    return out.strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--marker-timeout", type=int, default=180)
    args = parser.parse_args()
    run_id = f"p2636-{uuid.uuid4().hex[:8]}"

    # 干净起点：上一轮遗留的缓存目录会让「建立基线」读到旧现场。
    run([str(ADB), "-s", args.device, "shell", "am", "force-stop", APP_ID])
    run([str(ADB), "-s", args.device, "shell", "run-as", APP_ID, "rm", "-rf", "files/p2-636-crash"])

    print(f"[1/4] prepare 段启动（run_id={run_id}）", flush=True)
    # prepare 段写完闸门即退出（不阻塞），故可以同步等待；闸门文件用于确认它真的跑完。
    prepare = instrument(args.device, "prepareCrashWindow", "prepare", wait=True)
    prepare_output = (prepare.stdout or "") + (prepare.stderr or "")
    if "OK (" not in prepare_output or "FAILURES!!!" in prepare_output:
        xml_path = write_evidence(f"{run_id}-prepare", prepare_output, 1)
        print(f"ERROR: prepare 段失败；证据={xml_path}\n{prepare_output[-3000:]}", file=sys.stderr)
        return 1

    deadline = time.monotonic() + args.marker_timeout
    marker = None
    while time.monotonic() < deadline:
        marker = read_marker(args.device)
        if marker:
            break
        time.sleep(0.5)
    if not marker:
        print("ERROR: prepare 段未写下死亡门标记", file=sys.stderr)
        return 1
    old_pid = marker
    print(f"[2/4] 崩溃窗口已就位，旧 PID={old_pid}", flush=True)

    print("[3/4] am force-stop 终止应用进程", flush=True)
    run([str(ADB), "-s", args.device, "shell", "am", "force-stop", APP_ID])
    time.sleep(2)
    if process_pid(args.device) == old_pid:
        print("ERROR: force-stop 后旧 PID 仍存活，验收不成立", file=sys.stderr)
        return 1

    print("[4/4] verify 段（新进程）", flush=True)
    verify = instrument(args.device, "verifyNewProcessStillSeesLocalChanges", "verify", wait=True)
    output = (verify.stdout or "") + (verify.stderr or "")
    passed = "OK (" in output and "FAILURES!!!" not in output
    xml_path = write_evidence(f"{run_id}-verify", output, 0 if passed else 1)
    if not passed:
        print(f"ERROR: 新进程核对失败；证据={xml_path}\n{output[-3000:]}", file=sys.stderr)
        return 1
    print(f"OK: 进程死亡后缓存判据验收通过；证据={xml_path}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
