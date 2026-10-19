#!/usr/bin/env python3
# KeePasskey 本地 HTTPS WebDAV 测试服务（仅供本地联调，禁止公网暴露）。
# 依赖：wsgidav + cheroot + pyopenssl（pip install wsgidav cheroot pyopenssl）。
# 证书：certs/cert.pem + certs/key.pem（localhost 自签名，SAN 含 localhost / 127.0.0.1）。
import os
import sys
import time
from pathlib import Path

from cheroot import wsgi
from cheroot.ssl.builtin import BuiltinSSLAdapter
from wsgidav import util
from wsgidav.fs_dav_provider import FilesystemProvider
from wsgidav.wsgidav_app import WsgiDAVApp

PORT = int(os.environ.get("WEBDAV_PORT", "9443"))
HOST = os.environ.get("WEBDAV_HOST", "127.0.0.1")
USER = os.environ.get("WEBDAV_USER")
PASSWORD = os.environ.get("WEBDAV_PASSWORD")
DAV_ROOT = os.path.abspath(os.environ.get("WEBDAV_ROOT", "webdav-data"))
CERT = os.path.abspath(os.environ.get("WEBDAV_CERT", "certs/cert.pem"))
KEY = os.path.abspath(os.environ.get("WEBDAV_KEY", "certs/key.pem"))

if not USER or not PASSWORD:
    sys.exit("[FATAL] 请通过 WEBDAV_USER 和 WEBDAV_PASSWORD 提供本地联调凭据")

if not os.path.isfile(CERT) or not os.path.isfile(KEY):
    sys.exit(f"[FATAL] 缺失证书文件：{CERT} / {KEY}")

os.makedirs(DAV_ROOT, exist_ok=True)

provider = FilesystemProvider(DAV_ROOT, readonly=False)
config = {
    "host": HOST,
    "port": PORT,
    "provider_mapping": {"/": provider},
    "http_authenticator": {
        "domain_controller": None,  # None -> simple_dc.SimpleDomainController
        "accept_basic": True,
        "accept_digest": False,
        "trusted_auth_header": None,
    },
    "simple_dc": {
        "user_mapping": {
            "*": {
                USER: {"password": PASSWORD, "root": DAV_ROOT, "perms": "rw"},
            }
        }
    },
    "property_manager": True,
    "lock_storage": True,
    "verbose": 1,
}

app = WsgiDAVApp(config)


class ConditionalWriteRace:
    """Optional deterministic If-Match race for device acceptance tests."""

    def __init__(self, wrapped_app):
        self.wrapped_app = wrapped_app
        self.marker = os.environ.get("WEBDAV_RACE_MARKER")
        self.remote_path = os.environ.get("WEBDAV_RACE_PATH")
        self.triggered = os.environ.get("WEBDAV_RACE_TRIGGERED")
        self.race_move_number = int(os.environ.get("WEBDAV_RACE_MOVE_NUMBER", "0"))
        self.delay_move_number = int(os.environ.get("WEBDAV_DELAY_MOVE_NUMBER", "0"))
        self.delay_seconds = float(os.environ.get("WEBDAV_DELAY_SECONDS", "0"))
        self.delay_marker = os.environ.get("WEBDAV_DELAY_MARKER")
        self.delay_probe_after_race_seconds = float(
            os.environ.get("WEBDAV_DELAY_PROBE_AFTER_RACE_SECONDS", "0")
        )
        self.delay_probe_seconds = float(os.environ.get("WEBDAV_DELAY_PROBE_SECONDS", "0"))
        self.probe_delay_marker = os.environ.get("WEBDAV_PROBE_DELAY_MARKER")
        self.probe_request_marker = os.environ.get("WEBDAV_PROBE_REQUEST_MARKER")
        self.move_count = 0
        self.race_happened = False

    def __call__(self, environ, start_response):
        if environ.get("REQUEST_METHOD") == "PROPFIND" and self.probe_request_marker:
            marker = Path(self.probe_request_marker)
            if not marker.exists():
                marker.parent.mkdir(parents=True, exist_ok=True)
                marker.write_text("production HTTPS metadata request received\n", encoding="utf-8")
        if (
            environ.get("REQUEST_METHOD") == "PROPFIND"
            and self.delay_probe_seconds > 0
            and self.probe_delay_marker
        ):
            marker = Path(self.probe_delay_marker)
            if not marker.exists():
                marker.parent.mkdir(parents=True, exist_ok=True)
                marker.write_text("production probe request is in flight\n", encoding="utf-8")
                print("[TEST-SLOW] delaying production probe request", flush=True)
                time.sleep(self.delay_probe_seconds)
        if (
            environ.get("REQUEST_METHOD") == "PROPFIND"
            and self.race_happened
            and self.delay_probe_after_race_seconds > 0
            and self.delay_marker
        ):
            marker = Path(self.delay_marker)
            if not marker.exists():
                marker.parent.mkdir(parents=True, exist_ok=True)
                marker.write_text("delaying the 412 metadata re-probe\n", encoding="utf-8")
                print("[TEST-SLOW] delaying 412 metadata re-probe", flush=True)
                time.sleep(self.delay_probe_after_race_seconds)
        if environ.get("REQUEST_METHOD") == "MOVE":
            self.move_count += 1
            if self.delay_move_number and self.move_count == self.delay_move_number:
                if self.delay_marker:
                    marker = Path(self.delay_marker)
                    marker.parent.mkdir(parents=True, exist_ok=True)
                    marker.write_text(
                        f"delaying MOVE {self.move_count} for {self.delay_seconds:g}s\n",
                        encoding="utf-8",
                    )
                time.sleep(self.delay_seconds)
        marker_is_active = bool(self.marker and Path(self.marker).is_file())
        numbered_race_is_active = self.race_move_number > 0 and self.move_count == self.race_move_number
        if marker_is_active or numbered_race_is_active:
            print(
                f"[TEST-RACE] method={environ.get('REQUEST_METHOD')} path={environ.get('PATH_INFO')}",
                flush=True,
            )
        if (
            self.remote_path
            and environ.get("REQUEST_METHOD") == "MOVE"
            and (marker_is_active or numbered_race_is_active)
        ):
            root = Path(DAV_ROOT).resolve()
            target = (root / self.remote_path.lstrip("/")).resolve()
            if target.is_relative_to(root):
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"synthetic remote revision C inserted before conditional MOVE")
                self.race_happened = True
                if self.triggered:
                    Path(self.triggered).write_text("conditional MOVE race returned HTTP 412\n", encoding="utf-8")
                print("[TEST-RACE] returning explicit HTTP 412", flush=True)
                start_response("412 Precondition Failed", [("Content-Length", "0")])
                return [b""]
        return self.wrapped_app(environ, start_response)


app = ConditionalWriteRace(app)

server = wsgi.Server(
    bind_addr=(HOST, PORT),
    wsgi_app=app,
    server_name=f"KeePasskey-Test-WebDAV/{wsgi.Server.version}",
)
server.ssl_adapter = BuiltinSSLAdapter(CERT, KEY)
print(f"[WebDAV] HTTPS 监听 https://{HOST}:{PORT}  root={DAV_ROOT}")
# 刻意不回显口令（CodeQL py/clear-text-logging-sensitive-data）：只提示凭据来源与账号，
# 实际口令仅由 WEBDAV_PASSWORD 环境变量提供。
print(f"[WebDAV] 账号 {USER}（口令取自 WEBDAV_PASSWORD 环境变量，按安全策略不回显）")

try:
    server.start()
except KeyboardInterrupt:
    print("\n[WebDAV] 收到 Ctrl-C，停止。")
finally:
    server.stop()
