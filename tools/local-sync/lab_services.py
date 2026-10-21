#!/usr/bin/env python3
"""本地联调服务的**进程与目录生命周期**封装（ISSUE-P3-650）。

为什么单独成模块：`run_lab.py` 原先在 finally 中执行 `taskkill /F /IM minio.exe`，
按**进程名**杀光主机上所有 MinIO——同一台机器上并行的其他联调任务、其他代理的
测试服务都会被误杀。本模块只做一件事：记录**自己启动**的 PID，退出时只终止这些
PID 及其子进程；绝不按镜像名批量结束进程。

目录隔离：每次运行在 `build/local-sync-lab/<run-id>/` 下新建独立的 MinIO 数据目录与
WebDAV 根目录，不复用 `tools/local-sync/minio-data`、`webdav-data` 等既有目录，避免
读到或写坏别的任务留下的数据。

凭据：由调用方按次生成（默认随机高熵），本模块不持有固定默认口令。
"""
import os
import shutil
import socket
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
BIN = os.path.join(HERE, "bin")
MINIO = os.path.join(BIN, "minio.exe")
MC = os.path.join(BIN, "mc.exe")
CERTS_DIR = os.path.join(HERE, "certs", "minio")
CERT_PATH = os.path.join(HERE, "certs", "cert.pem")
KEY_PATH = os.path.join(HERE, "certs", "key.pem")
RUN_ROOT = os.path.join(REPO, "build", "local-sync-lab")

LOOPBACK = "127.0.0.1"
WILDCARD = "0.0.0.0"


def run_dir() -> str:
    """本轮独立运行目录（不复用任何既有服务数据目录）。"""
    path = os.path.join(RUN_ROOT, time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
    os.makedirs(path, exist_ok=True)
    return path


def wait_port(host, port, timeout=40.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1.5):
                return True
        except OSError:
            time.sleep(0.3)
    return False


class Service:
    """自己启动的一个子进程。生命周期只由本对象负责。"""

    def __init__(self, name, argv, env=None, cwd=HERE):
        self.name = name
        self.argv = argv
        self.env = env
        self.cwd = cwd
        self.proc = None

    def start(self):
        self.proc = subprocess.Popen(
            self.argv,
            cwd=self.cwd,
            env=self.env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return self

    @property
    def pid(self):
        return None if self.proc is None else self.proc.pid

    def alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def stop(self, timeout=8.0):
        """只终止本对象启动的 PID；先 terminate，超时再 kill，并等待其真正退出。"""
        if self.proc is None:
            return
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                try:
                    self.proc.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    pass


class Lab:
    """一组本地联调服务（WebDAV + MinIO）及其独占目录。"""

    def __init__(self, bind_host=LOOPBACK, webdav_port=9443, s3_port=9000, console_port=9001):
        self.bind_host = bind_host
        self.webdav_port = webdav_port
        self.s3_port = s3_port
        self.console_port = console_port
        self.root = run_dir()
        self.minio_data = os.path.join(self.root, "minio-data")
        self.webdav_root = os.path.join(self.root, "webdav-data")
        os.makedirs(self.minio_data, exist_ok=True)
        os.makedirs(self.webdav_root, exist_ok=True)
        self.services = []
        self.webdav_user = "kp-dav-" + uuid.uuid4().hex[:10]
        self.webdav_password = uuid.uuid4().hex + uuid.uuid4().hex
        self.s3_user = "kp-s3-" + uuid.uuid4().hex[:10]
        self.s3_password = uuid.uuid4().hex + uuid.uuid4().hex
        self.bucket = "keepasskey-lab"

    # ---- 启动 ----
    def start_webdav(self):
        env = dict(os.environ)
        env.update({
            "WEBDAV_HOST": self.bind_host,
            "WEBDAV_PORT": str(self.webdav_port),
            "WEBDAV_ROOT": self.webdav_root,
            "WEBDAV_USER": self.webdav_user,
            "WEBDAV_PASSWORD": self.webdav_password,
            "WEBDAV_CERT": CERT_PATH,
            "WEBDAV_KEY": KEY_PATH,
        })
        if self.bind_host not in (LOOPBACK, "localhost"):
            # 外部监听必须在服务端显式确认；凭据仍是按次随机（见 Lab.__init__）
            env["WEBDAV_ALLOW_LAN"] = "1"
        svc = Service("webdav", [sys.executable, os.path.join(HERE, "run_webdav.py")], env=env).start()
        self.services.append(svc)
        return svc

    def start_minio(self):
        env = dict(os.environ)
        env.update({
            "MINIO_ROOT_USER": self.s3_user,
            "MINIO_ROOT_PASSWORD": self.s3_password,
            "MINIO_CERTS_DIR": CERTS_DIR,
        })
        svc = Service("minio", [
            MINIO, "server", self.minio_data,
            "--address", f"{self.bind_host}:{self.s3_port}",
            "--console-address", f"{self.bind_host}:{self.console_port}",
        ], env=env).start()
        self.services.append(svc)
        return svc

    def create_bucket(self):
        """mc 别名只落在本进程的临时配置目录，不污染用户全局 mc 配置。"""
        env = dict(os.environ)
        env["MC_CONFIG_DIR"] = os.path.join(self.root, "mc-config")
        endpoint = f"https://{self.host_for_client()}:{self.s3_port}"
        alias = "lab"
        for cmd in (
            [MC, "--insecure", "alias", "set", alias, endpoint, self.s3_user, self.s3_password],
            [MC, "--insecure", "mb", f"{alias}/{self.bucket}"],
        ):
            r = subprocess.run(cmd, cwd=self.root, env=env, capture_output=True, text=True)
            if r.returncode != 0 and "exists" not in (r.stdout + r.stderr):
                return False
        return True

    def host_for_client(self):
        """客户端连接地址：绑定通配时用 loopback（本机用途），否则用绑定地址。"""
        return LOOPBACK if self.bind_host in (WILDCARD, "::", "") else self.bind_host

    def stop_all(self):
        for svc in reversed(self.services):
            svc.stop()

    def cleanup(self, keep=False):
        self.stop_all()
        if not keep:
            shutil.rmtree(self.root, ignore_errors=True)
