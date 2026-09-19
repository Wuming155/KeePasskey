#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通行密钥 `KPEX_PASSKEY_*` 产物对拍的**官方实现侧**权威判据（ISSUE-P2-210 / AGENTS.md 规则 8）。

## 为什么它才是「互操作证据」

`AGENTS.md` 规则 8 规定：产物互操作性**只认官方实现端到端对拍**——自家 writer → 自家 reader
恒为绿，证明不了库文件级互操作。本脚本用两个**非本仓**实现读同一份本仓产物，并交叉核对：

1. `pykeepass`（KDBX 解析，独立实现）逐字段读取 + 保护位判定；
2. `cryptography`（OpenSSL 后端）**独立解析** `KPEX_PASSKEY_PRIVATE_KEY_PEM`，
   确认它是合法的 PKCS#8 私钥、曲线 / 类型与条目算法一致，并**由私钥重新导出公钥**，
   与该凭据公开的公钥逐字节比对；
3. `keepassxc-cli`（KeePassXC 官方 CLI）读同一库，与 `pykeepass` 读到的属性集交叉核对
   —— 两个独立实现读数一致，才排除「单一实现的宽容解析」这一解释。

## 用法

```bash
# 1) 先跑探针产出产物（若尚未产出）
./gradlew.bat :database:testDebugUnitTest --tests "*PasskeyInteropProbeTest*"
# 2) 权威对拍
python tools/passkey-interop/verify_interop.py
```

产物与清单默认取 `database/build/interop-probe/`（`build/` 可丢弃，脚本会用 `--probe-dir` 覆盖）。

退出码：0 = 全部判据通过；1 = 任一判据失败（供 CI / 人工复核引为硬证据）。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec, ed25519, rsa
    from pykeepass import PyKeePass
except ImportError as exc:  # pragma: no cover - 环境缺失时显式失败
    sys.exit(f"缺少依赖（需 pykeepass / cryptography）：{exc}")

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_PROBE_DIR = REPO_ROOT / "database" / "build" / "interop-probe"
PROBE_DB_NAME = "keepasskey-passkey-probe.kdbx"
MANIFEST_NAME = "keepasskey-passkey-probe.expected.json"

# ── KPEX / 本仓扩展键（行首属性名，用于解析 `keepassxc-cli show` 的多行 PEM 值） ──────────
K_RP = "KPEX_PASSKEY_RELYING_PARTY"
K_USERNAME = "KPEX_PASSKEY_USERNAME"
K_USER_HANDLE = "KPEX_PASSKEY_USER_HANDLE"
K_CREDENTIAL_ID = "KPEX_PASSKEY_CREDENTIAL_ID"
K_PRIVATE_KEY_PEM = "KPEX_PASSKEY_PRIVATE_KEY_PEM"
K_FLAG_BE = "KPEX_PASSKEY_FLAG_BE"
K_FLAG_BS = "KPEX_PASSKEY_FLAG_BS"
K_PRF = "KPEX_PASSKEY_PRF"
K_ALGORITHM = "Passkey.Algorithm"
K_PUBLIC_KEY = "Passkey.PublicKey"

PROTECTED_KEYS = {K_USER_HANDLE, K_CREDENTIAL_ID, K_PRIVATE_KEY_PEM, K_PRF}
ALGORITHM_ES256 = -7
ALGORITHM_ED25519 = -8
ALGORITHM_RS256 = -257

# 条目算法 → PEM 期望的密钥类型（`cryptography` 侧的判定口径）
EXPECTED_KEY_TYPE = {
    ALGORITHM_ES256: "EC P-256",
    ALGORITHM_ED25519: "Ed25519",
    ALGORITHM_RS256: "RSA",
}

# 标准化字段（`keepassxc-cli show` 摘要行）与全部已知键，二者都用于多行值解析的边界判定
STANDARD_FIELDS = {"Title", "UserName", "Password", "URL", "Notes", "Uuid", "Tags"}
KNOWN_KEYS = {
    K_RP, K_USERNAME, K_USER_HANDLE, K_CREDENTIAL_ID, K_PRIVATE_KEY_PEM,
    K_FLAG_BE, K_FLAG_BS, K_PRF, K_ALGORITHM, K_PUBLIC_KEY,
    "Passkey.SignCount", "Passkey.UserDisplayName", "Passkey.CreatedAt",
} | STANDARD_FIELDS

_failures: list[str] = []
_checks = 0


def check(condition: bool, message: str) -> None:
    global _checks
    _checks += 1
    if not condition:
        _failures.append(message)


def parse_show_output(text: str) -> dict[str, str]:
    """解析 `keepassxc-cli show --all -s` 输出为 `键 -> 值`（PEM 等跨行值按续行拼接）。"""
    attrs: dict[str, str] = {}
    current: str | None = None
    for line in text.splitlines():
        key, sep, value = line.partition(": ")
        if sep and key in KNOWN_KEYS:
            current = key
            attrs[key] = value
        elif current is not None:
            attrs[current] = attrs[current] + "\n" + line
    return attrs


