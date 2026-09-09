#!/usr/bin/env python3
"""KeePasskey 本地 HTTPS 同步联调一体化脚本：
1. 拉起本地 HTTPS WebDAV 服务（wsgidav + 自签名证书）
2. 拉起本地 HTTPS MinIO(S3) 服务并创建测试桶
3. 运行 sync 模块的 LiveSyncServersTest 真实集成测试（针对真实服务）
4. 回收所有本地服务

用法：python run_lab.py
前置：pip install wsgidav cheroot pyopenssl；bin/ 下含 minio.exe / mc.exe（首次需联网下载）。
"""
import os
import socket
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))      # .../KeePasskey（仓库根）
GRADLEW = os.path.join(REPO, "gradlew.bat")
BIN = os.path.join(HERE, "bin")
MINIO = os.path.join(BIN, "minio.exe")
MC = os.path.join(BIN, "mc.exe")
CERTS_DIR = os.path.join(HERE, "certs", "minio")
DATA_DIR = os.path.join(HERE, "minio-data")
WEBDAV_PORT = 9443
S3_PORT = 9000
CONSOLE_PORT = 9001
USER = "tester"
PASSWORD = "tester1234"
S3_USER = "tester"
S3_PASSWORD = "tester1234"
BUCKET = "keepasskey-test"
CERT_PATH = os.path.join(HERE, "certs", "cert.pem")

PROCS = []


def wait_port(host, port, timeout=40):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=2):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def run(cmd):
    print("  $", cmd)
    r = subprocess.run(cmd, cwd=HERE, capture_output=True, text=True, shell=True)
    out = (r.stdout or "") + (r.stderr or "")
    for line in out.splitlines()[-40:]:
        print("    >", line)
    return r.returncode == 0, out


def main():
    py = sys.executable
    wd = subprocess.Popen([py, os.path.join(HERE, "run_webdav.py")],
                          cwd=HERE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    PROCS.append(wd)
    if not wait_port("localhost", WEBDAV_PORT, timeout=30):
        print("[FATAL] WebDAV 未就绪"); return 1
    print("[lab] WebDAV HTTPS 就绪 :9443")

    os.makedirs(DATA_DIR, exist_ok=True)
    env = dict(os.environ)
    env["MINIO_ROOT_USER"] = S3_USER
    env["MINIO_ROOT_PASSWORD"] = S3_PASSWORD
    env["MINIO_CERTS_DIR"] = CERTS_DIR
    mproc = subprocess.Popen(
        [MINIO, "server", DATA_DIR, "--address", f":{S3_PORT}", "--console-address", f":{CONSOLE_PORT}"],
        cwd=HERE, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    PROCS.append(mproc)
    if not wait_port("localhost", S3_PORT, timeout=40):
        print("[FATAL] MinIO 未就绪"); return 1
    print("[lab] MinIO HTTPS 就绪 :9000")

    ok, _ = run(f'"{MC}" --insecure alias set local https://localhost:9000 {S3_USER} {S3_PASSWORD}')
    ok2, out = run(f'"{MC}" --insecure mb local/{BUCKET}')
    if not (ok and (ok2 or "exists" in out)):
        print("[WARN] 建桶可能失败，S3 测试可能失败")

    print("[lab] 运行 LiveSyncServersTest（针对真实本地服务）...")
    gradle_cmd = (
        f'"{GRADLEW}" :sync:testDebugUnitTest '
        f'--tests "com.keepasskey.sync.LiveSyncServersTest" '
        f'-DliveSyncTest=true -DliveCertPath="{CERT_PATH}"'
    )
    rc = subprocess.run(gradle_cmd, cwd=REPO, shell=True).returncode
    print(f"[lab] Gradle 退出码: {rc}")
    return rc


if __name__ == "__main__":
    rc = 1
    try:
        rc = main()
    finally:
        print("[lab] 回收本地服务...")
        for p in PROCS:
            try:
                p.terminate()
                p.wait(timeout=8)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass
        # 兜底清理可能残留的 minio 进程
        subprocess.run(["taskkill", "/F", "/IM", "minio.exe"], capture_output=True, shell=True)
    sys.exit(rc)
