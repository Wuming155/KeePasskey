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


def _redact(text):
    """**纵深防御**：若子进程输出意外带出口令，回显前擦除。

    注意：CodeQL 的 `py/clear-text-logging-sensitive-data` **不把** `str.replace` 视为净化器，
    因此不能依赖本函数来阻断该规则——真正的手段是让口令根本不进入任何 `print` 路径（见 `run`）。
    本函数只用于「子进程输出」这一条非污点路径的兜底。
    """
    for secret in (PASSWORD,):
        if secret:
            text = text.replace(secret, "***")
    return text


def run(cmd, display, **kw):
    """执行 `cmd`；`display` 是**调用方显式构造、不含任何凭据**的命令摘要，仅用于回显。

    刻意不把 `cmd`（可能内嵌同步凭据）传入 `print`：CodeQL 不把字符串替换识别为净化器，
    「先擦除再回显」仍会被判为明文日志，故改为结构性隔离——
    机密值只出现在传给 `subprocess` 的实参里，日志只接受调用方提供的安全摘要。
    """
    print("  $", display)
    r = subprocess.run(cmd, cwd=HERE, capture_output=True, text=True, **kw)
    out = (r.stdout or "") + (r.stderr or "")
    for line in out.splitlines():
        print("    >", _redact(line))
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
        s, _ = run(
            [MC, "--insecure", "alias", "set", alias, ENDPOINT, USER, PASSWORD],
            display=f"{MC} --insecure alias set {alias} {ENDPOINT} {USER} ***",
        )
        ok = ok and s
        s, o = run(
            [MC, "--insecure", "mb", f"{alias}/{BUCKET}"],
            display=f"{MC} --insecure mb {alias}/{BUCKET}",
        )
        ok = ok and (s or "already exists" in o or "exists" in o)
        # 上传
        with open(os.path.join(HERE, "_s3_tmp.txt"), "w") as f:
            f.write("s3-kdbx-bytes-payload")
        s, _ = run(
            [MC, "--insecure", "cp", "_s3_tmp.txt", f"{alias}/{BUCKET}/vault.kdbx"],
            display=f"{MC} --insecure cp _s3_tmp.txt {alias}/{BUCKET}/vault.kdbx",
        )
        ok = ok and s
        # 下载并比对
        s, _ = run(
            [MC, "--insecure", "cp", f"{alias}/{BUCKET}/vault.kdbx", "_s3_dl.txt"],
            display=f"{MC} --insecure cp {alias}/{BUCKET}/vault.kdbx _s3_dl.txt",
        )
        ok = ok and s
        with open(os.path.join(HERE, "_s3_dl.txt")) as f:
            dl = f.read()
        ok = ok and (dl == "s3-kdbx-bytes-payload")
        print(f"[{'PASS' if dl == 's3-kdbx-bytes-payload' else 'FAIL'}] 下载字节一致 (len={len(dl)})")
        # 删除
        s, _ = run(
            [MC, "--insecure", "rm", f"{alias}/{BUCKET}/vault.kdbx"],
            display=f"{MC} --insecure rm {alias}/{BUCKET}/vault.kdbx",
        )
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
