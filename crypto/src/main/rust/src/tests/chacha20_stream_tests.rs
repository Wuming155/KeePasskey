//! [`chacha20_stream`] 内核测试（落位约定见 ISSUE-P3-57：`src/tests/<name>_tests.rs`）。
//!
//! 对照源：
//! 1. **RFC 7539 §2.4.2 官方测试向量**（独立第三方对照，锁定 (key, nonce, counter, PT) → CT）；
//! 2. **任意字节粒度分块与整段单次施加的等价性**（生产 JNI 按流式任意长度调用的正确性依据）；
//! 3. 参数闸门负例（含 RFC 7539 32 位计数器上界）。

use super::{apply_keystream_at, KEY_LEN, NONCE_LEN};

fn hex(s: &str) -> Vec<u8> {
    hex::decode(s.trim()).expect("非法 hex")
}

// ============ 1) RFC 8439（= RFC 7539 定稿）§2.4.2 官方向量（counter=1）============

#[test]
fn rfc7539_section_242_test_vector() {
    // RFC 7539 §2.4.2：key = 00:01:..:1f，nonce = 00 00 00 00 00 00 00 4a 00 00 00 00，
    // 初始块计数器 = 1（即字节偏移 64），明文 "Ladies and Gentlemen of the class of '99 ..."。
    let key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    let nonce = hex("000000000000004a00000000");
    let plain = b"Ladies and Gentlemen of the class of '99: If I could offer you \
only one tip for the future, sunscreen would be it.";
    let expected_ct = hex(
        "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b\
f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8\
07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736\
5af90bbf74a35be6b40b8eedf2785e42874d",
    );

    let mut data = plain.to_vec();
    // 明文从块计数器 1 起加密 = 字节偏移 64（块 0 未被使用）
    apply_keystream_at(&key, &nonce, 64, &mut data).expect("官方向量应成功");
    assert_eq!(data, expected_ct, "密文必须与 RFC 7539 §2.4.2 逐字节一致");

    // 对称性：对密文再施加同一密钥流必须还原明文（解密方向）
    apply_keystream_at(&key, &nonce, 64, &mut data).expect("解密方向应成功");
    assert_eq!(data, plain.to_vec(), "往返必须还原明文");
}

// ============ 2) 任意字节粒度分块 = 整段单次施加 ============

#[test]
fn arbitrary_byte_granularity_equals_whole_pass() {
    let key = [0x42u8; KEY_LEN];
    let nonce = [0x24u8; NONCE_LEN];
    let total = 64 * 4 + 37;
    let mut whole: Vec<u8> = (0..total).map(|i| (i * 31 + 7) as u8).collect();
    let plain = whole.clone();
    apply_keystream_at(&key, &nonce, 0, &mut whole).expect("整段应成功");

    // 调用方粒度由流包装决定：覆盖块对齐 / 跨块 / 块内偏移 / 单字节
    for gran in [1usize, 7, 63, 64, 100, 200] {
        let mut chunked = plain.clone();
        let mut pos = 0usize;
        while pos < total {
            let end = (pos + gran).min(total);
            apply_keystream_at(&key, &nonce, pos as u64, &mut chunked[pos..end])
                .expect("任意粒度分块应成功");
            pos = end;
        }
        assert_eq!(whole, chunked, "粒度 {gran} 下分块必须与整段逐字节一致");
    }

    // 对称往返（粒度 100）
    let mut round_trip = whole.clone();
    let mut pos = 0usize;
    while pos < total {
        let end = (pos + 100).min(total);
        apply_keystream_at(&key, &nonce, pos as u64, &mut round_trip[pos..end])
            .expect("分块解密应成功");
        pos = end;
    }
    assert_eq!(round_trip, plain, "分块往返必须还原明文");
}

#[test]
fn non_zero_base_offset_chain() {
    // 自非零字节偏移起链式推进（对齐 KDBX 内层流多段读写形态）
    let key = [0x11u8; KEY_LEN];
    let nonce = [0x22u8; NONCE_LEN];
    let base = 1000u64; // 块内偏移（1000 = 15×64 + 40）
    let mut whole: Vec<u8> = (0..300).map(|i| (i * 13 + 3) as u8).collect();
    let plain = whole.clone();
    apply_keystream_at(&key, &nonce, base, &mut whole).expect("应成功");

    let mut chunked = plain.clone();
    apply_keystream_at(&key, &nonce, base, &mut chunked[..113]).expect("应成功");
    apply_keystream_at(&key, &nonce, base + 113, &mut chunked[113..]).expect("应成功");
    assert_eq!(whole, chunked, "两段链式必须与整段一致");
}

// ============ 3) 参数闸门负例 ============

#[test]
fn gate_rejects_invalid_params() {
    let key = [0x42u8; KEY_LEN];
    let nonce = [0x24u8; NONCE_LEN];
    let mut data = [0u8; 16];

    // key / nonce 长度不符
    assert!(apply_keystream_at(&key[..31], &nonce, 0, &mut data).is_none());
    assert!(apply_keystream_at(&key, &nonce[..11], 0, &mut data).is_none());

    // 偏移越过密钥流上界（2^32 × 64 字节）
    let overflow = (u32::MAX as u64 + 1) * 64;
    assert!(apply_keystream_at(&key, &nonce, overflow, &mut data).is_none());
    assert!(apply_keystream_at(&key, &nonce, overflow - 15, &mut data).is_none());
    // 末字节恰在上界内合法
    let mut tail = [0u8; 16];
    assert!(apply_keystream_at(&key, &nonce, overflow - 16, &mut tail).is_some());

    // u64 溢出（checked_add 闸门）
    assert!(apply_keystream_at(&key, &nonce, u64::MAX, &mut data).is_none());

    // 空数据幂等合法（任意偏移）
    let mut empty: [u8; 0] = [];
    assert!(apply_keystream_at(&key, &nonce, 0, &mut empty).is_some());
    assert!(apply_keystream_at(&key, &nonce, 123456, &mut empty).is_some());
}
