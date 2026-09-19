#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「外部管理器口径」的 KPEX 通行密钥只读 fixture（ISSUE-P2-210 的读取半边）。

## 为什么需要它

`ISSUE-P2-210` 要求通行密钥的 `KPEX_PASSKEY_*` schema 有**官方实现端到端对拍**证据
（`AGENTS.md` 规则 8）。写入半边由 `PasskeyInteropProbeTest` 产出、外部工具读取；
**读取半边**必须反过来：由**非本仓**的实现写出 `.kdbx`，交本仓生产读路径解析。

本脚本用 `pykeepass`（KDBX 读写）+ `cryptography`（PKCS#8 私钥生成，独立于本仓 crypto 模块）
产出该 fixture，**刻意不写任何 `Passkey.*` 扩展键**——那样才能证明本仓
「扩展键缺失时按 PKCS#8 `AlgorithmIdentifier` 的 OID 做纯字节嗅探」的**外部条目兼容路径**
真的可用（这正是 KeePassXC / KeePassDX 产出的条目的形态）。

## 固定测试向量

私钥为**公开的测试向量**（非任何真实凭据），固定不变 ⇒ fixture 可重复生成、断言可硬编码：

- ES256：P-256 标量 `0102…1f20`（顺序递增 32 字节，取值 < n 且非零）；
- Ed25519：**RFC 8032 §7.1 TEST 1** 种子（与 `crypto/src/main/rust/src/tests/passkey_sign_tests.rs`
  同源），其公钥在 RFC 中有公开出处，便于第三方独立核对。

## 用法

```bash
python tools/passkey-interop/make_external_fixture.py            # 生成 / 覆盖 fixture
python tools/passkey-interop/make_external_fixture.py --print    # 只打印期望值（刷新 Kotlin 常量用）
```

生成物（**入库**，读取半边用例与它配套）：

- `database/src/test/resources/fixtures/passkey-interop/pykeepass-kpex.kdbx`
- `database/src/test/resources/fixtures/passkey-interop/FIXTURE.md`（出处、SHA-256、期望值）

## 依赖

`pykeepass >= 4.2`、`cryptography >= 41`（本机实测 4.2.0 / 50.0.0）。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import pathlib
import sys

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec, ed25519
    from pykeepass import PyKeePass, create_database
except ImportError as exc:  # pragma: no cover - 环境缺失时的显式失败
    sys.exit(f"缺少依赖（需 pykeepass / cryptography）：{exc}")

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE_DIR = REPO_ROOT / "database" / "src" / "test" / "resources" / "fixtures" / "passkey-interop"
FIXTURE_DB = FIXTURE_DIR / "pykeepass-kpex.kdbx"
FIXTURE_NOTE = FIXTURE_DIR / "FIXTURE.md"

# ── 固定测试向量（公开，非真实凭据） ─────────────────────────────────────────────
ES256_SCALAR_HEX = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
ED25519_SEED_HEX = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"  # RFC 8032 TEST 1
PRF_SECRET = bytes(range(32))  # 0x00..0x1f，合成材料

RP_ID = "passkey-interop.example"
DB_PASSWORD = "passkey-ext-fixture-2026"
DB_NAME = "ExternalManagerKpexFixture"

# KPEX 键名（KeePassXC / KeePassDX 口径）
K_RP = "KPEX_PASSKEY_RELYING_PARTY"
K_USERNAME = "KPEX_PASSKEY_USERNAME"
K_USER_HANDLE = "KPEX_PASSKEY_USER_HANDLE"
K_CREDENTIAL_ID = "KPEX_PASSKEY_CREDENTIAL_ID"
K_PRIVATE_KEY_PEM = "KPEX_PASSKEY_PRIVATE_KEY_PEM"
K_FLAG_BE = "KPEX_PASSKEY_FLAG_BE"
K_FLAG_BS = "KPEX_PASSKEY_FLAG_BS"
K_PRF = "KPEX_PASSKEY_PRF"

