#!/usr/bin/env python3
"""对本地 HTTPS WebDAV 做真实协议自测（PROPFIND/PUT/GET/MOVE/DELETE）。
使用 certs/cert.pem 建立真实 TLS 信任（模拟 App 端证书验证）。
可在 verify 模式下直接运行，或由 smoke 脚本导入 run_checks()。

ISSUE-P3-650：**不得**回退到源码固定测试口令。凭据只能由环境提供
（`WEBDAV_USER` / `WEBDAV_PASSWORD`，联调脚本按次随机生成）；缺失即拒绝运行。
"""
import base64
import os
import ssl
import http.client

HOST = os.environ.get("WEBDAV_HOST", "localhost")
PORT = int(os.environ.get("WEBDAV_PORT", "9443"))
USER = os.environ.get("WEBDAV_USER")
PASSWORD = os.environ.get("WEBDAV_PASSWORD")
CERT = os.path.abspath(os.environ.get("WEBDAV_CERT", "certs/cert.pem"))


def _require_credentials():
    if not USER or not PASSWORD:
        raise SystemExit("[FATAL] 请通过 WEBDAV_USER / WEBDAV_PASSWORD 提供联调凭据（不再有固定默认口令）")


def _conn():
    ctx = ssl.create_default_context(cafile=CERT)
    return http.client.HTTPSConnection(HOST, PORT, context=ctx, timeout=15)


def _req(method, path, body=None, headers=None):
    auth = base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
    h = {"Authorization": f"Basic {auth}", "Depth": "0"}
    if headers:
        h.update(headers)
    conn = _conn()
    conn.request(method, path, body=body, headers=h)
    r = conn.getresponse()
    data = r.read().decode("utf-8", "replace")
    etag = r.getheader("ETag")
    conn.close()
    return r.status, etag, data


def run_checks():
    _require_credentials()
    ok = True

    def check(name, cond, extra=""):
        nonlocal ok
        ok = ok and cond
        print(f"[{'PASS' if cond else 'FAIL'}] {name} {extra}")

    st, _, _ = _req("PROPFIND", "/", headers={"Content-Type": "application/xml"},
                    body='<D:propfind xmlns:D="DAV:"><D:prop><D:getetag/></D:prop></D:propfind>')
    check("PROPFIND 根返回 207/200", st in (207, 200), f"HTTP {st}")

    st, etag, _ = _req("PUT", "/test.kdbx", body=b"kdbx-bytes-payload",
                       headers={"Content-Type": "application/octet-stream"})
    check("PUT 创建文件", st in (201, 204), f"HTTP {st} ETag={etag}")

    st, _, data = _req("GET", "/test.kdbx")
    check("GET 回下载内容一致", st == 200 and data == "kdbx-bytes-payload", f"HTTP {st} len={len(data)}")

    st, _, body = _req("PROPFIND", "/test.kdbx",
                        body='<D:propfind xmlns:D="DAV:"><D:prop><D:getetag/><D:getcontentlength/></D:prop></D:propfind>')
    # WebDAV ETag 经 PROPFIND 响应体 <getetag> 返回（HTTP 头不一定携带），App 端即解析响应体
    import re
    m = re.search(r"<[^>]*getetag[^>]*>(.*?)</", body, re.S)
    etag_val = m.group(1).strip().strip('"') if m else ""
    check("PROPFIND 文件含 ETag(响应体)", st in (207, 200) and etag_val, f"HTTP {st} ETag={etag_val}")

    st, _, _ = _req("MOVE", "/test.kdbx",
                    headers={"Destination": f"https://{HOST}:{PORT}/test-moved.kdbx", "Overwrite": "T"})
    check("MOVE 原子重命名", st in (201, 204), f"HTTP {st}")

    st, _, _ = _req("DELETE", "/test-moved.kdbx")
    check("DELETE 清理", st in (204, 200, 404), f"HTTP {st}")

    print("RESULT:", "ALL PASS" if ok else "HAS FAILURE")
    return ok


if __name__ == "__main__":
    import sys
    sys.exit(0 if run_checks() else 1)
