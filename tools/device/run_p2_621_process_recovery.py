#!/usr/bin/env python3
"""Run the P2-621 formal KDBX recovery scenario across an actual Android process restart."""
from __future__ import annotations

import argparse
import os
import pathlib
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

import run_p1_613_lifecycle as lifecycle
import run_p2_627_locked_queue as queue_runner


REPO = pathlib.Path(__file__).resolve().parents[2]
ARTIFACTS = REPO / ".ci-artifacts" / "remediation-20261015" / "p2-621"
TEST_CLASS = "com.keepasskey.app.sync.SyncRollbackProcessRecoveryDeviceTest"
WEBDAV_USER = "p2-621-process-test"
WEBDAV_PASSWORD = "Synthetic-P2-621-WebDav-Password"


def run_phase(device: str, phase: str, run_id: str, port: int) -> None:
    command = [
        str(lifecycle.ADB), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", f"{TEST_CLASS}#{phase}",
        "-e", "p2_621_run_id", run_id,
        "-e", "p2_621_webdav_port", str(port),
        f"{lifecycle.TEST_ID}/{lifecycle.RUNNER}",
    ]
    started = time.time()
    result = lifecycle.run(command, check=False, capture=True)
    output = result.stdout or ""
    stem = ARTIFACTS / f"{run_id}-{phase}"
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
    valid_result = "INSTRUMENTATION_CODE: -1" in output
    exit_code = 0 if result.returncode == 0 and valid_result else (result.returncode or 1)
    suite = lifecycle.instrumentation_xml(output, TEST_CLASS, phase, time.time() - started, exit_code)
    ET.indent(suite, space="  ")
    xml_path = stem.with_suffix(".xml")
    ET.ElementTree(suite).write(xml_path, encoding="utf-8", xml_declaration=True)
    print(output, end="")
    if result.returncode != 0 or not valid_result or suite.get("tests") == "0":
        raise RuntimeError(f"{phase} instrumentation failed; run_id={run_id}; evidence={xml_path}")
    if suite.get("failures") != "0" or suite.get("errors") != "0":
        raise RuntimeError(f"{phase} tests failed; run_id={run_id}; evidence={xml_path}")


