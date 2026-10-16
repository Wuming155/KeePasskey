#!/usr/bin/env python3
"""Run P2-627 locked queue and P3-628 probe checks against a local HTTPS WebDAV server.

The app's debug-only network security config trusts the repository-local HTTPS certificate, so
the harness does not modify the device trust store or reboot for certificate installation. It
uses synthetic vault/WebDAV data and records XML plus raw receipts under
`.ci-artifacts/remediation-20261015/p2-627`.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET

import run_p1_613_lifecycle as lifecycle


REPO = pathlib.Path(__file__).resolve().parents[2]
ARTIFACTS = REPO / ".ci-artifacts" / "remediation-20261015" / "p2-627"
TEST_CLASS = "com.keepasskey.app.sync.PeriodicSyncLockedQueueColdStartDeviceTest"
WEBDAV_USER = "p2-627-test"
WEBDAV_PASSWORD = "Synthetic-P2-627-WebDav-Password"
SLOW_MOVE_DELAY_SECONDS = 60
NETWORK_RECOVERY_SERVER_RESTART_SECONDS = 45


def run_phase(
    device: str,
    phase: str,
    run_id: str,
    webdav_port: int,
    evidence_prefix: str = "",
    work_start_delay_seconds: int | None = None,
    remote_changed_before_worker: bool = False,
    tamper_queue_before_worker: bool = False,
    network_unavailable_before_worker: bool = False,
) -> None:
    command = [
        str(lifecycle.ADB), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", TEST_CLASS,
        "-e", "p2_627_phase", phase,
        "-e", "p2_627_run_id", run_id,
        "-e", "p2_627_webdav_port", str(webdav_port),
    ]
    if work_start_delay_seconds is not None:
        command.extend([
            "-e", "p2_627_work_start_delay_seconds", str(work_start_delay_seconds),
        ])
    if remote_changed_before_worker:
        command.extend(["-e", "p2_627_remote_changed_before_worker", "true"])
    if tamper_queue_before_worker:
        command.extend(["-e", "p2_627_tamper_queue_before_worker", "true"])
    if network_unavailable_before_worker:
        command.extend(["-e", "p2_627_network_unavailable_before_worker", "true"])
    command.append(f"{lifecycle.TEST_ID}/{lifecycle.RUNNER}")
    started = time.time()
    result = lifecycle.run(command, check=False, capture=True)
    output = result.stdout or ""
    elapsed = time.time() - started
    prefix = f"{evidence_prefix}-" if evidence_prefix else ""
    stem = ARTIFACTS / f"{prefix}{phase}-{TEST_CLASS.rsplit('.', 1)[-1]}"
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
    valid_runner_result = "INSTRUMENTATION_CODE: -1" in output
    exit_code = 0 if result.returncode == 0 and valid_runner_result else (result.returncode or 1)
    suite = lifecycle.instrumentation_xml(output, TEST_CLASS, phase, elapsed, exit_code)
    ET.indent(suite, space="  ")
    xml_path = stem.with_suffix(".xml")
    ET.ElementTree(suite).write(xml_path, encoding="utf-8", xml_declaration=True)
    print(output, end="")
    if result.returncode != 0 or not valid_runner_result:
        raise RuntimeError(f"{phase} instrumentation failed; evidence saved under {stem}")
    if suite.get("tests") == "0":
        raise RuntimeError(f"{phase} instrumentation ran zero tests")
    if suite.get("failures") != "0" or suite.get("errors") != "0":
        raise RuntimeError(f"{phase} tests failed; see {xml_path}")


def run_probe_test(
    device: str,
    webdav_port: int,
    scenario: str = "network_available",
    delay_marker: pathlib.Path | None = None,
) -> None:
    if scenario in ("foreground_resume", "network_transition"):
        if delay_marker is None:
            raise RuntimeError("app-process lifecycle scenario requires an HTTPS request marker")
        run_main_process_resume_probe(device, webdav_port, delay_marker, scenario)
        return
    test_class = "com.keepasskey.app.sync.ResumeSyncProbeProductionDeviceTest"
    phase = f"probe-{scenario.replace('_', '-')}"
    method_by_scenario = {
        "network_available": "networkAvailableTriggersProductionHttpsProbe",
        "network_transition": "realNetworkDisconnectAndRecoveryTriggersProductionHttpsProbe",
        "target_switch": "targetChangeDuringProductionHttpsProbeDropsLateResult",
    }
    command = [
        str(lifecycle.ADB), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", f"{test_class}#{method_by_scenario[scenario]}",
        "-e", "p3_628_webdav_port", str(webdav_port),
        f"{lifecycle.TEST_ID}/{lifecycle.RUNNER}",
    ]
    started = time.time()
    if scenario in ("target_switch", "network_transition"):
        if scenario == "target_switch":
            assert delay_marker is not None
            delay_marker.unlink(missing_ok=True)
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + 35
        try:
            ready = False
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    break
                if scenario == "target_switch":
                    ready = delay_marker is not None and delay_marker.is_file()
                else:
                    marker_result = lifecycle.run(
                        [
                            str(lifecycle.ADB), "-s", device, "shell", "run-as", lifecycle.APP_ID,
                            "cat", "files/.p3-628-network-transition.ready",
                        ],
                        check=False,
                        capture=True,
                    )
                    ready = marker_result.returncode == 0
                if ready:
                    break
                time.sleep(0.2)
            if not ready:
                output, _ = process.communicate(timeout=5)
                wait_description = "delayed production PROPFIND" if scenario == "target_switch" else "offline-ready marker"
                raise RuntimeError(f"probe scenario did not reach {wait_description}:\n{output[-4000:]}")
            if scenario == "target_switch":
                lifecycle.adb(
                    device,
                    "shell",
                    "am",
                    "broadcast",
                    "-a",
                    "com.keepasskey.debug.P3_628_SWITCH_TARGET",
                    "-p",
                    lifecycle.APP_ID,
                )
            else:
                if scenario == "network_transition":
                    lifecycle.adb(device, "shell", "svc", "wifi", "enable")
                    reconnect_deadline = time.monotonic() + 60
                    next_wifi_scan = 0.0
                    while time.monotonic() < reconnect_deadline:
                        wifi_status = lifecycle.adb(
                            device, "shell", "cmd", "wifi", "status", capture=True
                        ).stdout or ""
                        if "Wifi is connected" in wifi_status:
                            break
                        if time.monotonic() >= next_wifi_scan:
                            lifecycle.adb(device, "shell", "cmd", "wifi", "start-scan", check=False)
                            next_wifi_scan = time.monotonic() + 2
                        time.sleep(1)
                    else:
                        raise RuntimeError("Redmi Wi-Fi did not reconnect to its saved network")
                    lifecycle.adb(
                        device, "shell", "am", "broadcast", "-a",
                        "com.keepasskey.debug.P3_628_NETWORK_RESTORED", "-p", lifecycle.APP_ID,
                    )
            output, _ = process.communicate(timeout=135)
            return_code = process.returncode or 0
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
    else:
        result = lifecycle.run(command, check=False, capture=True)
        output = result.stdout or ""
        return_code = result.returncode
    elapsed = time.time() - started
    stem = ARTIFACTS / f"{phase}-ResumeSyncProbeProductionDeviceTest"
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
    valid_runner_result = "INSTRUMENTATION_CODE: -1" in output
    exit_code = 0 if return_code == 0 and valid_runner_result else (return_code or 1)
    suite = lifecycle.instrumentation_xml(output, test_class, phase, elapsed, exit_code)
    ET.indent(suite, space="  ")
    xml_path = stem.with_suffix(".xml")
    ET.ElementTree(suite).write(xml_path, encoding="utf-8", xml_declaration=True)
    print(output, end="")
    if return_code != 0 or not valid_runner_result or suite.get("tests") == "0":
        raise RuntimeError(f"production probe instrumentation failed; see {xml_path}")
    if suite.get("failures") != "0" or suite.get("errors") != "0":
        raise RuntimeError(f"production probe test failed; see {xml_path}")


def run_main_process_resume_probe(
    device: str,
    webdav_port: int,
    request_marker: pathlib.Path,
    scenario: str,
) -> None:
    """Drive the debug app's own MainApplication callbacks, outside the test APK process."""
    run_id = uuid.uuid4().hex[:12]
    ready_file = "files/.p3-628-main-process.ready"
    error_file = "files/.p3-628-main-process.error"
    notice_file = "files/.p3-628-main-process.notice"
    request_marker.unlink(missing_ok=True)
    for name in (
        ready_file,
        error_file,
        notice_file,
        "files/.p3-628-main-process.received",
        "files/.p3-628-main-process.stage",
    ):
        lifecycle.adb(device, "shell", "run-as", lifecycle.APP_ID, "rm", name, check=False)
    output: list[str] = []
    passed = False
    started = time.monotonic()
    setup_sent = False

    def broadcast(action: str, *extra_args: str) -> None:
        lifecycle.adb(
            device,
            "shell",
            "am",
            "broadcast",
            "-a",
            action,
            "-n",
            f"{lifecycle.APP_ID}/com.keepasskey.app.sync.P3_628ScenarioReceiver",
            *extra_args,
        )

    def read_app_file(path: str) -> str:
        result = lifecycle.run(
            [str(lifecycle.ADB), "-s", device, "shell", "run-as", lifecycle.APP_ID, "cat", path],
            check=False,
            capture=True,
        )
        return result.stdout or "" if result.returncode == 0 else ""

    try:
        broadcast(
            "com.keepasskey.debug.P3_628_SETUP_MAIN_PROCESS",
            "--es", "p3_628_run_id", run_id,
            "--ei", "p3_628_webdav_port", str(webdav_port),
        )
        setup_sent = True
        setup_deadline = time.monotonic() + 30
        while time.monotonic() < setup_deadline:
            if read_app_file(error_file).strip():
                raise RuntimeError(f"app-process setup failed: {read_app_file(error_file).strip()}")
            if read_app_file(ready_file).strip():
                break
            time.sleep(0.25)
        else:
            received = read_app_file("files/.p3-628-main-process.received")
            stage = read_app_file("files/.p3-628-main-process.stage")
            raise RuntimeError(
                f"app-process P3-628 setup marker was not created (receiver={received or 'none'}, stage={stage or 'none'})"
            )

        component = f"{lifecycle.APP_ID}/com.keepasskey.app.MainActivity"
        output.append(lifecycle.adb(device, "shell", "am", "start", "-W", "-n", component, capture=True).stdout or "")
        time.sleep(1.0)
        # Setup disables the probe toggle. The cold-start callback can seed a throttle baseline,
        # so clear its receipt and wait out the 30-second production throttle before the event.
        request_marker.unlink(missing_ok=True)
        if scenario == "foreground_resume":
            time.sleep(31.0)
            request_marker.unlink(missing_ok=True)
            lifecycle.adb(device, "shell", "input", "keyevent", "3")
            time.sleep(1.5)
            broadcast("com.keepasskey.debug.P3_628_ENABLE_PROBE")
            output.append(
                lifecycle.adb(device, "shell", "am", "start", "-W", "-n", component, capture=True).stdout or ""
            )
        else:
            wifi_before = lifecycle.adb(device, "shell", "cmd", "wifi", "status", capture=True).stdout or ""
            if "Wifi is connected" not in wifi_before:
                raise RuntimeError("network-recovery scenario requires saved Wi-Fi to be connected")
            lifecycle.adb(device, "shell", "svc", "wifi", "disable")
            disabled_deadline = time.monotonic() + 15
            while time.monotonic() < disabled_deadline:
                wifi_status = lifecycle.adb(device, "shell", "cmd", "wifi", "status", capture=True).stdout or ""
                if "Wifi is disabled" in wifi_status:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("Wi-Fi did not disconnect for the app-process recovery scenario")
            time.sleep(31.0)
            broadcast("com.keepasskey.debug.P3_628_ENABLE_PROBE")
            lifecycle.adb(device, "shell", "svc", "wifi", "enable")
            reconnect_deadline = time.monotonic() + 60
            next_wifi_scan = 0.0
            while time.monotonic() < reconnect_deadline:
                wifi_status = lifecycle.adb(device, "shell", "cmd", "wifi", "status", capture=True).stdout or ""
                if "Wifi is connected" in wifi_status:
                    break
                if time.monotonic() >= next_wifi_scan:
                    lifecycle.adb(device, "shell", "cmd", "wifi", "start-scan", check=False)
                    next_wifi_scan = time.monotonic() + 2
                time.sleep(1)
            else:
                raise RuntimeError("Wi-Fi did not reconnect for the app-process recovery scenario")

        request_deadline = time.monotonic() + 45
        while time.monotonic() < request_deadline and not request_marker.is_file():
            time.sleep(0.2)
        if not request_marker.is_file():
            broadcast("com.keepasskey.debug.P3_628_ASSERT_NOTICE")
            time.sleep(0.2)
            status = read_app_file(notice_file)
            raise RuntimeError(
                f"app-process {scenario} did not issue production HTTPS PROPFIND; notice-state={status or 'missing'}"
            )
        time.sleep(2.0)  # Wait for the response body and the app-owned notice StateFlow update.
        broadcast("com.keepasskey.debug.P3_628_ASSERT_NOTICE")
        notice_deadline = time.monotonic() + 10
        notice = ""
        while time.monotonic() < notice_deadline:
            notice = read_app_file(notice_file).strip()
            if notice:
                break
            time.sleep(0.2)
        output.append(f"app-process recovery notice receipt: {notice or 'missing'}")
        if "etagChanged=true" not in notice:
            raise RuntimeError(f"app-process probe did not update the expected ETag: {notice or 'missing'}")
        output.append(
            f"MainApplication {scenario.replace('_', ' ')} produced production HTTPS and updated ETag/notice"
        )
        passed = True
    finally:
        if setup_sent:
            lifecycle.adb(
                device,
                "shell",
                "am",
                "broadcast",
                "-a",
                "com.keepasskey.debug.P3_628_CLEANUP",
                "-n",
                f"{lifecycle.APP_ID}/com.keepasskey.app.sync.P3_628ScenarioReceiver",
                check=False,
            )
        lifecycle.adb(device, "shell", "input", "keyevent", "3", check=False)
        elapsed = time.monotonic() - started
        stem = ARTIFACTS / f"probe-{scenario.replace('_', '-')}-main-process-{run_id}"
        stem.with_suffix(".instrumentation.txt").write_text("\n".join(output), encoding="utf-8")
        suite = ET.Element(
            "testsuite",
            name=f"P3-628 MainApplication {scenario.replace('_', ' ')}",
            tests="1",
            failures="0" if passed else "1",
            errors="0",
            skipped="0",
            time=f"{elapsed:.3f}",
        )
        testcase = ET.SubElement(
            suite,
            "testcase",
            classname="com.keepasskey.app.sync.MainApplicationForegroundProbeDeviceTest",
            name=f"{scenario.replace('_', ' ')} triggers production HTTPS probe",
            time=f"{elapsed:.3f}",
        )
        if not passed:
            failure = ET.SubElement(testcase, "failure", message="main-process foreground probe failed")
            failure.text = "\n".join(output)
        ET.indent(suite, space="  ")
        ET.ElementTree(suite).write(stem.with_suffix(".xml"), encoding="utf-8", xml_declaration=True)
    if not passed:
        raise RuntimeError(f"app-process {scenario} probe failed; see {stem.with_suffix('.xml')}")


