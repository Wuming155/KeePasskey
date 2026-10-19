#!/usr/bin/env python3
"""KeePasskey 本地 HTTPS 同步联调一体化脚本：
1. 拉起本地 HTTPS WebDAV 服务（wsgidav + 自签名证书）
2. 拉起本地 HTTPS MinIO(S3) 服务并创建测试桶
3. 运行 sync 模块的 LiveSyncServersTest 真实集成测试（针对真实服务）
4. 运行 app 模块的 KDBX 内容比较 / 三方合并语义真实 WebDAV 往返测试
5. 回收所有本地服务

用法：python run_lab.py
前置：pip install wsgidav cheroot pyopenssl；bin/ 下含 minio.exe / mc.exe（首次需联网下载）。
"""
import os
import socket
import subprocess
import sys
import time
import uuid

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
USER = "kp-test-" + uuid.uuid4().hex[:8]
PASSWORD = uuid.uuid4().hex
S3_USER = "kp-s3-" + uuid.uuid4().hex[:8]
S3_PASSWORD = uuid.uuid4().hex
BUCKET = "keepasskey-test"
CERT_PATH = os.path.join(HERE, "certs", "cert.pem")
LIVE_ARTIFACT_DIR = os.path.join(REPO, ".ci-artifacts", "live-sync-kdbx")

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


def _redact(text):
    """**纵深防御**：若子进程输出意外带出口令，回显前擦除。

    注意：CodeQL 的 `py/clear-text-logging-sensitive-data` **不把** `str.replace` 视为净化器，
    因此不能依赖本函数来阻断该规则——真正的手段是让口令根本不进入任何 `print` 路径（见 `run`）。
    本函数只用于「子进程输出」这一条非污点路径的兜底。
    """
    for secret in (PASSWORD, S3_PASSWORD):
        if secret:
            text = text.replace(secret, "***")
    return text


def run(cmd, display):
    """执行 `cmd`；`display` 是**调用方显式构造、不含任何凭据**的命令摘要，仅用于回显。

    刻意不把 `cmd`（可能内嵌同步凭据）传入 `print`：CodeQL 不把字符串替换识别为净化器，
    「先擦除再回显」仍会被判为明文日志，故改为结构性隔离——
    机密值只出现在传给 `subprocess` 的实参里，日志只接受调用方提供的安全摘要。
    """
    print("  $", display)
    r = subprocess.run(cmd, cwd=HERE, capture_output=True, text=True, shell=True)
    out = (r.stdout or "") + (r.stderr or "")
    for line in out.splitlines()[-40:]:
        print("    >", _redact(line))
    return r.returncode == 0, out


def main():
    py = sys.executable
    # WebDAV 与两个测试 JVM 共用一次性本地凭据；不回显口令，且不依赖弱口令默认值。
    os.environ["WEBDAV_HOST"] = "127.0.0.1"
    os.environ["WEBDAV_USER"] = USER
    os.environ["WEBDAV_PASSWORD"] = PASSWORD
    os.environ.setdefault("LIVE_SYNC_TEST", "1")
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
        [MINIO, "server", DATA_DIR, "--address", f"127.0.0.1:{S3_PORT}", "--console-address", f"127.0.0.1:{CONSOLE_PORT}"],
        cwd=HERE, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    PROCS.append(mproc)
    if not wait_port("localhost", S3_PORT, timeout=40):
        print("[FATAL] MinIO 未就绪"); return 1
    print("[lab] MinIO HTTPS 就绪 :9000")

    ok, _ = run(
        f'"{MC}" --insecure alias set local https://localhost:9000 {S3_USER} {S3_PASSWORD}',
        display=f'"{MC}" --insecure alias set local https://localhost:9000 {S3_USER} ***',
    )
    ok2, out = run(
        f'"{MC}" --insecure mb local/{BUCKET}',
        display=f'"{MC}" --insecure mb local/{BUCKET}',
    )
    if not (ok and (ok2 or "exists" in out)):
        print("[WARN] 建桶可能失败，S3 测试可能失败")

    print("[lab] 运行 sync LiveSyncServersTest（针对真实本地服务）...")
    sync_gradle_cmd = (
        f'"{GRADLEW}" :sync:testDebugUnitTest '
        f'--tests "com.keepasskey.sync.LiveSyncServersTest" '
        f'-DliveSyncTest=true -DliveCertPath="{CERT_PATH}"'
    )
    sync_rc = subprocess.run(sync_gradle_cmd, cwd=REPO, shell=True).returncode
    print(f"[lab] sync Gradle 退出码: {sync_rc}")
    if sync_rc != 0:
        return sync_rc

    os.makedirs(LIVE_ARTIFACT_DIR, exist_ok=True)
    print("[lab] 运行 app KDBX 语义真实 WebDAV 往返（到期检测 / 合并 / 下载重开）...")
    app_gradle_cmd = (
        f'"{GRADLEW}" :app:testDebugUnitTest '
        f'--tests "com.keepasskey.app.sync.LiveSyncKdbxSemanticsTest" '
        f'-DliveSyncTest=true -DliveCertPath="{CERT_PATH}" '
        f'-DliveWebdavUrl="https://127.0.0.1:{WEBDAV_PORT}" '
        f'-DliveArtifactDir="{LIVE_ARTIFACT_DIR}"'
    )
    app_rc = subprocess.run(app_gradle_cmd, cwd=REPO, shell=True).returncode
    print(f"[lab] app Gradle 退出码: {app_rc}")
    return app_rc


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