def run_crash_phase(device: str, run_id: str, port: int, stage: str) -> None:
    command = [
        str(lifecycle.ADB), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", f"{TEST_CLASS}#prepare",
        "-e", "p2_621_run_id", run_id,
        "-e", "p2_621_webdav_port", str(port),
        "-e", "p2_621_crash_stage", stage,
        f"{lifecycle.TEST_ID}/{lifecycle.RUNNER}",
    ]
    started = time.time()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    marker_path = f"files/.p2-621-process-{run_id}/crash-marker.txt"
    marker = ""
    deadline = time.monotonic() + 150
    try:
        while time.monotonic() < deadline:
            read_marker = lifecycle.run(
                [str(lifecycle.ADB), "-s", device, "shell", "run-as", lifecycle.APP_ID, "cat", marker_path],
                check=False,
                capture=True,
            )
            if read_marker.returncode == 0:
                marker = read_marker.stdout or ""
                break
            if process.poll() is not None:
                break
            time.sleep(0.25)
        output, _ = process.communicate(timeout=15)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)

    stem = ARTIFACTS / f"{run_id}-prepare-process-crash"
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
    fields = dict(line.split("=", 1) for line in marker.splitlines() if "=" in line)
    if fields.get("stage") != stage:
        raise RuntimeError(f"crash marker mismatch: expected={stage}, marker={marker!r}, output={output[-2500:]}")
    try:
        crashed_pid = int(fields["pid"])
    except (KeyError, ValueError) as error:
        raise RuntimeError(f"crash marker omitted the process id: {marker}") from error
    current = lifecycle.adb(device, "shell", "pidof", lifecycle.APP_ID, capture=True, check=False)
    if str(crashed_pid) in (current.stdout or "").split():
        raise RuntimeError(f"instrumentation process {crashed_pid} survived the requested kill")

    suite = ET.Element(
        "testsuite",
        name=f"{TEST_CLASS}#prepare-process-crash",
        tests="1",
        failures="0",
        errors="0",
        skipped="0",
        time=f"{time.time() - started:.3f}",
        timestamp=time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    )
    ET.SubElement(suite, "properties")
    testcase = ET.SubElement(
        suite,
        "testcase",
        classname=TEST_CLASS,
        name=f"正式库B采纳提交阶段{stage}真实终止进程",
        time="0.000",
    )
    ET.SubElement(testcase, "system-out").text = f"{marker.strip()}\nexit={process.returncode}"
    ET.indent(suite, space="  ")
    xml_path = stem.with_suffix(".xml")
    ET.ElementTree(suite).write(xml_path, encoding="utf-8", xml_declaration=True)
    print(output, end="")
    print(f"host confirmed process death: pid={crashed_pid}, stage={fields['stage']}")
    queue_runner.kill_app_process_without_force_stop(device, lifecycle.APP_ID)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="ADB serial; use the connected Redmi 4X or an isolated AVD")
    args = parser.parse_args()
    device = args.device
    if not lifecycle.ADB.is_file():
        raise SystemExit(f"adb not found: {lifecycle.ADB}")
    lifecycle.ensure_device(device)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="p2-621-webdav-") as temp_dir:
        temp_root = pathlib.Path(temp_dir)
        dav_root = temp_root / "root"
        dav_root.mkdir(parents=True, exist_ok=True)
        webdav_port = queue_runner.allocate_webdav_port()
        cert = REPO / "tools" / "local-sync" / "certs" / "cert.pem"
        environment = dict(os.environ)
        environment.update(
            WEBDAV_HOST="127.0.0.1",
            WEBDAV_PORT=str(webdav_port),
            WEBDAV_USER=WEBDAV_USER,
            WEBDAV_PASSWORD=WEBDAV_PASSWORD,
            WEBDAV_ROOT=str(dav_root),
            WEBDAV_CERT=str(cert),
            WEBDAV_KEY=str(REPO / "tools" / "local-sync" / "certs" / "key.pem"),
        )
        server = None
        server_log = None
        reverse_added = False
        try:
            run_id = uuid.uuid4().hex[:12]
            server, server_log = queue_runner.start_local_webdav(
                environment,
                ARTIFACTS / f"{run_id}-webdav.log",
            )
            lifecycle.wait_for_port("127.0.0.1", webdav_port)
            lifecycle.adb(device, "reverse", f"tcp:{webdav_port}", f"tcp:{webdav_port}")
            reverse_added = True
            build = lifecycle.run(
                [str(lifecycle.GRADLE), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--no-daemon", "--max-workers=1"],
                capture=True,
            )
            (ARTIFACTS / f"{run_id}-build.log").write_text(build.stdout or "", encoding="utf-8")
            app_apk = REPO / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
            test_apk = REPO / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
            lifecycle.adb(device, "install", "-r", str(app_apk))
            lifecycle.adb(device, "install", "-r", "-t", str(test_apk))

            for stage in (
                "INTENT_PERSISTED",
                "CACHE_RECEIPT_COMMITTED",
                "BASE_METADATA_PERSISTED",
                "BASE_CONTENT_PERSISTED",
                "HIGH_WATER_WRITE_STARTED",
            ):
                stage_run_id = uuid.uuid4().hex[:12]
                run_crash_phase(device, stage_run_id, webdav_port, stage)
                run_phase(device, "resumeAfterProcessRestart", stage_run_id, webdav_port)
                queue_runner.kill_app_process_without_force_stop(device, lifecycle.APP_ID)
            print(f"P2-621 process-restart stage XML and receipts: {ARTIFACTS}")
        finally:
            if reverse_added:
                lifecycle.adb(device, "reverse", "--remove", f"tcp:{webdav_port}", check=False)
            if server is not None and server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)
            if server_log is not None:
                server_log.close()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"P2-621 process-recovery check failed: {error}", file=sys.stderr)
        raise SystemExit(1)