def run_slow_lock_test(
    device: str,
    move_marker: pathlib.Path,
    run_id: str,
    webdav_port: int,
    lock_mode: str,
) -> None:
    test_class = "com.keepasskey.app.sync.SyncSlowHttpsScreenOffLockDeviceTest"
    phase = "slow-screen-off-lock" if lock_mode == "screen_off" else "slow-background-lock"
    command = [
        str(lifecycle.ADB), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", test_class,
        "-e", "p2_625_run_id", run_id,
        "-e", "p2_625_webdav_port", str(webdav_port),
        "-e", "p2_625_lock_mode", lock_mode,
        f"{lifecycle.TEST_ID}/{lifecycle.RUNNER}",
    ]
    initial_power = lifecycle.adb(device, "shell", "dumpsys", "power", capture=True).stdout or ""
    screen_was_awake = "mWakefulness=Awake" in initial_power or "Display Power: state=ON" in initial_power
    lifecycle.adb(device, "shell", "input", "keyevent", "224")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    started = time.time()
    try:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline and not move_marker.is_file():
            if process.poll() is not None:
                break
            time.sleep(0.2)
        if not move_marker.is_file():
            output, _ = process.communicate(timeout=5)
            elapsed = time.time() - started
            valid_runner_result = "INSTRUMENTATION_CODE: -1" in output
            exit_code = 0 if process.returncode == 0 and valid_runner_result else (process.returncode or 1)
            suite = lifecycle.instrumentation_xml(
                output, test_class, phase, elapsed, exit_code
            )
            ET.indent(suite, space="  ")
            stem = ARTIFACTS / f"slow-{lock_mode}-lock-SyncSlowHttpsScreenOffLockDeviceTest"
            stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
            ET.ElementTree(suite).write(stem.with_suffix(".xml"), encoding="utf-8", xml_declaration=True)
            raise RuntimeError(f"slow MOVE was not observed; instrumentation output:\n{output[-5000:]}")

        lifecycle.adb(
            device,
            "shell",
            "input",
            "keyevent",
            "26" if lock_mode == "screen_off" else "3",
        )
        output, _ = process.communicate(timeout=45)
        elapsed = time.time() - started
        valid_runner_result = "INSTRUMENTATION_CODE: -1" in output
        exit_code = 0 if process.returncode == 0 and valid_runner_result else (process.returncode or 1)
        suite = lifecycle.instrumentation_xml(
            output, test_class, phase, elapsed, exit_code
        )
        ET.indent(suite, space="  ")
        stem = ARTIFACTS / f"slow-{lock_mode}-lock-SyncSlowHttpsScreenOffLockDeviceTest"
        stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
        ET.ElementTree(suite).write(stem.with_suffix(".xml"), encoding="utf-8", xml_declaration=True)
        print(output, end="")
        if process.returncode != 0 or not valid_runner_result or suite.get("tests") == "0":
            raise RuntimeError(f"slow screen-off lock instrumentation failed; see {stem.with_suffix('.xml')}")
        if suite.get("failures") != "0" or suite.get("errors") != "0":
            raise RuntimeError(f"slow screen-off lock device test failed; see {stem.with_suffix('.xml')}")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        current_power = lifecycle.adb(device, "shell", "dumpsys", "power", capture=True, check=False).stdout or ""
        screen_is_awake = "mWakefulness=Awake" in current_power or "Display Power: state=ON" in current_power
        if screen_was_awake and not screen_is_awake:
            lifecycle.adb(device, "shell", "input", "keyevent", "224", check=False)
        elif not screen_was_awake and screen_is_awake:
            lifecycle.adb(device, "shell", "input", "keyevent", "26", check=False)


