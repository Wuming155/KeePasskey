#!/usr/bin/env python3
"""一体化 WebDAV 冒烟：子进程拉起 HTTPS WebDAV，等待端口就绪后执行真实协议自测，最后回收。"""
import os
import socket
import subprocess
import sys
import time

import verify_webdav

PY = sys.executable
HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 9443


def wait_port(host, port, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=2):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def main():
    proc = subprocess.Popen([PY, os.path.join(HERE, "run_webdav.py")],
                            cwd=HERE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, bufsize=1)
    try:
        if not wait_port("localhost", PORT):
            print("[FATAL] WebDAV 端口未在预期时间内就绪")
            for line in proc.stdout:
                print("  >", line.rstrip())
            return 1
        print("[smoke] WebDAV 已就绪，开始协议自测")
        ok = verify_webdav.run_checks()
        return 0 if ok else 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())
