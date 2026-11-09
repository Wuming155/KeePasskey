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


def generate_vhost_certs(target_dir: str, base_domain: str = "minio.127.0.0.1.nip.io"):
    """生成隔离测试用根 CA、带通配 SAN 的叶证书及 Java PKCS12 信任库（ENG-20261028-22）。

    证书仅用于临时测试目录，绝不安装到系统 CA 根中；JVM 测试进程仅通过
    `-Djavax.net.ssl.trustStore` 定向替换测试进程的信任源，严格维持 HTTPS 与真实主机名校验。
    """
    import datetime
    import ipaddress
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.serialization import pkcs12, BestAvailableEncryption
    from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

    os.makedirs(target_dir, exist_ok=True)

    # 1. Root CA
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "KeePasskey Test Root CA")])
    now = datetime.datetime.now(datetime.timezone.utc)
    ca_cert = (
        x509.CertificateBuilder()
        .subject_name(ca_name)
        .issuer_name(ca_name)
        .public_key(ca_key.public_key())
        .serial_number(1)
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=1))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True, key_cert_sign=True, crl_sign=True,
                content_commitment=False, key_encipherment=False,
                data_encipherment=False, key_agreement=False,
                encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
        .sign(ca_key, hashes.SHA256())
    )

    # 2. Server Leaf Certificate (SAN: base_domain, *.base_domain, localhost, 127.0.0.1)
    leaf_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    leaf_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, base_domain)])
    san = x509.SubjectAlternativeName([
        x509.DNSName(base_domain),
        x509.DNSName(f"*.{base_domain}"),
        x509.DNSName("localhost"),
        x509.IPAddress(ipaddress.IPv4Address("127.0.0.1")),
    ])
    leaf_cert = (
        x509.CertificateBuilder()
        .subject_name(leaf_name)
        .issuer_name(ca_name)
        .public_key(leaf_key.public_key())
        .serial_number(2)
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=1))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(leaf_key.public_key()), critical=False)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True, key_encipherment=True,
                content_commitment=False, data_encipherment=False,
                key_agreement=False, key_cert_sign=False, crl_sign=False,
                encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(san, critical=False)
        .sign(ca_key, hashes.SHA256())
    )

    ca_pem = ca_cert.public_bytes(serialization.Encoding.PEM)
    leaf_pem = leaf_cert.public_bytes(serialization.Encoding.PEM)
    chain_pem = leaf_pem + b"\n" + ca_pem
    key_pem = leaf_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    )

    public_crt_path = os.path.join(target_dir, "public.crt")
    private_key_path = os.path.join(target_dir, "private.key")
    ca_pem_path = os.path.join(target_dir, "ca.pem")
    truststore_path = os.path.join(target_dir, "truststore.p12")

    with open(public_crt_path, "wb") as f:
        f.write(chain_pem)
    with open(private_key_path, "wb") as f:
        f.write(key_pem)
    with open(ca_pem_path, "wb") as f:
        f.write(ca_pem)

    # 3. Java PKCS12 truststore (for test JVM -Djavax.net.ssl.trustStore)
    p12_cert = pkcs12.PKCS12Certificate(ca_cert, friendly_name=b"test-ca")
    p12_bytes = pkcs12.serialize_java_truststore([p12_cert], BestAvailableEncryption(b"changeit"))
    with open(truststore_path, "wb") as f:
        f.write(p12_bytes)

    return {
        "certs_dir": target_dir,
        "public_crt": public_crt_path,
        "private_key": private_key_path,
        "ca_pem": ca_pem_path,
        "truststore_p12": truststore_path,
        "base_domain": base_domain,
    }


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

    def start_minio(self, certs_dir=None, domain=None):
        certs = certs_dir or CERTS_DIR
        env = dict(os.environ)
        env.update({
            "MINIO_ROOT_USER": self.s3_user,
            "MINIO_ROOT_PASSWORD": self.s3_password,
            "MINIO_CERTS_DIR": certs,
        })
        if domain:
            env["MINIO_DOMAIN"] = domain
        svc = Service("minio", [
            MINIO, "server", self.minio_data,
            "--certs-dir", certs,
            "--address", f"{self.bind_host}:{self.s3_port}",
            "--console-address", f"{self.bind_host}:{self.console_port}",
        ], env=env).start()
        self.services.append(svc)
        return svc

    def create_bucket(self, bucket=None):
        """mc 别名只落在本进程的临时配置目录，不污染用户全局 mc 配置。"""
        env = dict(os.environ)
        env["MC_CONFIG_DIR"] = os.path.join(self.root, "mc-config")
        endpoint = f"https://{self.host_for_client()}:{self.s3_port}"
        target_bucket = bucket or self.bucket
        alias = "lab"
        for cmd in (
            [MC, "--insecure", "alias", "set", alias, endpoint, self.s3_user, self.s3_password],
            [MC, "--insecure", "mb", f"{alias}/{target_bucket}"],
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