def allocate_webdav_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def start_local_webdav(environment: dict[str, str], log_path: pathlib.Path):
    log_handle = log_path.open("w", encoding="utf-8")
    try:
        process = subprocess.Popen(
            [sys.executable, str(REPO / "tools" / "local-sync" / "run_webdav.py")],
            cwd=REPO / "tools" / "local-sync",
            env=environment,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
        )
    except Exception:
        log_handle.close()
        raise
    return process, log_handle


def kill_app_process_without_force_stop(device: str, package: str) -> None:
    """Kill the app process while leaving Android's package stopped flag clear."""
    result = lifecycle.run(
        [str(lifecycle.ADB), "-s", device, "shell", "pidof", package],
        check=False,
        capture=True,
    )
    pids = (result.stdout or "").split()
    for pid in pids:
        lifecycle.adb(device, "shell", "kill", "-9", pid)
    if pids:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            check = lifecycle.run(
                [str(lifecycle.ADB), "-s", device, "shell", "pidof", package],
                check=False,
                capture=True,
            )
            if not (check.stdout or "").strip():
                break
            time.sleep(0.25)
        else:
            raise RuntimeError(f"{package} process remained alive after SIGKILL")
    package_state = lifecycle.run(
        [str(lifecycle.ADB), "-s", device, "shell", "dumpsys", "package", package],
        check=True,
        capture=True,
    ).stdout or ""
    if "stopped=true" in package_state:
        raise RuntimeError(f"{package} became force-stopped; this is not ordinary process death")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", default="emulator-5554", help="ADB serial; system-reboot scenario is limited to AVD")
    parser.add_argument("--probe-only", action="store_true", help="run only the P3-628 production HTTPS probe test")
    parser.add_argument("--probe-network-transition", action="store_true", help="toggle Redmi Wi-Fi off/on and verify production HTTPS probe recovery")
    parser.add_argument("--probe-target-switch", action="store_true", help="switch active database while production HTTPS PROPFIND is in flight")
    parser.add_argument("--probe-foreground-resume", action="store_true", help="verify MainApplication foreground resume triggers production HTTPS")
    parser.add_argument(
        "--slow-screen-off-lock",
        action="store_true",
        help="run P2-625: cancel a production WebDAV MOVE after a real screen-off event",
    )
    parser.add_argument(
        "--slow-background-lock",
        action="store_true",
        help="run P2-625: cancel a production WebDAV MOVE after the app enters background",
    )
    parser.add_argument(
        "--system-reboot-before-resume",
        action="store_true",
        help="verify the queued WorkManager request survives an Android system reboot",
    )
    parser.add_argument(
        "--simulate-remote-change-before-resume",
        action="store_true",
        help="change the remote file after prepare and verify the stale ETag cannot overwrite it",
    )
    parser.add_argument(
        "--simulate-412-during-upload",
        action="store_true",
        help="change the remote file immediately before its conditional MOVE so the server returns 412",
    )
    parser.add_argument(
        "--tamper-pending-queue-before-worker",
        action="store_true",
        help="tamper with the queued encrypted payload and verify the worker fails closed",
    )
    parser.add_argument(
        "--simulate-network-outage-and-recovery",
        action="store_true",
        help="remove ADB reverse until the worker reports a temporary error, then restore and retry",
    )
    args = parser.parse_args()
    probe_scenarios = [
        (args.probe_network_transition, "network_transition"),
        (args.probe_target_switch, "target_switch"),
        (args.probe_foreground_resume, "foreground_resume"),
    ]
    selected_probe_scenarios = [scenario for enabled, scenario in probe_scenarios if enabled]
    if len(selected_probe_scenarios) > 1 or (args.probe_only and selected_probe_scenarios):
        raise SystemExit("only one P3-628 probe scenario may run per invocation")
    probe_scenario = selected_probe_scenarios[0] if selected_probe_scenarios else "network_available"
    probe_only_scenario = args.probe_only or bool(selected_probe_scenarios)
    if args.slow_screen_off_lock and args.slow_background_lock:
        raise SystemExit("screen-off and background lock triggers must run separately")
    slow_lock_scenario = args.slow_screen_off_lock or args.slow_background_lock
    if slow_lock_scenario:
        if probe_only_scenario or any((args.system_reboot_before_resume, args.simulate_remote_change_before_resume,
                args.tamper_pending_queue_before_worker, args.simulate_network_outage_and_recovery)):
            raise SystemExit("slow lock scenario may only combine with the explicit HTTP 412 option")
    if probe_only_scenario and any((
        args.slow_screen_off_lock,
        args.slow_background_lock,
        args.system_reboot_before_resume,
        args.simulate_remote_change_before_resume,
        args.simulate_412_during_upload,
        args.tamper_pending_queue_before_worker,
        args.simulate_network_outage_and_recovery,
    )):
        raise SystemExit("P3-628 probe scenarios run independently from P2-625/P2-627 scenarios")
    elif sum((args.system_reboot_before_resume, args.simulate_remote_change_before_resume,
              args.simulate_412_during_upload, args.tamper_pending_queue_before_worker,
              args.simulate_network_outage_and_recovery)) > 1:
        raise SystemExit("system reboot, remote-change, HTTP 412, queue-tamper, and network-outage scenarios must run separately")
    device = args.device
    if args.system_reboot_before_resume and not device.startswith("emulator-"):
        raise SystemExit("system-reboot scenario is limited to the isolated AVD")
    if not lifecycle.ADB.is_file():
        raise SystemExit(f"adb not found: {lifecycle.ADB}")
    lifecycle.ensure_device(device)

    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex[:12]
    webdav_port = allocate_webdav_port()
    cert = REPO / "tools" / "local-sync" / "certs" / "cert.pem"
    server = None
    server_log_handle = None
    network_restart_timer = None
    wifi_originally_enabled: bool | None = None
    reverse_added = False
    try:
        build = lifecycle.run(
            [str(lifecycle.GRADLE), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--no-daemon", "--max-workers=1"],
            capture=True,
        )
        (ARTIFACTS / "build.log").write_text(build.stdout or "", encoding="utf-8")
        print((build.stdout or "")[-4000:])

        with tempfile.TemporaryDirectory(prefix="p2-627-webdav-") as temp_dir:
            environment = dict(os.environ)
            probe_delay_marker = ARTIFACTS / f"p3-628-probe-target-switch-{run_id}.ready"
            probe_request_marker = ARTIFACTS / f"p3-628-main-process-probe-{run_id}.ready"
            environment.update(
                WEBDAV_HOST="127.0.0.1",
                WEBDAV_PORT=str(webdav_port),
                WEBDAV_USER=WEBDAV_USER,
                WEBDAV_PASSWORD=WEBDAV_PASSWORD,
                WEBDAV_ROOT=str(pathlib.Path(temp_dir) / "root"),
                WEBDAV_CERT=str(cert),
                WEBDAV_KEY=str(REPO / "tools" / "local-sync" / "certs" / "key.pem"),
                WEBDAV_RACE_MARKER=str(pathlib.Path(temp_dir) / "race-once"),
                WEBDAV_RACE_PATH=(f"/p2-625-slow-lock-{run_id}.kdbx" if slow_lock_scenario
                                  else f"/p2-627-queue-{run_id}.kdbx"),
                WEBDAV_RACE_MOVE_NUMBER=(
                    "2" if slow_lock_scenario and args.simulate_412_during_upload else "0"
                ),
                WEBDAV_DELAY_MOVE_NUMBER=(
                    "2" if slow_lock_scenario and not args.simulate_412_during_upload else "0"
                ),
                WEBDAV_DELAY_SECONDS=str(SLOW_MOVE_DELAY_SECONDS),
                WEBDAV_DELAY_PROBE_AFTER_RACE_SECONDS=(
                    str(SLOW_MOVE_DELAY_SECONDS)
                    if slow_lock_scenario and args.simulate_412_during_upload
                    else "0"
                ),
                WEBDAV_DELAY_PROBE_SECONDS=("15" if probe_scenario == "target_switch" else "0"),
                WEBDAV_PROBE_DELAY_MARKER=str(probe_delay_marker),
                WEBDAV_PROBE_REQUEST_MARKER=(
                    str(probe_request_marker)
                    if probe_scenario in ("foreground_resume", "network_transition")
                    else ""
                ),
                WEBDAV_DELAY_MARKER=str(ARTIFACTS / f"p2-625-slow-move-{run_id}.ready"),
            )
            dav_root = pathlib.Path(environment["WEBDAV_ROOT"])
            race_triggered = ARTIFACTS / f"http-412-race-{run_id}.triggered"
            race_marker = pathlib.Path(temp_dir) / "race-once"
            environment["WEBDAV_RACE_TRIGGERED"] = str(race_triggered)
            environment["WEBDAV_RACE_MARKER"] = str(race_marker)
            dav_root.mkdir(parents=True, exist_ok=True)
            (dav_root / "p3-628-probe.kdbx").write_bytes(
                b"synthetic encrypted payload for metadata probe"
            )
            server, server_log_handle = start_local_webdav(
                environment,
                ARTIFACTS / f"webdav-{run_id}.log",
            )
            lifecycle.wait_for_port("127.0.0.1", webdav_port)
            lifecycle.adb(device, "reverse", f"tcp:{webdav_port}", f"tcp:{webdav_port}")
            reverse_added = True

            def restart_webdav_after_network_outage() -> None:
                nonlocal server, server_log_handle
                server, server_log_handle = start_local_webdav(
                    environment,
                    ARTIFACTS / f"webdav-{run_id}-recovery.log",
                )
                lifecycle.wait_for_port("127.0.0.1", webdav_port)

            app_apk = REPO / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
            test_apk = REPO / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
            lifecycle.adb(device, "install", "-r", str(app_apk))
            lifecycle.adb(device, "install", "-r", "-t", str(test_apk))

            if probe_only_scenario:
                if probe_scenario == "network_transition":
                    wifi_status = lifecycle.adb(device, "shell", "cmd", "wifi", "status", capture=True).stdout or ""
                    wifi_originally_enabled = "Wifi is enabled" in wifi_status
                    if not wifi_originally_enabled:
                        raise RuntimeError("network-transition test requires Wi-Fi to be enabled before the test")
                    lifecycle.adb(
                        device,
                        "shell",
                        "run-as",
                        lifecycle.APP_ID,
                        "rm",
                        "files/.p3-628-network-transition.ready",
                        check=False,
                    )
                run_probe_test(
                    device,
                    webdav_port,
                    probe_scenario,
                    probe_request_marker
                    if probe_scenario in ("foreground_resume", "network_transition")
                    else probe_delay_marker,
                )
            elif slow_lock_scenario:
                slow_move_marker = pathlib.Path(environment["WEBDAV_DELAY_MARKER"])
                slow_move_marker.unlink(missing_ok=True)
                lock_mode = "screen_off" if args.slow_screen_off_lock else "background"
                run_slow_lock_test(device, slow_move_marker, run_id, webdav_port, lock_mode)
                if args.simulate_412_during_upload:
                    if not race_triggered.is_file():
                        raise RuntimeError("slow lock/412 server response receipt was not created")
                    remote_file = dav_root / f"p2-625-slow-lock-{run_id}.kdbx"
                    expected_remote = b"synthetic remote revision C inserted before conditional MOVE"
                    if not remote_file.is_file() or remote_file.read_bytes() != expected_remote:
                        raise RuntimeError("slow lock/412 race did not leave remote revision C intact")
                run_probe_test(device, webdav_port)
            else:
                evidence_prefix = (
                    "system-reboot" if args.system_reboot_before_resume else
                    "remote-changed" if args.simulate_remote_change_before_resume else
                    "http-412" if args.simulate_412_during_upload else
                    "tampered-queue" if args.tamper_pending_queue_before_worker else
                    "network-recovery" if args.simulate_network_outage_and_recovery else ""
                )
                remote_change_scenario = (
                    args.simulate_remote_change_before_resume or args.simulate_412_during_upload
                )
                network_scenario = args.simulate_network_outage_and_recovery
                run_phase(
                    device,
                    "prepare",
                    run_id,
                    webdav_port,
                    evidence_prefix,
                    work_start_delay_seconds=(
                        600 if network_scenario else 300 if args.system_reboot_before_resume else 60
                    ),
                    remote_changed_before_worker=remote_change_scenario,
                    tamper_queue_before_worker=args.tamper_pending_queue_before_worker,
                    network_unavailable_before_worker=network_scenario,
                )
                if args.simulate_remote_change_before_resume:
                    remote_path = pathlib.Path(environment["WEBDAV_ROOT"]) / f"p2-627-queue-{run_id}.kdbx"
                    if not remote_path.is_file():
                        raise RuntimeError(f"prepare did not create the expected remote file: {remote_path.name}")
                    remote_path.write_bytes(b"synthetic remote revision C changed after prepare")
                if args.simulate_412_during_upload:
                    race_marker.touch()
                if network_scenario:
                    server.terminate()
                    server.wait(timeout=5)
                    server_log_handle.flush()
                    server_log_handle.close()
                    server_log_handle = None
                    server = None
                if args.system_reboot_before_resume:
                    lifecycle.adb(device, "reboot")
                    lifecycle.wait_for_device(device)
                    lifecycle.adb(device, "reverse", f"tcp:{webdav_port}", f"tcp:{webdav_port}")
                else:
                    kill_app_process_without_force_stop(device, lifecycle.APP_ID)
                    lifecycle.force_stop_and_verify(device, lifecycle.TEST_ID)
                    if network_scenario:
                        network_restart_timer = threading.Timer(
                            NETWORK_RECOVERY_SERVER_RESTART_SECONDS,
                            restart_webdav_after_network_outage,
                        )
                        network_restart_timer.start()
                run_phase(
                    device,
                    "resume",
                    run_id,
                    webdav_port,
                    evidence_prefix,
                    remote_changed_before_worker=remote_change_scenario,
                    tamper_queue_before_worker=args.tamper_pending_queue_before_worker,
                    network_unavailable_before_worker=network_scenario,
                )
                if network_restart_timer is not None:
                    network_restart_timer.join(timeout=60)
                    if network_restart_timer.is_alive() or server is None:
                        raise RuntimeError("local HTTPS service did not recover during network retry test")
                if args.simulate_412_during_upload and not race_triggered.is_file():
                    raise RuntimeError("server did not trigger the conditional MOVE ETag race")
                run_probe_test(device, webdav_port)

        print(f"P2-627 device XML and instrumentation receipts: {ARTIFACTS}")
        return 0
    finally:
        if probe_scenario == "network_transition" and wifi_originally_enabled is not None:
            lifecycle.adb(
                device,
                "shell",
                "svc",
                "wifi",
                "enable" if wifi_originally_enabled else "disable",
                check=False,
            )
            if wifi_originally_enabled:
                lifecycle.adb(device, "shell", "cmd", "wifi", "start-scan", check=False)
        if reverse_added:
            lifecycle.adb(device, "reverse", "--remove", f"tcp:{webdav_port}", check=False)
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
        if server_log_handle is not None:
            server_log_handle.close()
        if network_restart_timer is not None and network_restart_timer.is_alive():
            network_restart_timer.cancel()
            network_restart_timer.join(timeout=5)
        lifecycle.adb(device, "uninstall", lifecycle.TEST_ID, check=False)
        lifecycle.adb(device, "uninstall", lifecycle.APP_ID, check=False)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"[P2-627 locked queue] ERROR: {type(error).__name__}: {error}", file=sys.stderr)
        raise
