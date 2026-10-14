#!/usr/bin/env python3
"""Run ISSUE-P1-613's two real process-restart device scenarios on one isolated AVD.

The harness builds and installs only to the selected emulator, adds the repository's
localhost test CA to that AVD's user trust store; the debug-only network config
trusts user CAs while release builds remain system-CA-only. The harness removes the
CA and reboots the AVD in a finally block. It starts the independent
HTTPS WebDAV server outside the app process, runs prepare instrumentation, performs
an OS force-stop, and runs resume instrumentation. AndroidJUnitRunner status events
are retained verbatim and converted to per-phase JUnit XML under .ci-artifacts.

The target must be an emulator serial. Physical-device serials are rejected because
the test installs a debug APK and reboots the device to add/remove a temporary CA.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


REPO = pathlib.Path(__file__).resolve().parents[2]
ADB = pathlib.Path(os.environ.get("ANDROID_HOME", r"D:\Android\SDK")) / "platform-tools" / "adb.exe"
GRADLE = REPO / "gradlew.bat"
APP_ID = "com.keepasskey.debug"
TEST_ID = f"{APP_ID}.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
DEFAULT_AVD_NAME = "KeePasskeyAudit_20261013"
PORT = 9443
WEBDAV_USER = "p1-613-test"
WEBDAV_PASSWORD = "Synthetic-WebDav-Password"
PHASES = (
    ("sync", "com.keepasskey.app.sync.SyncForceStopWebDavDeviceTest"),
    ("recovery", "com.keepasskey.app.security.SessionRecoveryProductUiProcessRestartDeviceTest"),
)


def run(command: list[str], *, cwd: pathlib.Path = REPO, check: bool = True, capture: bool = False):
    result = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=False,
    )
    if check and result.returncode != 0:
        detail = result.stdout[-5000:] if capture and result.stdout else ""
        raise RuntimeError(f"command failed ({result.returncode}): {command[0]} {detail}")
    return result


def adb(device: str, *args: str, check: bool = True, capture: bool = False):
    return run([str(ADB), "-s", device, *args], check=check, capture=capture)


def ensure_device(device: str) -> None:
    listing = run([str(ADB), "devices"], capture=True, check=False).stdout or ""
    if any(line.startswith(f"{device}\tdevice") for line in listing.splitlines()):
        return
    if device != "emulator-5554":
        raise RuntimeError(f"指定隔离 AVD 未连接：{device}")
    cli = pathlib.Path(os.environ.get("USERPROFILE", "")) / ".android" / "bin" / "android-cli.exe"
    if not cli.is_file():
        raise RuntimeError(f"{DEFAULT_AVD_NAME} 未启动且找不到 Android CLI：{cli}")
    run([str(cli), "emulator", "start", DEFAULT_AVD_NAME], check=True)
    listing = run([str(ADB), "devices"], capture=True, check=False).stdout or ""
    if not any(line.startswith(f"{device}\tdevice") for line in listing.splitlines()):
        raise RuntimeError(f"{DEFAULT_AVD_NAME} 启动后未出现在 ADB：{listing}")


def openssl_path() -> str:
    candidate = shutil.which("openssl")
    if candidate:
        return candidate
    git_openssl = pathlib.Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Git/usr/bin/openssl.exe"
    if git_openssl.is_file():
        return str(git_openssl)
    raise RuntimeError("需要 openssl 计算 Android CA 索引名")


def certificate_hash(cert: pathlib.Path) -> str:
    result = run([openssl_path(), "x509", "-subject_hash_old", "-in", str(cert), "-noout"], capture=True)
    return result.stdout.strip().splitlines()[-1]


def wait_for_device(device: str) -> None:
    adb(device, "wait-for-device")
    deadline = time.time() + 120
    while time.time() < deadline:
        result = adb(device, "shell", "getprop", "sys.boot_completed", capture=True, check=False)
        if result.returncode == 0 and (result.stdout or "").strip() == "1":
            return
        time.sleep(2)
    raise TimeoutError("AVD 未在 120 秒内完成启动")


def install_user_ca(device: str, cert: pathlib.Path, digest: str) -> tuple[str, bool]:
    destination = f"/data/misc/user/0/cacerts-added/{digest}.0"
    adb(device, "root")
    wait_for_device(device)
    exists = adb(device, "shell", "test", "-f", destination, check=False).returncode == 0
    if exists:
        with tempfile.TemporaryDirectory(prefix="p1-613-ca-") as temp_dir:
            current = pathlib.Path(temp_dir) / "existing-ca.0"
            adb(device, "pull", destination, str(current))
            if current.read_bytes() != cert.read_bytes():
                raise RuntimeError(f"user CA 路径已存在不同证书，拒绝覆盖：{destination}")
        return destination, False

    try:
        adb(device, "shell", "mkdir", "-p", "/data/misc/user/0/cacerts-added")
        with tempfile.TemporaryDirectory(prefix="p1-613-ca-") as temp_dir:
            staged = pathlib.Path(temp_dir) / f"{digest}.0"
            shutil.copyfile(cert, staged)
            adb(device, "push", str(staged), destination)
        adb(device, "shell", "chown", "1000:1000", destination)
        adb(device, "shell", "chmod", "644", destination)
        adb(device, "shell", "restorecon", "-F", destination)
        adb(device, "reboot")
        wait_for_device(device)
    except Exception:
        adb(device, "root", check=False)
        wait_for_device(device)
        adb(device, "shell", "rm", "-f", destination, check=False)
        adb(device, "reboot", check=False)
        wait_for_device(device)
        raise
    return destination, True


def remove_user_ca(device: str, destination: str, added: bool) -> None:
    if not added:
        return
    adb(device, "root", check=False)
    wait_for_device(device)
    adb(device, "shell", "rm", "-f", destination, check=False)
    adb(device, "reboot", check=False)
    wait_for_device(device)
    adb(device, "unroot", check=False)


def wait_for_port(host: str, port: int, timeout: float = 45.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1):
                return
        except OSError:
            time.sleep(0.25)
    raise TimeoutError(f"WebDAV 服务未在 {timeout:g} 秒内监听 {host}:{port}")


def instrumentation_xml(output: str, class_name: str, phase: str, elapsed: float, result_code: int) -> ET.Element:
    cases: list[ET.Element] = []
    status: dict[str, str] = {}
    started_at: float | None = None
    for line in output.splitlines():
        prefix = "INSTRUMENTATION_STATUS: "
        if line.startswith(prefix):
            key, separator, value = line[len(prefix):].partition("=")
            if separator:
                status[key] = value
            continue
        if line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.rsplit(" ", 1)[-1])
            if code == 1:
                started_at = time.time()
            elif code in (0, -2, -3):
                case = ET.Element(
                    "testcase",
                    {
                        "classname": status.get("class", class_name),
                        "name": status.get("test", "unknown"),
                        "time": f"{max(0.0, time.time() - (started_at or time.time())):.3f}",
                    },
                )
                if code == -2:
                    failure = ET.SubElement(case, "failure", {"message": status.get("shortMsg", "instrumented test failed")})
                    failure.text = status.get("stack", status.get("stream", ""))
                elif code == -3:
                    skipped = ET.SubElement(case, "skipped", {"message": status.get("shortMsg", "skipped")})
                    skipped.text = status.get("stream", "")
                cases.append(case)
                started_at = None
            status = {}

    failures = sum(case.find("failure") is not None for case in cases)
    errors = sum(case.find("error") is not None for case in cases)
    skipped_count = sum(case.find("skipped") is not None for case in cases)
    suite = ET.Element(
        "testsuite",
        {
            "name": f"{class_name}#{phase}",
            "tests": str(len(cases)),
            "failures": str(failures),
            "errors": str(errors),
            "skipped": str(skipped_count),
            "time": f"{elapsed:.3f}",
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        },
    )
    ET.SubElement(suite, "properties")
    suite.extend(cases)
    if result_code != 0 and failures == 0 and errors == 0:
        case = ET.SubElement(suite, "testcase", {"classname": class_name, "name": "instrumentation_process", "time": "0"})
        failure = ET.SubElement(case, "failure", {"message": f"instrumentation exit code {result_code}"})
        failure.text = output[-12000:]
        suite.set("tests", str(len(cases) + 1))
        suite.set("failures", "1")
    return suite


def run_instrumentation(device: str, class_name: str, phase: str, run_id: str, artifacts: pathlib.Path) -> None:
    command = [
        str(ADB), "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", class_name,
        "-e", "p1_613_phase", phase,
        "-e", "p1_613_run_id", run_id,
        f"{TEST_ID}/{RUNNER}",
    ]
    start = time.time()
    result = run(command, check=False, capture=True)
    elapsed = time.time() - start
    output = result.stdout or ""
    raw_path = artifacts / f"{phase}-{class_name.rsplit('.', 1)[-1]}.instrumentation.txt"
    raw_path.write_text(output, encoding="utf-8")
    exit_code = 0 if result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in output else (result.returncode or 1)
    suite = instrumentation_xml(output, class_name, phase, elapsed, exit_code)
    xml_path = artifacts / f"{phase}-{class_name.rsplit('.', 1)[-1]}.xml"
    ET.indent(suite, space="  ")
    ET.ElementTree(suite).write(xml_path, encoding="utf-8", xml_declaration=True)
    print(output, end="")
    if result.returncode != 0 or "INSTRUMENTATION_CODE: -1" not in output:
        raise RuntimeError(f"instrumentation failed; transcript and XML saved under {artifacts}")
    if suite.get("tests") == "0":
        raise RuntimeError(f"instrumentation reported zero tests: {class_name} {phase}")
    if suite.get("failures") != "0" or suite.get("errors") != "0":
        raise RuntimeError(f"instrumentation reported failing tests: {xml_path}")


def force_stop_and_verify(device: str, app_id: str) -> None:
    adb(device, "shell", "am", "force-stop", app_id)
    deadline = time.time() + 15
    while time.time() < deadline:
        result = adb(device, "shell", "pidof", app_id, capture=True, check=False)
        if result.returncode != 0 or not (result.stdout or "").strip():
            return
        time.sleep(0.25)
    raise TimeoutError(f"force-stop 未清除目标进程：{app_id}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", default="emulator-5554", help="隔离 AVD serial；拒绝物理设备")
    args = parser.parse_args()
    device = args.device
    if not device.startswith("emulator-"):
        raise SystemExit("拒绝对物理设备运行：本流程会安装 debug APK、force-stop 并重启 AVD")
    if not ADB.is_file():
        raise SystemExit(f"找不到 adb：{ADB}")
    ensure_device(device)

    artifacts = REPO / ".ci-artifacts" / "remediation-20261015" / "p1-613"
    artifacts.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex[:12]
    cert = REPO / "tools" / "local-sync" / "certs" / "cert.pem"
    destination = ""
    ca_added = False
    server = None
    reverse_added = False
    try:
        build = run([str(GRADLE), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--no-daemon", "--max-workers=1"], capture=True)
        (artifacts / "build.log").write_text(build.stdout or "", encoding="utf-8")
        print((build.stdout or "")[-4000:])

        digest = certificate_hash(cert)
        destination, ca_added = install_user_ca(device, cert, digest)
        with tempfile.TemporaryDirectory(prefix="p1-613-webdav-") as temp_dir:
            env = dict(os.environ)
            env.update(
                WEBDAV_HOST="127.0.0.1",
                WEBDAV_PORT=str(PORT),
                WEBDAV_USER=WEBDAV_USER,
                WEBDAV_PASSWORD=WEBDAV_PASSWORD,
                WEBDAV_ROOT=str(pathlib.Path(temp_dir) / "root"),
                WEBDAV_CERT=str(cert),
                WEBDAV_KEY=str(REPO / "tools" / "local-sync" / "certs" / "key.pem"),
            )
            server = subprocess.Popen(
                [sys.executable, str(REPO / "tools" / "local-sync" / "run_webdav.py")],
                cwd=REPO / "tools" / "local-sync",
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            wait_for_port("127.0.0.1", PORT)
            adb(device, "reverse", f"tcp:{PORT}", f"tcp:{PORT}")
            reverse_added = True

            app_apk = REPO / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
            test_apk = REPO / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
            adb(device, "install", "-r", str(app_apk))
            adb(device, "install", "-r", "-t", str(test_apk))
            for phase_name, class_name in PHASES:
                run_instrumentation(device, class_name, "prepare", run_id, artifacts)
                force_stop_and_verify(device, APP_ID)
                run_instrumentation(device, class_name, "resume", run_id, artifacts)

        print(f"P1-613 设备 XML 与 instrumentation 原始回执：{artifacts}")
        return 0
    finally:
        if reverse_added:
            adb(device, "reverse", "--remove", f"tcp:{PORT}", check=False)
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
        if destination:
            remove_user_ca(device, destination, ca_added)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"[P1-613 lifecycle] ERROR: {type(error).__name__}: {error}", file=sys.stderr)
        raise
