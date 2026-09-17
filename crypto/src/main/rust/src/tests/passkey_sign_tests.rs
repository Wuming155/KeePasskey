//! [`passkey_sign`] 内核测试（落位约定见 ISSUE-P3-57：`src/tests/<name>_tests.rs`）。
//!
//! 对照源（均为**官方 RFC 向量**，逐字摘录——密码学向量不得手打，§145 教训）：
//! 1. **RFC 6979 A.2.5**（ECDSA P-256 + SHA-256，确定性 (r, s)）：x = CFCF…CF（32B），
//!    报文 "sample"；
//! 2. **RFC 8032 §7.1 TEST 1**（Ed25519 空报文，seed / sig 官方给定）。

use super::{ed25519_sign_raw, es256_sign_der, ED25519_SEED_LEN, ES256_SCALAR_LEN};

fn hex(s: &str) -> Vec<u8> {
    hex::decode(s.trim()).expect("非法 hex")
}

// ============ 1) ES256：RFC 6979 A.2.5（P-256 / SHA-256 / "sample"）============

#[test]
fn es256_rfc6979_a25_sample_vector() {
    let scalar = hex(
        "c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721",
    );
    assert_eq!(scalar.len(), ES256_SCALAR_LEN);
    let message = b"sample";
    // RFC 6979 A.2.5 给定 (r, s)：
    let r = "efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716";
    let s = "f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8";

    let sig = es256_sign_der(&scalar, message).expect("RFC 6979 向量签名应成功");

    // 由官方 (r, s) 构造期望的规范 DER（r 高位 0xEF / s 高位 0xF7 置位 ⇒ 须补前导 0x00
    // 保证 INTEGER 为正——与 BC `BigInteger.toByteArray()` 及 RustCrypto 的规范编码一致）：
    // SEQUENCE(0x46) { INTEGER(0x21) 00||r, INTEGER(0x21) 00||s }
    let mut expected = vec![0x30, 0x46, 0x02, 0x21, 0x00];
    expected.extend(hex(r));
    expected.push(0x02);
    expected.push(0x21);
    expected.push(0x00);
    expected.extend(hex(s));
    assert_eq!(&sig[..], &expected[..], "ES256 签名必须与 RFC 6979 A.2.5 逐字节一致");
}

#[test]
fn es256_deterministic() {
    let scalar = [0x2au8; ES256_SCALAR_LEN];
    let data = b"keepasskey assertion";
    let a = es256_sign_der(&scalar, data).expect("应成功");
    let b = es256_sign_der(&scalar, data).expect("应成功");
    assert_eq!(&a[..], &b[..], "RFC 6979 确定性：同 key/msg 签名必须相同");
}

// ============ 2) Ed25519：RFC 8032 §7.1 TEST 1 ============

#[test]
fn ed25519_rfc8032_section_71_test1() {
    // RFC 8032 §7.1 TEST 1（SHA(abc) 之误传？不——这是官方 TEST 1：seed 9d61…7f60，空报文）
    let seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    assert_eq!(seed.len(), ED25519_SEED_LEN);
    let expected_sig = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155\
5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    );
    let sig = ed25519_sign_raw(&seed, b"").expect("RFC 8032 TEST 1 应成功");
    assert_eq!(sig.len(), 64, "Ed25519 签名恒为 64 字节 raw");
    assert_eq!(&sig[..], &expected_sig[..], "Ed25519 签名必须与 RFC 8032 §7.1 TEST 1 一致");
}

// ============ 3) 参数闸门负例 ============

#[test]
fn gate_rejects_invalid_params() {
    let scalar = [0x2au8; ES256_SCALAR_LEN];
    let seed = [0x5au8; ED25519_SEED_LEN];

    // 长度不符
    assert!(es256_sign_der(&scalar[..31], b"x").is_none());
    assert!(ed25519_sign_raw(&seed[..31], b"x").is_none());

    // ES256 标量 0 / ≥ 曲线阶 为非法标量（RustCrypto from_bytes 校验 fail-closed）
    assert!(es256_sign_der(&[0u8; 32], b"x").is_none());
    let order = hex(
        "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
    );
    assert!(es256_sign_der(&order, b"x").is_none());

    // 空报文合法
    assert!(es256_sign_der(&scalar, b"").is_some());
    assert!(ed25519_sign_raw(&seed, b"").is_some());
}
