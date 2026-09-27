"""极简 CBOR（RFC 8949）只读解码器——只为读懂 WebAuthn 的 attestationObject 与 COSE 公钥。

为什么不用 `cbor2`：本机无该依赖，而本实验室只需「取到 authData 字段与 COSE 公钥」，
自己写一个**只读、越界即报错**的子集，比引依赖更好控（与 `app/passkey/ByteJsonScanner.kt`
同一取向：不半解、不猜）。

覆盖：unsigned / negative 整数、字节串、文本串、数组、映射、tag（取内层）、简单值。
不覆盖（一律 `CborError`）：不定长流式项（`0x1f`）、半精度浮点之后的扩展、未知主类型 7。
"""

from __future__ import annotations

import hashlib


class CborError(ValueError):
    """解码失败：调用方一律按「验证不通过」处理，禁止吞异常继续。"""


def _need(data: bytes, off: int, size: int) -> None:
    if off + size > len(data):
        raise CborError("CBOR 截断：头部越界")


def _head(data: bytes, off: int) -> tuple[int, int, int]:
    """返回 (major, 附加信息值, 新偏移)。"""
    if off >= len(data):
        raise CborError("CBOR 截断：无首字节")
    ib = data[off]
    major, info = ib >> 5, ib & 0x1F
    off += 1
    if info < 24:
        return major, info, off
    width = {24: 1, 25: 2, 26: 4, 27: 8}.get(info)
    if width is None:
        raise CborError(f"不支持的 CBOR 附加信息 0x{info:02x}")
    _need(data, off, width)
    return major, int.from_bytes(data[off:off + width], "big"), off + width


def decode(data: bytes, off: int = 0) -> tuple[object, int]:
    """解码一个数据项，返回 (值, 消费到的偏移)。"""
    major, val, off = _head(data, off)
    if major == 0:
        return val, off
    if major == 1:
        return -1 - val, off
    if major in (2, 3):
        _need(data, off, val)
        chunk = data[off:off + val]
        return (chunk if major == 2 else chunk.decode("utf-8", "replace")), off + val
    if major == 4:
        items = []
        for _ in range(val):
            item, off = decode(data, off)
            items.append(item)
        return items, off
    if major == 5:
        out = {}
        for _ in range(val):
            key, off = decode(data, off)
            value, off = decode(data, off)
            out[key] = value
        return out, off
    if major == 6:                                    # tag：标签不参与判据，取内层
        return decode(data, off)
    raise CborError(f"不支持的 CBOR 主类型 {major}（简单值/浮点在实验室数据里不该出现）")


def decode_whole(data: bytes) -> object:
    """整段必须恰好是一个数据项（残留字节即拒绝，防半解）。"""
    value, consumed = decode(data, 0)
    if consumed != len(data):
        raise CborError(f"尾部残留 {len(data) - consumed} B 未消费")
    return value


def _as_int(raw: object) -> int:
    return int.from_bytes(raw, "big") if isinstance(raw, bytes) else int(raw)


# ── COSE 密钥与 authData ─────────────────────────────────────────────────────────────────

# COSE 键位（RFC 9052 / WebAuthn §6.1）：公共标签 kty=1 / alg=3 / crv=-1；
# EC2 与 OKP 用 x=-2、y=-3；RSA 复用同一标签位写作 n=-1、e=-3（故 crv 与 n 同键位，按 kty 分支取用）。
COSE_KEY_TYPE, COSE_ALG, COSE_CRV = 1, 3, -1
COSE_X, COSE_Y = -2, -3
COSE_N, COSE_E = COSE_CRV, COSE_Y
CRV_P256, CRV_ED25519 = 1, 6
ALG_ES256, ALG_EDDSA, ALG_RS256 = -7, -8, -257


def cose_to_pem(cose: dict) -> tuple[str, str]:
    """COSE 公钥 → (PEM 文本, 算法名)。未知 kty/crv/alg 直接抛，不静默降级。"""
    from cryptography.hazmat.primitives.asymmetric import ec, ed25519, rsa
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    def to_pem(key) -> str:
        return key.public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo).decode()

    kty, alg, crv = cose.get(COSE_KEY_TYPE), cose.get(COSE_ALG), cose.get(COSE_CRV)
    if kty == 1 and crv == CRV_P256 and alg == ALG_ES256:
        point = b"\x04" + cose[COSE_X] + cose[COSE_Y]
        return to_pem(ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), point)), "ES256"
    if kty == 1 and crv == CRV_ED25519 and alg == ALG_EDDSA:
        return to_pem(ed25519.Ed25519PublicKey.from_public_bytes(cose[COSE_X])), "EdDSA"
    if kty == 3 and alg == ALG_RS256:
        numbers = rsa.RSAPublicNumbers(_as_int(cose[COSE_E]), _as_int(cose[COSE_N]))
        return to_pem(numbers.public_key()), "RS256"
    raise CborError(f"未知 COSE 密钥：kty={kty} alg={alg} crv={crv}")


def parse_auth_data(auth: bytes, rp_id: str) -> dict:
    """读 authenticatorData；AT 置位时再解 aaguid / credentialId / COSE 公钥。"""
    if len(auth) < 37:
        raise CborError("authData 短于 37 B")
    flags = auth[32]
    out = {
        "rpIdHash": auth[:32],
        "expectedRpIdHash": hashlib.sha256(rp_id.encode()).digest(),
        "up": bool(flags & 0x01),
        "uv": bool(flags & 0x04),
        "at": bool(flags & 0x40),
        "signCount": int.from_bytes(auth[33:37], "big"),
    }
    if not out["at"]:
        return out
    rest = auth[37:]
    if len(rest) < 18:
        raise CborError("AT 置位但 attestedCredentialData 不足 18 B")
    cred_id_len = int.from_bytes(rest[16:18], "big")
    out["aaguid"] = rest[:16]
    out["credentialId"] = rest[18:18 + cred_id_len]
    cose_bytes = rest[18 + cred_id_len:]
    cose = decode_whole(cose_bytes)
    if not isinstance(cose, dict):
        raise CborError("COSE 公钥不是映射")
    out["cose"] = cose
    return out