ENTRY_ES256 = "External ES256 Passkey"
ENTRY_ED25519 = "External Ed25519 Passkey"
ES256_USER = "ext-es256@passkey-interop.example"
ED25519_USER = "ext-ed25519@passkey-interop.example"
ES256_CRED_ID = "ZXh0LWtleS1lczI1Ni1jcmVkZW50aWFsLWlk"
ED25519_CRED_ID = "ZXh0LWtleS1lZDI1NTE5LWNyZWRlbnRpYWwtaWQ"
ES256_USER_HANDLE = "ZXh0LXVzZXItaGFuZGxlLWVzMjU2"
ED25519_USER_HANDLE = "ZXh0LXVzZXItaGFuZGxlLWVkMjU1MTk"


def pem_and_public_key(algorithm: str) -> tuple[bytes, bytes]:
    """产出 PKCS#8 PEM（未加密）与该算法下的**公开**公钥字节。

    - ES256：公钥为未压缩点 `0x04 || X || Y`（与本仓 `PasskeyData.publicKeyBase64` 同形态）；
    - Ed25519：公钥为 32 字节原始公钥。

    PEM 与公钥均取自 `cryptography`（OpenSSL 后端）——**独立于本仓** crypto 模块，
    因此「本仓能读出并还原该私钥」构成互操作证据而非自证。
    """
    if algorithm == "ES256":
        private_key = ec.derive_private_key(int(ES256_SCALAR_HEX, 16), ec.SECP256R1())
        public_bytes = private_key.public_key().public_bytes(
            serialization.Encoding.X962,
            serialization.PublicFormat.UncompressedPoint,
        )
    elif algorithm == "Ed25519":
        private_key = ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(ED25519_SEED_HEX))
        public_bytes = private_key.public_key().public_bytes(
            serialization.Encoding.Raw,
            serialization.PublicFormat.Raw,
        )
    else:  # pragma: no cover - 内部调用点恒定
        raise ValueError(f"未支持的算法：{algorithm}")

    pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    return pem, public_bytes


def pem_der(pem: bytes) -> bytes:
    """从 PEM 文本中取出 DER（供 SHA-256 与结构核对；不引入额外依赖）。"""
    lines = [ln.strip() for ln in pem.decode("ascii").splitlines()]
    body = "".join(ln for ln in lines if ln and not ln.startswith("-----"))
    return base64.b64decode(body)


def build_entries() -> list[dict[str, object]]:
    es_pem, es_pub = pem_and_public_key("ES256")
    ed_pem, ed_pub = pem_and_public_key("Ed25519")
    return [
        {
            "title": ENTRY_ES256,
            "userName": ES256_USER,
            "credentialId": ES256_CRED_ID,
            "userHandle": ES256_USER_HANDLE,
            "algorithmId": -7,
            "pem": es_pem,
            "publicKey": es_pub,
            "publicKeyHex": es_pub.hex(),
            "prf": base64.b64encode(PRF_SECRET).decode("ascii"),
        },
        {
            "title": ENTRY_ED25519,
            "userName": ED25519_USER,
            "credentialId": ED25519_CRED_ID,
            "userHandle": ED25519_USER_HANDLE,
            "algorithmId": -8,
            "pem": ed_pem,
            "publicKey": ed_pub,
            "publicKeyHex": ed_pub.hex(),
            "prf": None,
        },
    ]


def generate() -> None:
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    if FIXTURE_DB.exists():
        FIXTURE_DB.unlink()

    kp = create_database(str(FIXTURE_DB), password=DB_PASSWORD)
    group = kp.add_group(kp.root_group, "External Passkeys")

    for spec in build_entries():
        entry = kp.add_entry(group, str(spec["title"]), str(spec["userName"]), "")
        # 保护位严格照 KeePassXC 口径：RP / 用户名 / 标志位为普通属性，
        # UserHandle / CredentialId / 私钥 PEM / PRF 为受保护属性。
        entry.set_custom_property(K_RP, RP_ID, protect=False)
        entry.set_custom_property(K_USERNAME, str(spec["userName"]), protect=False)
        entry.set_custom_property(K_USER_HANDLE, str(spec["userHandle"]), protect=True)
        entry.set_custom_property(K_CREDENTIAL_ID, str(spec["credentialId"]), protect=True)
        entry.set_custom_property(K_PRIVATE_KEY_PEM, spec["pem"].decode("ascii"), protect=True)
        entry.set_custom_property(K_FLAG_BE, "1", protect=False)
        entry.set_custom_property(K_FLAG_BS, "1", protect=False)
        if spec["prf"]:
            entry.set_custom_property(K_PRF, str(spec["prf"]), protect=True)

    kp.save()
    write_note(verify_no_extension_keys())