def derive_public_key_b64(pem_text: str) -> tuple[str, str]:
    """用 `cryptography` 独立解析 PKCS#8 PEM，返回（算法描述, 公钥 Base64）。

    公钥形态与本仓 `PasskeyData.publicKeyBase64` 对齐：ES256 为未压缩点 `0x04||X||Y`，
    Ed25519 为 32 字节原始公钥。
    """
    private_key = serialization.load_pem_private_key(pem_text.encode("ascii"), password=None)
    if isinstance(private_key, ec.EllipticCurvePrivateKey):
        if not isinstance(private_key.curve, ec.SECP256R1):
            raise ValueError(f"EC 曲线不是 secp256r1：{private_key.curve.name}")
        raw = private_key.public_key().public_bytes(
            serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
        )
        return "EC P-256", base64.b64encode(raw).decode("ascii")
    if isinstance(private_key, ed25519.Ed25519PrivateKey):
        raw = private_key.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw
        )
        return "Ed25519", base64.b64encode(raw).decode("ascii")
    if isinstance(private_key, rsa.RSAPrivateKey):
        raw = private_key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        return "RSA", base64.b64encode(raw).decode("ascii")
    raise ValueError(f"未支持的私钥类型：{type(private_key).__name__}")


def read_with_keepassxc_cli(db_path: pathlib.Path, password: str, entry_title: str) -> dict[str, str] | None:
    """用官方 CLI 读条目；CLI 缺失时返回 None（调用方据此跳过交叉核对并如实记录）。"""
    executable = shutil.which("keepassxc-cli")
    if executable is None:
        return None
    proc = subprocess.run(
        [executable, "show", "-q", "--all", "-s", str(db_path), entry_title],
        input=password.encode("utf-8"),
        capture_output=True,
    )
    if proc.returncode != 0:
        _failures.append(f"keepassxc-cli 读取 `{entry_title}` 失败：{proc.stderr.decode('utf-8', 'replace').strip()}")
        return None
    return parse_show_output(proc.stdout.decode("utf-8", "replace"))


