"""静态实验室 RP（`ISSUE-P3-339` 浏览器半环，走**公网可信证书**，零设备篡改）。

只有两件事：把自跑页面交给浏览器，以及收下页面 POST 回来的结局。
判据（是否唤醒）由页面上的 `navigator.credentials` 结果 + 本应用 logcat 两侧共同确定，
本服务**不做**密码学验证（那是 `/api/*` 那套本地 RP 的活，见 rp_server.py；
那条路要自建 CA，已被实测判为「不真实」，保留但不作为唤醒判据的来源）。

    python tools/passkey-phish-lab/static_rp.py --port 8788
    cloudflared tunnel --url http://127.0.0.1:8788        # 每次给一个随机 *.trycloudflare.com

`trycloudflare.com` 在随仓 PSL 私有段在册 ⇒ 每个隧道主机**各自**是一个可注册域，
两条隧道即「两个互不为后缀的可注册域」，正是仿冒矩阵需要的形状。
"""

from __future__ import annotations

import argparse
import json
import threading
from datetime import datetime
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WEB_ROOT = Path(__file__).resolve().parent / "web"
_lock = threading.Lock()


class Handler(SimpleHTTPRequestHandler):
    """`GET /*` 发页面（含 `/`），`POST /report` 落一行读数。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WEB_ROOT), **kwargs)

    def log_message(self, fmt: str, *args) -> None:
        pass

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length") or "0")
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError as exc:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(str(exc).encode())
            return
        row = {
            "ts": datetime.now().isoformat(timespec="seconds"),
            "host": self.headers.get("Host"),
            "origin": payload.get("origin"),
            "mode": payload.get("mode"),
            "rp": payload.get("rp"),
            "outcome": payload.get("outcome"),
            "errorName": payload.get("errorName"),
            "detail": str(payload.get("detail"))[:220],
            "ms": payload.get("ms"),
        }
        with _lock:
            print("READ " + json.dumps(row, ensure_ascii=False), flush=True)
        self.send_response(204)
        self.end_headers()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8788)
    args = parser.parse_args()
    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"静态 RP 监听 127.0.0.1:{args.port}（页面 web/index.html）")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
