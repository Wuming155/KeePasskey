"""生成仿冒域唤醒实验室用的根 CA 与服务端证书，并可一键注入 AVD 的系统信任锚。

**仅实验室用途**：密钥随仓库忽略（见 `.gitignore` 的 `tools/passkey-phish-lab/certs/`），
CA 只签实验室域名，禁止用于任何真实站点。

域名口径（与 `docs/ACTIVE_ISSUES.md` `ISSUE-P3-339` 一致）：TLD 必须选在**随仓 PSL 在册**的
`xyz` 上——`.test` / `.invalid` / `.local` 不在册，而本仓 `PublicSuffixList` 对未知 TLD
按「不可注册」fail-closed ⇒ 真域也醒不过来，负向读数就毫无意义。
"""

from __future__ import annotations

import argparse
import datetime as dt
import ipaddress
import subprocess
import sys
from pathlib import Path

import idna
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

CERT_DIR = Path(__file__).resolve().parent / "certs"

# 同形异码域：拉丁 r + 西里尔 р（U+0440）⇒ 浏览器显示成近乎同名，A-label 才是真身份
HOMOGLYPH_UNICODE = "rр.testlab.xyz"


def lab_names() -> list[str]:
    """实验室全部 DNS 名（**U-label 形态**，即人在地址栏里看到、也是 RP 页里要填的 rpId）。"""
    return [
        "rp.testlab.xyz",                       # 正向：真域
        "sub.rp.testlab.xyz",                   # 正向：同 eTLD+1 的子域（规范允许）
        "phish.testlab.xyz",                    # 负向：完全另一个域
        "rp.testlab.xyz.phish.testlab.xyz",     # 负向：把真域堆在后缀里
        HOMOGLYPH_UNICODE,                      # 负向：同形异码（真域的仿冒）
    ]


def ascii_lab_names() -> list[str]:
    """上面每个名的 **A-label（punycode）形态**——证书 SAN 与 hosts 解析都只认这个形态
    （`cryptography` 直接拒收 U-label：`DNSName values should be passed as an A-label string`）。"""
    out: list[str] = []
    for name in lab_names():
        try:
            ascii_name = idna.encode(name, uts46=True).decode("ascii")
        except idna.IDNAError as exc:            # 实验室域名写错要立刻响，不静默跳过
            raise SystemExit(f"域名 {name!r} 无法 IDNA 归一：{exc}") from exc
        if ascii_name not in out:
            out.append(ascii_name)
    return out


def _not_ahead(days: int) -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=days)


def _key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def build(force: bool = False) -> None:
    cert_path = CERT_DIR / "server.pem"
    if cert_path.exists() and not force:
        print(f"已存在 {cert_path}（--force 可重建）")
        return
    CERT_DIR.mkdir(parents=True, exist_ok=True)

    ca_key, leaf_key = _key(), _key()
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "KeePasskey Phish Lab CA")])
    ca_cert = (
        x509.CertificateBuilder()
        .subject_name(ca_name).issuer_name(ca_name)
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(dt.datetime.now(dt.timezone.utc))
        .not_valid_after(_not_ahead(3650))
        .add_extension(x509.BasicConstraints(True, 0), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=False, content_commitment=False, key_encipherment=False,
                data_encipherment=False, key_agreement=False, key_cert_sign=True, crl_sign=True,
                encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .sign(ca_key, hashes.SHA256())
    )

    sans: list[x509.GeneralName] = [x509.DNSName(n) for n in ascii_lab_names()]
    # 模拟器看宿主的地址 = 10.0.2.2；宿主自己读 /status 走 127.0.0.1 ⇒ 两个 IP 都要进 SAN，
    # 这样 run_matrix.py 可以用**完整**的证书校验（含主机名），不必关 check_hostname
    for ip in ("10.0.2.2", "127.0.0.1"):
        sans.append(x509.IPAddress(ipaddress.ip_address(ip)))
    leaf = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "rp.testlab.xyz")]))
        .issuer_name(ca_name)
        .public_key(leaf_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(dt.datetime.now(dt.timezone.utc))
        .not_valid_after(_not_ahead(825))
        .add_extension(x509.BasicConstraints(False, None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True, content_commitment=False, key_encipherment=True,
                data_encipherment=False, key_agreement=False, key_cert_sign=False, crl_sign=False,
                encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(x509.SubjectAlternativeName(sans), critical=False)
        .sign(ca_key, hashes.SHA256())
    )

    def dump(target: Path, data: bytes, private: bool = False) -> None:
        target.write_bytes(data)
        target.chmod(0o600 if private else 0o644)

    dump(CERT_DIR / "ca.pem", ca_cert.public_bytes(serialization.Encoding.PEM))
    dump(CERT_DIR / "ca.key", ca_key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()),
        private=True)
    dump(CERT_DIR / "server.pem", leaf.public_bytes(serialization.Encoding.PEM))
    dump(CERT_DIR / "server.key", leaf_key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()),
        private=True)
    print("写入：")
    for name in ("ca.pem", "ca.key", "server.pem", "server.key"):
        print("  ", CERT_DIR / name)
    print("SAN 全集：")
    for name in sans:
        print("  ", name.value)


def ca_hash_old() -> str:
    """Android 系统锚目录用的 OpenSSL `subject_hash_old`（Git for Windows 自带 openssl）。"""
    out = subprocess.run(
        ["openssl", "x509", "-subject_hash_old", "-in", str(CERT_DIR / "ca.pem"), "-noout"],
        capture_output=True, text=True, check=True,
    )
    return out.stdout.strip()


def install(serial: str) -> None:
    """把 CA 装进 AVD 的**系统**信任锚（需 -writable-system + adb root + remount 已完成）。"""
    digest = ca_hash_old()
    target = f"/system/etc/security/cacerts/{digest}.0"
    adb = ["adb", "-s", serial]
    subprocess.run(adb + ["root"], capture_output=True)
    subprocess.run(adb + ["push", str(CERT_DIR / "ca.pem"), f"/data/local/tmp/{digest}.0"],
                   check=True, capture_output=True)
    for cmd in (f"mv /data/local/tmp/{digest}.0 {target}",
                f"chmod 644 {target}",
                f"restorecon -R /system/etc/security/cacerts"):
        proc = subprocess.run(adb + ["shell", cmd], capture_output=True, text=True)
        if proc.returncode != 0:
            sys.exit(f"注入失败（{cmd}）：{proc.stderr.strip() or proc.stdout.strip()}")
    print(f"已注入系统锚：{target}")
    listed = subprocess.run(adb + ["shell", f"ls -l {target}"], capture_output=True, text=True)
    print(listed.stdout.strip())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="覆盖已有证书")
    parser.add_argument("--install", metavar="SERIAL", help="注入到指定设备的系统信任锚")
    args = parser.parse_args()
    build(force=args.force)
    if args.install:
        install(args.install)