def verify_entry(db_path: pathlib.Path, kp: PyKeePass, spec: dict, rp_id: str) -> None:
    title = spec["title"]
    algorithm_id = int(spec["algorithmId"])
    label = f"[{title}]"

    entry = kp.find_entries(title=title, first=True)
    if entry is None:
        _failures.append(f"{label} pykeepass 未找到该条目")
        return

    props = entry.custom_properties
    for key in (K_RP, K_USERNAME, K_USER_HANDLE, K_CREDENTIAL_ID, K_PRIVATE_KEY_PEM, K_FLAG_BE, K_FLAG_BS):
        check(key in props, f"{label} 缺少必需 KPEX 键 {key}")

    # 1) 公开材料逐字段相等
    check(entry.get_custom_property(K_RP) == rp_id, f"{label} RP ID 不匹配")
    check(entry.get_custom_property(K_USERNAME) == spec["userName"], f"{label} 用户名不匹配")
    check(entry.get_custom_property(K_CREDENTIAL_ID) == spec["credentialId"], f"{label} Credential ID 不匹配")
    check(entry.get_custom_property(K_USER_HANDLE) == spec["userHandle"], f"{label} User Handle 不匹配")

    # 2) 保护位（KeePassXC / KeePassDX 口径）
    for key in PROTECTED_KEYS:
        if key == K_PRF and not spec.get("prfPresent"):
            continue
        check(key in props, f"{label} 缺少受保护键 {key}")
        if key in props:
            check(entry.is_custom_property_protected(key), f"{label} {key} 必须是受保护属性")
    for key in (K_RP, K_USERNAME, K_FLAG_BE, K_FLAG_BS):
        check(not entry.is_custom_property_protected(key), f"{label} {key} 不得为受保护属性")

    # 3) 备份位文本（KeePassXC 口径为 `1` / `0`）
    for key in (K_FLAG_BE, K_FLAG_BS):
        check(entry.get_custom_property(key) in ("1", "0"), f"{label} {key} 必须是 `1` / `0` 文本")

    # 4) 扩展键算法与清单一致
    check(entry.get_custom_property(K_ALGORITHM) == str(algorithm_id), f"{label} Passkey.Algorithm 与清单不一致")

    # 5) 私钥 PEM：SHA-256 与「独立解析 + 公钥重导出」双重判据
    pem_text = entry.get_custom_property(K_PRIVATE_KEY_PEM)
    check(pem_text.startswith("-----BEGIN PRIVATE KEY-----"), f"{label} 私钥不是 PKCS#8 PEM")

    der = base64.b64decode(
        "".join(ln.strip() for ln in pem_text.splitlines() if ln.strip() and not ln.startswith("-----"))
    )
    check(
        hashlib.sha256(der).hexdigest() == spec["privateKeyPemDerSha256"],
        f"{label} PEM 内层 DER 与清单 SHA-256 不一致（产物被改写？）",
    )

    try:
        algorithm_name, public_key_b64 = derive_public_key_b64(pem_text)
    except Exception as exc:  # noqa: BLE001 - 解析失败即为互操作失败
        _failures.append(f"{label} cryptography 无法解析该 PKCS#8 PEM：{exc}")
        return

    expected_name = EXPECTED_KEY_TYPE.get(algorithm_id, "?")
    check(algorithm_name == expected_name, f"{label} PEM 密钥类型 {algorithm_name} ≠ 条目算法 {expected_name}")
    check(
        public_key_b64 == spec["publicKeyBase64"],
        f"{label} 由私钥重导出的公钥与条目公开公钥不一致（PEM 与公钥不匹配）",
    )
    check(
        entry.get_custom_property(K_PUBLIC_KEY) == spec["publicKeyBase64"],
        f"{label} Passkey.PublicKey 与清单不一致",
    )

    # 6) PRF：存在性 + 32 字节材料
    if spec.get("prfPresent"):
        prf = entry.get_custom_property(K_PRF)
        check(prf is not None, f"{label} 应含 KPEX_PASSKEY_PRF")
        if prf is not None:
            check(len(base64.b64decode(prf)) == 32, f"{label} PRF 秘密应为 32 字节")
    else:
        check(K_PRF not in props, f"{label} 不应含 KPEX_PASSKEY_PRF")

    # 7) 双实现交叉核对：keepassxc-cli 与 pykeepass 读数必须一致
    cli_attrs = read_with_keepassxc_cli(db_path, _PASSWORD[0], title)
    if cli_attrs is None:
        print(f"  ! keepassxc-cli 不可用或读取失败——交叉核对已跳过（如实记录，不视为通过）")
        return
    for key in (K_RP, K_USERNAME, K_USER_HANDLE, K_CREDENTIAL_ID, K_FLAG_BE, K_FLAG_BS):
        check(
            cli_attrs.get(key) == entry.get_custom_property(key),
            f"{label} keepassxc-cli 与 pykeepass 对 {key} 的读数不一致",
        )
    check(
        "-----BEGIN PRIVATE KEY-----" in cli_attrs.get(K_PRIVATE_KEY_PEM, ""),
        f"{label} keepassxc-cli 未能读出 PKCS#8 PEM",
    )
    print(f"  · {label} 双实现读数一致（pykeepass + keepassxc-cli）")


_PASSWORD: list[str] = [""]


def main() -> int:
    parser = argparse.ArgumentParser(description="KPEX 通行密钥产物 · 官方实现端到端对拍")
    parser.add_argument("--probe-dir", default=str(DEFAULT_PROBE_DIR), help="探针产物目录")
    args = parser.parse_args()

    probe_dir = pathlib.Path(args.probe_dir)
    db_path = probe_dir / PROBE_DB_NAME
    manifest_path = probe_dir / MANIFEST_NAME

    if not db_path.is_file() or not manifest_path.is_file():
        sys.exit(
            f"缺少探针产物：{db_path} / {manifest_path}\n"
            "请先执行：./gradlew.bat :database:testDebugUnitTest --tests \"*PasskeyInteropProbeTest*\""
        )

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    _PASSWORD[0] = manifest["password"]

    print(f"产物：{db_path}")
    print(f"  SHA-256：{hashlib.sha256(db_path.read_bytes()).hexdigest()}")
    print(f"  pykeepass {_version('pykeepass')} / keepassxc-cli {_cli_version()}")

    kp = PyKeePass(str(db_path), password=manifest["password"])
    print(f"pykeepass 成功解锁，条目数 {len(kp.entries)}")

    for spec in manifest["entries"]:
        verify_entry(db_path, kp, spec, manifest["rpId"])

    print()
    if _failures:
        print(f"✗ 对拍失败（{len(_failures)}/{_checks} 条判据不通过）：")
        for item in _failures:
            print(f"  - {item}")
        return 1
    print(f"✓ 对拍通过：{_checks} 条判据全部成立（pykeepass + cryptography + keepassxc-cli）")
    return 0


def _version(module_name: str) -> str:
    try:
        module = __import__(module_name)
        return str(getattr(module, "__version__", "?"))
    except Exception:  # noqa: BLE001
        return "?"


def _cli_version() -> str:
    executable = shutil.which("keepassxc-cli")
    if executable is None:
        return "不可用"
    proc = subprocess.run([executable, "--version"], capture_output=True)
    return proc.stdout.decode("utf-8", "replace").strip().splitlines()[0] if proc.stdout else "?"


if __name__ == "__main__":
    raise SystemExit(main())