def verify_no_extension_keys() -> None:
    """自检：fixture 里**不得**出现本仓扩展键（否则「OID 字节嗅探」路径不会被真正走到）。"""
    kp = PyKeePass(str(FIXTURE_DB), password=DB_PASSWORD)
    for entry in kp.entries:
        leaked = [k for k in entry.custom_properties if k.startswith("Passkey.")]
        if leaked:
            raise SystemExit(f"fixture 不得含本仓扩展键，但 {entry.title} 有：{leaked}")


def write_note(_: None) -> None:
    digest = hashlib.sha256(FIXTURE_DB.read_bytes()).hexdigest()
    lines = [
        "# 外部管理器口径通行密钥 fixture（读取半边）",
        "",
        "> 由 `tools/passkey-interop/make_external_fixture.py` 生成，**请勿手工编辑**。",
        "> 出处与用途见 `ISSUE-P2-210` 与 `AGENTS.md` 规则 8。",
        "",
        f"- 库文件：`{FIXTURE_DB.name}`",
        f"- SHA-256：`{digest}`",
        f"- 口令：`{DB_PASSWORD}`",
        f"- 库名：{DB_NAME}",
        "- 生成器：`pykeepass` + `cryptography`（两者均非本仓实现）",
        "- 关键约束：条目**只有** `KPEX_PASSKEY_*`，不含任何 `Passkey.*` 扩展键",
        "  ⇒ 强制本仓走「按 PKCS#8 AlgorithmIdentifier 的 OID 纯字节嗅探」的外部条目路径",
        "",
        "## 期望值（供 `PasskeyInteropExternalFixtureTest` 断言）",
        "",
        "| 条目 | 算法 | RP ID | 用户名 | Credential ID | User Handle | 公钥(hex) | PRF |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for spec in build_entries():
        lines.append(
            "| `{title}` | {alg} ({alg_id}) | `{rp}` | `{user}` | `{cid}` | `{uh}` | `{pk}` | {prf} |".format(
                title=spec["title"],
                alg="ES256" if spec["algorithmId"] == -7 else "Ed25519",
                alg_id=spec["algorithmId"],
                rp=RP_ID,
                user=spec["userName"],
                cid=spec["credentialId"],
                uh=spec["userHandle"],
                pk=spec["publicKeyHex"],
                prf="有（32 字节）" if spec["prf"] else "无",
            )
        )
    lines += [
        "",
        "## 固定测试向量（公开，非真实凭据）",
        "",
        f"- ES256 私钥标量：`{ES256_SCALAR_HEX}`",
        f"- Ed25519 私钥种子：`{ED25519_SEED_HEX}`（RFC 8032 §7.1 TEST 1）",
        f"- PRF 秘密：`{PRF_SECRET.hex()}`（0x00..0x1f）",
        "",
        "## 重新生成",
        "",
        "```bash",
        "python tools/passkey-interop/make_external_fixture.py",
        "```",
        "",
    ]
    FIXTURE_NOTE.write_text("\n".join(lines), encoding="utf-8")


def print_expected() -> None:
    for spec in build_entries():
        print(f"title={spec['title']}")
        print(f"algorithmId={spec['algorithmId']}")
        print(f"publicKeyHex={spec['publicKeyHex']}")
        print(f"pemSha256={hashlib.sha256(pem_der(spec['pem'])).hexdigest()}")
        print(f"pemBase64={base64.b64encode(spec['pem']).decode('ascii')}")
        print()


def main() -> None:
    parser = argparse.ArgumentParser(description="生成 KPEX 通行密钥外部 fixture")
    parser.add_argument("--print", action="store_true", help="只打印期望值，不写文件")
    args = parser.parse_args()
    if args.print:
        print_expected()
    else:
        generate()
        print(f"已生成：{FIXTURE_DB}")
        print(f"说明文件：{FIXTURE_NOTE}")


if __name__ == "__main__":
    main()
