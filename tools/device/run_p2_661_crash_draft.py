#!/usr/bin/env python3
"""P2-661 两阶段**真实进程死亡**验收编排（加密崩溃草稿恢复）。

为什么需要宿主脚本：进程死亡必须由外部触发，单次 instrumentation 无法「自杀后继续断言」。
本脚本只做四件事，全部围绕**同一台设备、同一份应用数据**：

1. `prepare` 段：跑前置用例制造「内存脏树 + 已保全的加密草稿」，用例写 `GATE_REACHED` 后阻塞；
2. 轮询 `GATE_REACHED`（`run-as` 读私有目录），确认后记录旧 PID；
3. `adb shell am force-stop` 终止进程，并核实 PID 已消失、随后由新进程接手；
4. `verify` 段：新进程中核对正式文件逐字节未变、草稿可发现且唯一、错误凭据被拒、正确凭据预览
   可见本地修改、明确恢复后可重读、且只清理对应草稿。

只终止**自己拉起的** instrumentation 进程；不按镜像名批量杀进程。

用法：
    python tools/device/run_p2_661_crash_draft.py [--device emulator-5554]
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
ARTIFACTS = REPO / "build" / "p2-661-crash-draft"
TEST_CLASS = "com.keepasskey.app.security.VaultCrashDraftProcessDeathDeviceTest"
APP_ID = "com.keepasskey.debug"
TEST_APK_ID = "com.keepasskey.debug.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
MARKER_PATH = "files/p2-661-crash/GATE_REACHED"


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
        "-e", "p2661Phase", phase,
        f"{TEST_APK_ID}/{RUNNER}",
    ]
    return run(args, check=False)


def write_evidence(name: str, output: str, exit_code: int) -> pathlib.Path:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    stem = ARTIFACTS / name
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
    suite = ET.Element("testsuite", {
        "name": TEST_CLASS,
        "tests": "1",
        "failures": "0" if exit_code == 0 else "1",
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
    run_id = f"p2661-{uuid.uuid4().hex[:8]}"

    # 干净起点：上一轮遗留的库 / 快照 / 标记 / 恢复目录都会让前置段的「创建库」失败或读到旧现场。
    run([str(ADB), "-s", args.device, "shell", "am", "force-stop", APP_ID])
    for path in ("files/p2-661-crash", "files/recovery"):
        run([str(ADB), "-s", args.device, "shell", "run-as", APP_ID, "rm", "-rf", path])

    print(f"[1/4] prepare 段启动（run_id={run_id}）", flush=True)
    # 前置段会一直阻塞到被 force-stop，因此**必须**不加 `-w` 地异步拉起（`-w` 会在终端等待）；
    # 运行态由标记文件与 `pidof` 判定，输出在末尾统一收集。
    prepare = subprocess.Popen(
        [str(ADB), "-s", args.device, "shell", "am", "instrument",
         "-e", "class", f"{TEST_CLASS}#prepareCrashWithDirtyTree",
         "-e", "p2661Phase", "prepare",
         f"{TEST_APK_ID}/{RUNNER}"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        encoding="utf-8", errors="replace",
    )

    deadline = time.monotonic() + args.marker_timeout
    marker = None
    while time.monotonic() < deadline:
        marker = read_marker(args.device)
        if marker:
            break
        # 注意：`am instrument` 不带 `-w` 时，adb shell 会**立即返回**（测试仍在设备上跑），
        # 因此不能以「子进程已退出」作为中止条件；只以标记出现或超时为准。
        time.sleep(0.5)

    if not marker:
        prepare.kill()
        output = prepare.communicate(timeout=15)[0]
        write_evidence(f"{run_id}-prepare", output, 1)
        print(f"ERROR: 前置段未到达死亡门；输出尾部:\n{output[-2000:]}", file=sys.stderr)
        return 1
    old_pid = marker
    print(f"[2/4] 到达死亡门，旧 PID={old_pid}", flush=True)

    print("[3/4] am force-stop 终止应用进程", flush=True)
    run([str(ADB), "-s", args.device, "shell", "am", "force-stop", APP_ID])
    time.sleep(2)
    if process_pid(args.device) == old_pid:
        print("ERROR: force-stop 后旧 PID 仍存活，验收不成立", file=sys.stderr)
        prepare.kill()
        return 1
    try:
        prepare.communicate(timeout=15)
    except subprocess.TimeoutExpired:
        prepare.kill()

    print("[4/4] verify 段（新进程）", flush=True)
    verify = instrument(args.device, "verifyDraftRecoveredInNewProcess", "verify", wait=True)
    output = verify.stdout or ""
    # 判据只看测试结果本身：adb 在设备侧无 `INSTRUMENTATION_CODE` 行（-r 才输出）时退出码会非零，
    # 那不代表用例失败。`OK (N test)` 与 `FAILURES!!!` 是 JUnit 的确定结论。
    passed = "OK (" in output and "FAILURES!!!" not in output
    exit_code = 0 if passed else 1
    xml_path = write_evidence(f"{run_id}-verify", output, exit_code)
    if exit_code != 0:
        print(f"ERROR: 新进程核对失败；证据={xml_path}\n{output[-3000:]}", file=sys.stderr)
        return 1
    print(f"OK: 进程死亡后加密草稿恢复验收通过；证据={xml_path}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
