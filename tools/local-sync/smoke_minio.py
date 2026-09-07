#!/usr/bin/env python3
"""一体化 MinIO(S3) 冒烟：子进程拉起 HTTPS MinIO，等待端口就绪后通过 mc 执行
建桶 / 上传 / 下载 / 删除，验证 S3 兼容存储的真实 HTTPS + SigV4 通路。"""
import os
import socket
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BIN = os.path.join(HERE, "bin")
MINIO = os.path.join(BIN, "minio.exe")
MC = os.path.join(BIN, "mc.exe")
CERTS_DIR = os.path.join(HERE, "certs", "minio")
DATA_DIR = os.path.join(HERE, "minio-data")
API_PORT = 9000
CONSOLE_PORT = 9001
USER = "tester"
PASSWORD = "tester1234"
BUCKET = "keepasskey-test"
ENDPOINT = "https://localhost:9000"


def wait_port(host, port, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=2):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def run(cmd, **kw):
    print("  $", " ".join(cmd))
    r = subprocess.run(cmd, cwd=HERE, capture_output=True, text=True, **kw)
    out = (r.stdout or "") + (r.stderr or "")
    for line in out.splitlines():
        print("    >", line)
    return r.returncode == 0, out


def main():
    os.makedirs(DATA_DIR, exist_ok=True)
    env = dict(os.environ)
    env["MINIO_ROOT_USER"] = USER
    env["MINIO_ROOT_PASSWORD"] = PASSWORD
    env["MINIO_CERTS_DIR"] = CERTS_DIR

    proc = subprocess.Popen(
        [MINIO, "server", DATA_DIR, "--address", f":{API_PORT}",
         "--console-address", f":{CONSOLE_PORT}"],
        cwd=HERE, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1,
    )
    try:
        if not wait_port("localhost", API_PORT, timeout=40):
            print("[FATAL] MinIO API 端口未就绪")
            for line in proc.stdout:
                print("  >", line.rstrip())
            return 1

        ok = True
        # mc 对自签名证书用 --insecure 跳过校验（仅测试客户端侧；App 端以真实证书信任）
        alias = "local"
        s, _ = run([MC, "--insecure", "alias", "set", alias, ENDPOINT, USER, PASSWORD])
        ok = ok and s
        s, o = run([MC, "--insecure", "mb", f"{alias}/{BUCKET}"])
        ok = ok and (s or "already exists" in o or "exists" in o)
        # 上传
        with open(os.path.join(HERE, "_s3_tmp.txt"), "w") as f:
            f.write("s3-kdbx-bytes-payload")
        s, _ = run([MC, "--insecure", "cp", "_s3_tmp.txt", f"{alias}/{BUCKET}/vault.kdbx"])
        ok = ok and s
        # 下载并比对
        s, _ = run([MC, "--insecure", "cp", f"{alias}/{BUCKET}/vault.kdbx", "_s3_dl.txt"])
        ok = ok and s
        with open(os.path.join(HERE, "_s3_dl.txt")) as f:
            dl = f.read()
        ok = ok and (dl == "s3-kdbx-bytes-payload")
        print(f"[{'PASS' if dl == 's3-kdbx-bytes-payload' else 'FAIL'}] 下载字节一致 (len={len(dl)})")
        # 删除
        s, _ = run([MC, "--insecure", "rm", f"{alias}/{BUCKET}/vault.kdbx"])
        ok = ok and s
        print("RESULT:", "ALL PASS" if ok else "HAS FAILURE")
        return 0 if ok else 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()
        for t in ("_s3_tmp.txt", "_s3_dl.txt"):
            try:
                os.remove(os.path.join(HERE, t))
            except OSError:
                pass


if __name__ == "__main__":
    sys.exit(main())
