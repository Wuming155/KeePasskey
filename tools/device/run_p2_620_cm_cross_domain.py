#!/usr/bin/env python3
"""Run real Credential Manager A/B-origin device requests across provider process death."""
from __future__ import annotations

import argparse
import pathlib
import sys
import time
import uuid
import xml.etree.ElementTree as ET

import run_p1_613_lifecycle as lifecycle
import run_p2_627_locked_queue as queue_runner


REPO = pathlib.Path(__file__).resolve().parents[2]
ARTIFACTS = REPO / ".ci-artifacts" / "remediation-20261015" / "p2-620"
TEST_CLASS = "com.keepasskey.app.passkey.CredentialManagerCrossDomainDeviceTest"


def run_phase(device: str, method: str, run_id: str, manual_cm_confirm: bool = False) -> None:
    command = [
        str(lifecycle.ADB), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", f"{TEST_CLASS}#{method}",
        "-e", "p2_620_run_id", run_id,
        f"{lifecycle.TEST_ID}/{lifecycle.RUNNER}",
    ]
    if manual_cm_confirm:
        command[-1:-1] = ["-e", "p2_620ManualCMConfirm", "true"]
    started = time.time()
    result = lifecycle.run(command, check=False, capture=True)
    output = result.stdout or ""
    stem = ARTIFACTS / f"{run_id}-{method}"
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")
    valid_result = "INSTRUMENTATION_CODE: -1" in output
    exit_code = 0 if result.returncode == 0 and valid_result else (result.returncode or 1)
    suite = lifecycle.instrumentation_xml(output, TEST_CLASS, method, time.time() - started, exit_code)
    ET.indent(suite, space="  ")
    xml_path = stem.with_suffix(".xml")
    ET.ElementTree(suite).write(xml_path, encoding="utf-8", xml_declaration=True)
    print(output, end="")
    if result.returncode != 0 or not valid_result or suite.get("tests") == "0":
        raise RuntimeError(f"{method} instrumentation failed; run_id={run_id}; evidence={xml_path}")
    if suite.get("failures") != "0" or suite.get("errors") != "0":
        raise RuntimeError(f"{method} tests failed; run_id={run_id}; evidence={xml_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="ADB serial; run first on the connected Redmi 4X")
    parser.add_argument(
        "--manual-confirm",
        action="store_true",
        help="Pause at the A/B system confirmation pages for a human tap on device",
    )
    args = parser.parse_args()
    if not lifecycle.ADB.is_file():
        raise SystemExit(f"adb not found: {lifecycle.ADB}")
    lifecycle.ensure_device(args.device)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex[:12]
    (ARTIFACTS / "latest_run_id.txt").write_text(run_id, encoding="utf-8")
    build = lifecycle.run(
        [str(lifecycle.GRADLE), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--no-daemon", "--max-workers=1"],
        capture=True,
    )
    (ARTIFACTS / f"{run_id}-build.log").write_text(build.stdout or "", encoding="utf-8")
    app_apk = REPO / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    test_apk = REPO / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
    lifecycle.adb(args.device, "install", "-r", str(app_apk))
    lifecycle.adb(args.device, "install", "-r", "-t", str(test_apk))

    run_phase(args.device, "prepareOldDomainCandidate", run_id)
    queue_runner.kill_app_process_without_force_stop(args.device, lifecycle.APP_ID)
    run_phase(
        args.device,
        "resumeOldCandidateAfterProviderProcessDeath",
        run_id,
        manual_cm_confirm=args.manual_confirm,
    )
    print(f"P2-620 system Credential Manager A/B process-death XML and receipts: {ARTIFACTS}; run_id={run_id}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"P2-620 Credential Manager check failed: {error}", file=sys.stderr)
        raise SystemExit(1)
