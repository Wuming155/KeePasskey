#!/usr/bin/env python3
"""WebDAV 冒烟：独立数据目录 + 按次随机凭据 + 仅 loopback，跑真实协议自测。

ISSUE-P3-650 起不再有源码固定口令，也不再复用 `webdav-data` 目录；
退出只结束本脚本启动的 PID。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import lab_services  # noqa: E402
import verify_webdav  # noqa: E402


def main():
    lab = lab_services.Lab(bind_host=lab_services.LOOPBACK, webdav_port=19443,
                           s3_port=19010, console_port=19011)
    print(f"[smoke] 独立目录 {lab.root}（凭据按次随机，值不落日志）")
    try:
        lab.start_webdav()
        if not lab_services.wait_port(lab.host_for_client(), lab.webdav_port, timeout=30):
            print("[FATAL] WebDAV 端口未在预期时间内就绪")
            return 1
        # 自测脚本只读环境变量：此处把本轮随机凭据与独立根目录传进去
        os.environ["WEBDAV_HOST"] = lab.host_for_client()
        os.environ["WEBDAV_PORT"] = str(lab.webdav_port)
        os.environ["WEBDAV_USER"] = lab.webdav_user
        os.environ["WEBDAV_PASSWORD"] = lab.webdav_password
        os.environ["WEBDAV_CERT"] = lab_services.CERT_PATH
        verify_webdav.HOST = lab.host_for_client()
        verify_webdav.PORT = lab.webdav_port
        verify_webdav.USER = lab.webdav_user
        verify_webdav.PASSWORD = lab.webdav_password
        verify_webdav.CERT = lab_services.CERT_PATH
        print("[smoke] WebDAV 已就绪，开始协议自测")
        return 0 if verify_webdav.run_checks() else 1
    finally:
        lab.stop_all()
        lab.cleanup()


if __name__ == "__main__":
    sys.exit(main())
