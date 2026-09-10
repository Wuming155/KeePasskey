"""KDBX 4 头部 / KDF 变体字典的**只读**解析（零第三方依赖，仅标准库）。

用途（ISSUE-P3-38）：语料生成脚本必须在**落盘前**核对「文件名所声明的 KDF 参数」与
`.kdbx` 文件头中的**真实参数**逐项相等。`crypto/src/test/resources/argon2-interop/README.md`
§6.1 明确规定「文件名就是声明，必须与文件头真实参数逐项相等，否则设备侧用例会硬失败」，
因此这层核对不能靠人工回读。

安全边界：本模块**只读**已解密的头部明文段（KDBX4 的外层 Header 在规范中即明文，
不涉及任何主密码或密钥），不解析、不解密、不触碰载荷。找不到文件/签名不符/字段截断
一律抛异常（fail-closed），绝不返回「部分可信」的结果。
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Dict, Optional, Tuple

# ---- 文件签名与字段 ID（对齐 crypto/src/main/java/.../KdbxConstants.kt） ----
SIGNATURE_1 = 0x9AA2D903
SIGNATURE_2_KDBX = 0xB54BFB67
SUPPORTED_MAJOR_VERSION = 4

FIELD_ID_END = 0
FIELD_ID_CIPHER = 2
FIELD_ID_COMPRESSION = 3
FIELD_ID_KDF_PARAMETERS = 11

# ---- 变体字典元素类型 ----
VAR_TYPE_END = 0x00
VAR_TYPE_UINT32 = 0x04
VAR_TYPE_UINT64 = 0x05
VAR_TYPE_BYTE_ARRAY = 0x42

# ---- 头部字段硬上限（防畸形文件驱动超大分配；对齐 VariantDictionary 的既有裁决值） ----
MAX_FIELD_BYTES = 16 * 1024 * 1024
MAX_VARIANT_ENTRIES = 256
MAX_KEY_LENGTH = 256
MAX_VALUE_LENGTH = 1024 * 1024

CIPHER_UUIDS = {
    "31c1f2e6bf714350be5805216afc5aff": "aes256-cbc",
    "d6038a2b8b6f4cb5a524339a31dbb59a": "chacha20",
    "ad68f29f576f4bb9a36ad47af965346c": "twofish",
}

KDF_UUIDS = {
    "c9d9f39a628a4460bf740d08c18a4fea": "aes-kdf",
    "ef636ddf8c29444b91f7a9a403e30a0c": "argon2d",
    "9e298b1956db4773b23dfc3ec6f0a1e6": "argon2id",
}


class KdbxHeaderError(Exception):
    """头部解析失败（签名不符 / 截断 / 布局非法）。"""


@dataclass(frozen=True)
class KdbxHeaderInfo:
    """从 `.kdbx` 文件头解析出的 KDF 与加密参数（**仅明文元数据**）。"""

    major_version: int
    minor_version: int
    cipher: str
    cipher_uuid_hex: str
    kdf: str
    kdf_uuid_hex: str
    #: 变体字典 `M` 字段（字节）。JSON 伴生文件用 KiB，换算见 [memory_kib]。
    memory_bytes: Optional[int]
    iterations: Optional[int]
    parallelism: Optional[int]
    argon2_version: Optional[int]
    salt_hex: Optional[str]

    @property
    def memory_kib(self) -> Optional[int]:
        """伴生 JSON 的 `memoryKib`：头部 `M` 以字节存储，KiB 为其整除值。"""
        if self.memory_bytes is None:
            return None
        if self.memory_bytes % 1024 != 0:
            return None
        return self.memory_bytes // 1024


def _read_exact(data: bytes, offset: int, length: int, what: str) -> bytes:
    if length < 0 or offset < 0 or offset + length > len(data):
        raise KdbxHeaderError(f"{what} 被截断：需要 {length} 字节，剩余 {max(0, len(data) - offset)} 字节")
    return data[offset:offset + length]


def parse_header_fields(data: bytes) -> Dict[int, bytes]:
    """解析 KDBX4 外层头部的「字段 ID → 字段体」映射（遇到 END 字段即停止）。"""
    if len(data) < 12:
        raise KdbxHeaderError("文件长度不足 12 字节，不可能是 KDBX 文件")

    sig1, sig2, version = struct.unpack_from("<III", data, 0)
    if sig1 != SIGNATURE_1:
        raise KdbxHeaderError(f"签名 1 不符：0x{sig1:08X}（期望 0x{SIGNATURE_1:08X}）")
    if sig2 != SIGNATURE_2_KDBX:
        raise KdbxHeaderError(f"签名 2 不符：0x{sig2:08X}（期望 0x{SIGNATURE_2_KDBX:08X}）")
    major = version >> 16
    if major != SUPPORTED_MAJOR_VERSION:
        raise KdbxHeaderError(f"仅支持 KDBX 主版本 {SUPPORTED_MAJOR_VERSION}，实际为 {major}")

    fields: Dict[int, bytes] = {}
    offset = 12
    while True:
        header = _read_exact(data, offset, 5, "头部字段头")
        field_id = header[0]
        size = struct.unpack_from("<I", header, 1)[0]
        if size > MAX_FIELD_BYTES:
            raise KdbxHeaderError(f"字段 {field_id} 长度越界：{size}（上限 {MAX_FIELD_BYTES}）")
        offset += 5
        body = _read_exact(data, offset, size, f"字段 {field_id} 的字段体")
        offset += size
        if field_id == FIELD_ID_END:
            break
        fields[field_id] = body
    return fields


def parse_variant_dictionary(data: bytes) -> Dict[str, Tuple[int, bytes]]:
    """解析 KDBX4 变体字典为「键 → (类型, 原始值)」。"""
    if len(data) < 2:
        raise KdbxHeaderError("变体字典长度不足 2 字节")
    version = struct.unpack_from("<H", data, 0)[0]
    if (version & 0xFF00) != 0x0100:
        raise KdbxHeaderError(f"不支持的变体字典版本：0x{version:04X}")

    result: Dict[str, Tuple[int, bytes]] = {}
    offset = 2
    while True:
        # 终止符是**单个 0x00 字节**（无 key/value），对齐
        # database/.../crypto/VariantDictionary.kt 的 deserialize 语义。
        item_type = _read_exact(data, offset, 1, "变体字典元素类型")[0]
        offset += 1
        if item_type == VAR_TYPE_END:
            break
        if len(result) >= MAX_VARIANT_ENTRIES:
            raise KdbxHeaderError(f"变体字典条目数超过上限 {MAX_VARIANT_ENTRIES}")

        key_len = struct.unpack_from("<I", _read_exact(data, offset, 4, "变体字典键长度"), 0)[0]
        if key_len < 1 or key_len > MAX_KEY_LENGTH:
            raise KdbxHeaderError(f"变体字典键长度非法：{key_len}（允许 1 ~ {MAX_KEY_LENGTH}）")
        offset += 4
        raw_key = _read_exact(data, offset, key_len, "变体字典键")
        offset += key_len

        value_len = struct.unpack_from("<I", _read_exact(data, offset, 4, "变体字典值长度"), 0)[0]
        if value_len > MAX_VALUE_LENGTH:
            raise KdbxHeaderError(f"变体字典值长度越界：{value_len}（上限 {MAX_VALUE_LENGTH}）")
        offset += 4
        raw_value = _read_exact(data, offset, value_len, "变体字典值")
        offset += value_len

        result[raw_key.decode("utf-8", "replace")] = (item_type, raw_value)
    return result


def _uint(entry: Optional[Tuple[int, bytes]], expected_type: int, what: str) -> Optional[int]:
    if entry is None:
        return None
    item_type, raw = entry
    if item_type != expected_type:
        raise KdbxHeaderError(f"{what} 类型不符：0x{item_type:02X}（期望 0x{expected_type:02X}）")
    width = len(raw)
    if width not in (4, 8):
        raise KdbxHeaderError(f"{what} 宽度非法：{width} 字节")
    return int.from_bytes(raw, "little")


def parse_file(path: str, max_header_bytes: int = 4 * 1024 * 1024) -> KdbxHeaderInfo:
    """从文件路径解析头部元数据（只读取前 [max_header_bytes] 字节）。"""
    with open(path, "rb") as handle:
        data = handle.read(max_header_bytes)
    return parse_bytes(data)


def parse_bytes(data: bytes) -> KdbxHeaderInfo:
    """从字节解析头部元数据。"""
    # 先让 parse_header_fields 完成「长度 / 签名 / 主版本 / 字段完整性」校验，再读版本号，
    # 避免对过短输入做越界读取。
    fields = parse_header_fields(data)
    version = struct.unpack_from("<I", data, 8)[0]
    major, minor = version >> 16, version & 0xFFFF

    cipher_raw = fields.get(FIELD_ID_CIPHER)
    if cipher_raw is None:
        raise KdbxHeaderError("头部缺失 CipherID 字段（字段 2）")
    cipher_hex = cipher_raw.hex()

    kdf_raw = fields.get(FIELD_ID_KDF_PARAMETERS)
    if kdf_raw is None:
        raise KdbxHeaderError("头部缺失 KdfParameters 字段（字段 11）")
    variant = parse_variant_dictionary(kdf_raw)

    kdf_uuid_raw = variant.get("$UUID")
    if kdf_uuid_raw is None:
        raise KdbxHeaderError("KDF 变体字典缺失 $UUID")
    kdf_hex = kdf_uuid_raw[1].hex()

    salt_entry = variant.get("S")
    salt_hex = salt_entry[1].hex() if salt_entry is not None else None

    memory_bytes = _uint(variant.get("M"), VAR_TYPE_UINT64, "KDF 参数 M")
    iterations = _uint(variant.get("I"), VAR_TYPE_UINT64, "KDF 参数 I")
    if iterations is None:
        # AES-KDF 用 R 表示轮数
        iterations = _uint(variant.get("R"), VAR_TYPE_UINT64, "KDF 参数 R")
    parallelism = _uint(variant.get("P"), VAR_TYPE_UINT32, "KDF 参数 P")
    argon2_version = _uint(variant.get("V"), VAR_TYPE_UINT32, "KDF 参数 V")

    return KdbxHeaderInfo(
        major_version=major,
        minor_version=minor,
        cipher=CIPHER_UUIDS.get(cipher_hex, f"unknown({cipher_hex})"),
        cipher_uuid_hex=cipher_hex,
        kdf=KDF_UUIDS.get(kdf_hex, f"unknown({kdf_hex})"),
        kdf_uuid_hex=kdf_hex,
        memory_bytes=memory_bytes,
        iterations=iterations,
        parallelism=parallelism,
        argon2_version=argon2_version,
        salt_hex=salt_hex,
    )
