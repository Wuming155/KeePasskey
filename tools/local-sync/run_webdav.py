#!/usr/bin/env python3
# KeePasskey 本地 HTTPS WebDAV 测试服务（仅供本地联调，禁止公网暴露）。
# 依赖：wsgidav + cheroot + pyopenssl（pip install wsgidav cheroot pyopenssl）。
# 证书：certs/cert.pem + certs/key.pem（localhost 自签名，SAN 含 localhost / 127.0.0.1）。
import os
import sys

from cheroot import wsgi
from cheroot.ssl.builtin import BuiltinSSLAdapter
from wsgidav import util
from wsgidav.fs_dav_provider import FilesystemProvider
from wsgidav.wsgidav_app import WsgiDAVApp

PORT = int(os.environ.get("WEBDAV_PORT", "9443"))
HOST = os.environ.get("WEBDAV_HOST", "0.0.0.0")
USER = os.environ.get("WEBDAV_USER", "tester")
PASSWORD = os.environ.get("WEBDAV_PASSWORD", "tester123")
DAV_ROOT = os.path.abspath(os.environ.get("WEBDAV_ROOT", "webdav-data"))
CERT = os.path.abspath(os.environ.get("WEBDAV_CERT", "certs/cert.pem"))
KEY = os.path.abspath(os.environ.get("WEBDAV_KEY", "certs/key.pem"))

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

server = wsgi.Server(
    bind_addr=(HOST, PORT),
    wsgi_app=app,
    server_name=f"KeePasskey-Test-WebDAV/{wsgi.Server.version}",
)
server.ssl_adapter = BuiltinSSLAdapter(CERT, KEY)
print(f"[WebDAV] HTTPS 监听 https://{HOST}:{PORT}  root={DAV_ROOT}")
print(f"[WebDAV] 账号 {USER} / {PASSWORD}（Basic Auth）")

try:
    server.start()
except KeyboardInterrupt:
    print("\n[WebDAV] 收到 Ctrl-C，停止。")
finally:
    server.stop()
