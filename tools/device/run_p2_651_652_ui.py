#!/usr/bin/env python3
"""ISSUE-P2-651/652 真实 UI + 真实 HTTPS 接收方验收夹具。

## 为什么需要它

两项问题的验收都要求「**真实接收方**上的请求计数」，而不是假 Provider 的调用次数：

- P2-651：保存失败（http 端点被拒 / 封印失败 / 全零）时**一个请求都不得发出**，且改 HTTPS 后
  **不重新输入秘密**即可保存成功；
- P2-652：A 的测连在途时改存 B，**仍只触达 A**，且 A 的迟到结论**不得**把 B 标成已验证。

假件能证明编排层的路由，但证明不了「线上真的没有包」。本夹具起一个**真实 TLS** WebDAV 接收方
（仓库内 `tools/local-sync/certs/cert.pem`，其 SAN 含 `DNS:localhost` / `IP:127.0.0.1`，debug 变体
的 `network_security_config` 已把它作为信任锚），把每个请求的 method / path / Basic 用户名记进
内存日志，并额外提供**控制端点**供设备用例查询与放行：

    GET  /__control/state      -> {"requests": [...], "held": bool, "hold": bool}
    POST /__control/hold       -> 之后到达的 PROPFIND 在进入日志后阻塞
    POST /__control/release    -> 放行被阻塞的 PROPFIND
    POST /__control/reset      -> 清空日志与门闩

控制端点**不要求** Basic 认证（它只暴露合成测试数据），因此用例可以用自己的 OkHttp 客户端
（信任同一张仓库证书）查询，不需要把私钥带进设备。

## 与 `run_webdav.py` 的分工

`tools/local-sync/run_webdav.py` 是**联调服务器**（wsgidav，供人手动验证）；本脚本是**一次性
验收夹具**：进程内计数、可控门闩、只监听 127.0.0.1、退出即销毁临时目录。不复用前者是为了
不把「计数/门闩」这类测试专用逻辑塞进联调工具（`tools/local-sync/` 另有维护者）。

## 用法

    python tools/device/run_p2_651_652_ui.py --device emulator-5554

前置：`app-debug.apk` 与 `app-debug-androidTest.apk` 已由主代理构建（本脚本**不**跑 Gradle）。

## 边界

- 只证明「Android 设备 → 本机 TLS 接收方」这一跳；不证明真实商业 WebDAV 服务端行为。
- 证书与私钥取仓库 `tools/local-sync/certs/`；私钥不入库（`.gitignore`），缺失即 fail-fast。
- 每个场景独立进程内日志 + 独立临时数据目录，互不污染。
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import pathlib
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

REPO = pathlib.Path(__file__).resolve().parents[2]
CERT = REPO / "tools" / "local-sync" / "certs" / "cert.pem"
KEY = REPO / "tools" / "local-sync" / "certs" / "key.pem"
TEST_CLASS = "com.keepasskey.app.ui.settings.CloudSyncFormUiDeviceTest"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
APP_ID = "com.keepasskey.debug"
TEST_ID = "com.keepasskey.debug.test"

SCENARIOS = {
    "save-failure-zero-request": "httpEndpointRejectedSendsNoRequest",
    "retry-without-retyping": "correctedHttpsRetriesWithSameDraft",
    "in-flight-a-then-save-b": "inFlightTestKeepsAAndDoesNotVerifyB",
    "leave-page-wipes-draft": "leavingPageWipesDraftAndResetsBusy",
}


class ReceiverState:
    """请求日志与 PROPFIND 门闩（跨线程共享）。"""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.requests: list[dict] = []
        self.files: dict[str, bytes] = {}
        self.hold = False
        self.held = False
        self.release = threading.Event()
        self.release.set()

    def record(self, method: str, path: str, user: str) -> None:
        with self.lock:
            self.requests.append({"method": method, "path": path, "user": user})

    def snapshot(self) -> dict:
        with self.lock:
            return {"requests": list(self.requests), "held": self.held, "hold": self.hold}

    def reset(self) -> None:
        with self.lock:
            self.requests.clear()
            self.files.clear()
            self.hold = False
            self.held = False
        self.release.set()


def basic_user(header: str | None) -> str:
    if not header or not header.lower().startswith("basic "):
        return ""
    try:
        decoded = base64.b64decode(header.split(" ", 1)[1]).decode("utf-8", "replace")
    except Exception:
        return ""
    return decoded.split(":", 1)[0]


def etag_of(payload: bytes) -> str:
    import hashlib

    return '"' + hashlib.md5(payload).hexdigest() + '"'


def multistatus(path: str, payload: bytes | None, is_dir: bool) -> bytes:
    props = ["<d:getlastmodified>Mon, 21 Oct 2026 00:00:00 GMT</d:getlastmodified>"]
    if is_dir:
        props.append("<d:resourcetype><d:collection/></d:resourcetype>")
    else:
        assert payload is not None
        props.append(f"<d:getcontentlength>{len(payload)}</d:getcontentlength>")
        props.append(f"<d:getetag>{etag_of(payload)}</d:getetag>")
        props.append("<d:resourcetype/>")
    body = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<d:multistatus xmlns:d="DAV:"><d:response>'
        f"<d:href>{path}</d:href><d:propstat><d:prop>{''.join(props)}</d:prop>"
        "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
    )
    return body.encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    state: ReceiverState
    user: str
    password: str

    def log_message(self, fmt: str, *args) -> None:  # 静默：证据在 state 里，不刷控制台
        return

    # ---- 控制端点 ----

    def _control(self, method: str) -> bool:
        parsed = urlparse(self.path)
        if not parsed.path.startswith("/__control/"):
            return False
        action = parsed.path[len("/__control/"):]
        if method == "GET" and action == "state":
            self._json(self.state.snapshot())
            return True
        if method == "POST" and action == "reset":
            self.state.reset()
            self._json({"ok": True})
            return True
        if method == "POST" and action == "hold":
            with self.state.lock:
                self.state.hold = True
                self.state.held = False
            self.state.release.clear()
            self._json({"ok": True})
            return True
        if method == "POST" and action == "release":
            with self.state.lock:
                self.state.hold = False
                self.state.held = False
            self.state.release.set()
            self._json({"ok": True})
            return True
        self._json({"error": "unknown control action"}, status=404)
        return True

    def _json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---- WebDAV ----

    def _authorized(self) -> bool:
        return basic_user(self.headers.get("Authorization")) == self.user

    def _unauthorized(self) -> None:
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="p2-651"')
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _path(self) -> str:
        return unquote(urlparse(self.path).path)

    def _body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def _respond(self, status: int, body: bytes = b"", headers: dict | None = None) -> None:
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_PROPFIND(self) -> None:  # noqa: N802 - http.server 约定
        if self._control("GET"):
            return
        if not self._authorized():
            self.state.record("PROPFIND", self._path(), "")
            self._unauthorized()
            return
        path = self._path()
        self.state.record("PROPFIND", path, self.user)
        with self.state.lock:
            should_hold = self.state.hold
            if should_hold:
                self.state.held = True
        if should_hold:
            self.state.release.wait(timeout=60)
        payload = self.state.files.get(path)
        if path in ("", "/"):
            self._respond(207, multistatus(path or "/", None, True))
        elif payload is None:
            self._respond(404)
        else:
            self._respond(207, multistatus(path, payload, False))

    def do_GET(self) -> None:  # noqa: N802
        if self._control("GET"):
            return
        if not self._authorized():
            self.state.record("GET", self._path(), "")
            self._unauthorized()
            return
        path = self._path()
        self.state.record("GET", path, self.user)
        payload = self.state.files.get(path)
        if payload is None:
            self._respond(404)
        else:
            self._respond(200, payload, {"ETag": etag_of(payload), "Content-Type": "application/octet-stream"})

    def do_PUT(self) -> None:  # noqa: N802
        if self._control("POST"):
            return
        if not self._authorized():
            self.state.record("PUT", self._path(), "")
            self._unauthorized()
            return
        path = self._path()
        payload = self._body()
        self.state.record("PUT", path, self.user)
        self.state.files[path] = payload
        self._respond(201, b"", {"ETag": etag_of(payload)})

    def do_MOVE(self) -> None:  # noqa: N802
        if self._control("POST"):
            return
        if not self._authorized():
            self.state.record("MOVE", self._path(), "")
            self._unauthorized()
            return
        source = self._path()
        destination = unquote(urlparse(self.headers.get("Destination") or "").path)
        self.state.record("MOVE", source, self.user)
        payload = self.state.files.pop(source, None)
        if payload is None:
            self._respond(404)
            return
        self.state.files[destination] = payload
        self._respond(201, b"", {"ETag": etag_of(payload)})

    def do_POST(self) -> None:  # noqa: N802
        # 控制端点（reset / hold / release）走 POST：`http.server` 未实现 do_POST 时会对
        # 该请求直接回 501，设备用例的 setUp 便因「控制端点返回 501」整体失败（实测）。
        if self._control("POST"):
            return
        if not self._authorized():
            self.state.record("POST", self._path(), "")
            self._unauthorized()
            return
        path = self._path()
        self.state.record("POST", path, self.user)
        self._respond(405)

    def do_DELETE(self) -> None:  # noqa: N802
        if self._control("POST"):
            return
        if not self._authorized():
            self.state.record("DELETE", self._path(), "")
            self._unauthorized()
            return
        path = self._path()
        self.state.record("DELETE", path, self.user)
        self.state.files.pop(path, None)
        self._respond(204)


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def start_receiver(state: ReceiverState, user: str, password: str, port: int) -> ThreadingHTTPServer:
    handler = type(
        "BoundHandler",
        (Handler,),
        {"state": state, "user": user, "password": password},
    )
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=str(CERT), keyfile=str(KEY))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def run(command: list[str], *, capture: bool = False, check: bool = True):
    return subprocess.run(
        command,
        capture_output=capture,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=check,
    )


def adb(device: str, *args: str, capture: bool = False, check: bool = True):
    return run(["adb", "-s", device, *args], capture=capture, check=check)


def run_scenario(device: str, port: int, scenario: str, method: str, artifacts: pathlib.Path) -> ET.Element:
    started = time.time()
    command = [
        "adb", "-s", device, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", f"{TEST_CLASS}#{method}",
        "-e", "p2_651_port", str(port),
        "-e", "p2_651_run_id", uuid.uuid4().hex[:8],
        f"{TEST_ID}/{RUNNER}",
    ]
    result = run(command, capture=True, check=False)
    output = (result.stdout or "") + (result.stderr or "")
    elapsed = time.time() - started
    stem = artifacts / f"{scenario}"
    stem.with_suffix(".instrumentation.txt").write_text(output, encoding="utf-8")

    cases: list[ET.Element] = []
    status: dict[str, str] = {}
    case_started = None
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            status[key] = value
            continue
        if line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.rsplit(" ", 1)[-1])
            if code == 1:
                case_started = time.time()
            elif code in (0, -2, -3):
                case = ET.Element(
                    "testcase",
                    {
                        "classname": status.get("class", TEST_CLASS),
                        "name": status.get("test", method),
                        "time": f"{(time.time() - (case_started or time.time())):.3f}",
                    },
                )
                if code != 0:
                    failure = ET.SubElement(case, "failure" if code == -2 else "error")
                    failure.text = status.get("stack", "instrumentation reported failure")
                cases.append(case)
    suite = ET.Element(
        "testsuite",
        {
            "name": f"{TEST_CLASS}#{method}",
            "tests": str(len(cases)),
            "failures": str(sum(1 for c in cases if c.find("failure") is not None)),
            "errors": str(sum(1 for c in cases if c.find("error") is not None)),
            "skipped": str(sum(1 for c in cases if c.find("skipped") is not None)),
            "time": f"{elapsed:.3f}",
        },
    )
    suite.extend(cases)
    ET.indent(suite, space="  ")
    ET.ElementTree(suite).write(stem.with_suffix(".xml"), encoding="utf-8", xml_declaration=True)
    print(output[-4000:], end="")

    if result.returncode != 0 or "INSTRUMENTATION_CODE: -1" not in output:
        raise RuntimeError(f"{scenario}: instrumentation 未正常结束，证据见 {stem}.instrumentation.txt")
    if suite.get("tests") == "0":
        raise RuntimeError(f"{scenario}: 运行了 0 个用例（空转），证据见 {stem}.xml")
    if suite.get("failures") != "0" or suite.get("errors") != "0":
        raise RuntimeError(f"{scenario}: 用例失败，见 {stem}.xml")
    return suite


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    parser.add_argument(
        "--scenarios",
        default=",".join(SCENARIOS),
        help=f"逗号分隔；可选 {', '.join(SCENARIOS)}",
    )
    args = parser.parse_args()

    if not CERT.is_file() or not KEY.is_file():
        print(
            f"[FATAL] 缺少 TLS 材料：{CERT} / {KEY}。私钥不入库，"
            "请按 tools/local-sync/README.md 的 openssl 命令生成。",
            file=sys.stderr,
        )
        return 2

    artifacts = REPO / ".ci-artifacts" / "remediation-20261021" / "p2-651-652"
    artifacts.mkdir(parents=True, exist_ok=True)

    device = args.device
    if run(["adb", "devices"], capture=True).stdout.find(device) < 0:
        print(f"[FATAL] 设备 {device} 不在 adb devices 列表", file=sys.stderr)
        return 2

    port = free_port()
    user = "p2-651-user"
    password = f"Synthetic-{uuid.uuid4().hex[:12]}"
    state = ReceiverState()
    server = start_receiver(state, user, password, port)
    reverse_added = False
    failures: list[str] = []
    try:
        adb(device, "reverse", f"tcp:{port}", f"tcp:{port}")
        reverse_added = True
        for scenario in [s.strip() for s in args.scenarios.split(",") if s.strip()]:
            method = SCENARIOS.get(scenario)
            if method is None:
                raise SystemExit(f"[FATAL] 未知场景：{scenario}")
            state.reset()
            try:
                run_scenario(device, port, scenario, method, artifacts)
                print(f"[OK] {scenario}")
            except RuntimeError as failure:
                failures.append(str(failure))
                print(f"[FAIL] {failure}", file=sys.stderr)
    finally:
        if reverse_added:
            adb(device, "reverse", "--remove", f"tcp:{port}", check=False)
        server.shutdown()
        server.server_close()

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"全部场景通过；证据在 {artifacts}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
