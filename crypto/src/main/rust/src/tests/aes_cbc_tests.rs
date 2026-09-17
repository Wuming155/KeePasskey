//! [`aes_cbc`] 内核测试（落位约定见 ISSUE-P3-57：`src/tests/<name>_tests.rs`）。
//!
//! 对照源：
//! 1. **NIST 官方向量 CBC-AES256**（`csrc.nist.gov` 的《Block Cipher Modes of Operation · CBC》
//!    示例文档，即 SP 800-38A F.2.5 / F.2.6；2026-09-17 逐字摘录，**不手打**——§145 教训）；
//! 2. **分段等价性**：整段加密与「切成任意多段连续加密」必须逐字节一致——这是流式路径
//!    （每 64 KiB 一段、`iv` 原地演化）正确性的直接依据；
//! 3. 参数闸门负例与往返。

use super::{cbc_decrypt, cbc_encrypt, ecb_encrypt_block, BLOCK_LEN, KEY_LEN};

fn hex(s: &str) -> Vec<u8> {
    hex::decode(s.trim()).expect("非法 hex")
}

// ============ 1) NIST CBC-AES256 官方向量（F.2.5 加密 / F.2.6 解密）============

const NIST_KEY: &str = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4";
const NIST_IV: &str = "000102030405060708090a0b0c0d0e0f";
const NIST_PLAINTEXT: &str = "6bc1bee22e409f96e93d7e117393172a\
ae2d8a571e03ac9c9eb76fac45af8e51\
30c81c46a35ce411e5fbc1191a0a52ef\
f69f2445df4f9b17ad2b417be66c3710";
const NIST_CIPHERTEXT: &str = "f58c4c04d6e5f1ba779eabfb5f7bfbd6\
9cfc4e967edb808d679f777bc6702c7d\
39f23369a9d9bacfa530e26304231461\
b2eb05e2c39be9fcda6c19078c6a9d1b";

#[test]
fn nist_cbc_aes256_encrypt_matches_official_vector() {
    let key = hex(NIST_KEY);
    assert_eq!(key.len(), KEY_LEN);
    let mut iv = hex(NIST_IV);
    let plain = hex(NIST_PLAINTEXT);
    assert_eq!(plain.len(), 4 * BLOCK_LEN, "官方向量为 4 个分组");

    let ct = cbc_encrypt(&key, &mut iv, &plain).expect("官方向量加密应成功");
    assert_eq!(hex(NIST_CIPHERTEXT), ct, "密文必须与 NIST CBC-AES256 向量逐字节一致");
}

#[test]
fn nist_cbc_aes256_decrypt_matches_official_vector() {
    let key = hex(NIST_KEY);
    let mut iv = hex(NIST_IV);
    let ct = hex(NIST_CIPHERTEXT);

    let pt = cbc_decrypt(&key, &mut iv, &ct).expect("官方向量解密应成功");
    assert_eq!(hex(NIST_PLAINTEXT), pt, "明文必须与 NIST CBC-AES256 向量逐字节一致");
}

#[test]
fn nist_ecb_first_block_matches_official_vector() {
    // F.2.5 的 Block #1：InputBlock = PT ⊕ IV ⇒ 等价于 ECB 加密该 InputBlock 得到首块密文
    let key = hex(NIST_KEY);
    let iv = hex(NIST_IV);
    let pt = hex(NIST_PLAINTEXT);
    let input_block: Vec<u8> = pt[..BLOCK_LEN]
        .iter()
        .zip(iv.iter())
        .map(|(a, b)| a ^ b)
        .collect();
    let out = ecb_encrypt_block(&key, &input_block).expect("ECB 单分组应成功");
    assert_eq!(
        hex("f58c4c04d6e5f1ba779eabfb5f7bfbd6").as_slice(),
        out.as_slice(),
        "首块密文必须与官方向量一致"
    );
}

// ============ 2) 分段等价性（流式路径每段推进 iv 的直接依据）============

/// 整段一次 vs 按任意段长切分连续推进 `iv`：必须逐字节一致。
#[test]
fn segmented_encryption_equals_single_pass() {
    let key = [0x2bu8; KEY_LEN];
    let iv0 = [0x17u8; BLOCK_LEN];
    let total = 16 * 100;
    let plain: Vec<u8> = (0..total).map(|i| (i * 31 + 7) as u8).collect();

    let mut whole_iv = iv0;
    let whole = cbc_encrypt(&key, &mut whole_iv, &plain).expect("整段应成功");

    for seg_blocks in [1usize, 3, 7, 64] {
        let mut iv = iv0;
        let mut out = Vec::new();
        for chunk in plain.chunks(seg_blocks * BLOCK_LEN) {
            out.extend_from_slice(&cbc_encrypt(&key, &mut iv, chunk).expect("分段应成功"));
        }
        assert_eq!(whole, out, "段长 {seg_blocks} 分组的密文必须与整段一致");
    }
}

#[test]
fn segmented_decryption_equals_single_pass() {
    let key = [0x5cu8; KEY_LEN];
    let iv0 = [0x91u8; BLOCK_LEN];
    let plain: Vec<u8> = (0..(16 * 40)).map(|i| (i * 13 + 3) as u8).collect();

    let mut iv = iv0;
    let ct = cbc_encrypt(&key, &mut iv, &plain).expect("加密应成功");

    let mut whole_iv = iv0;
    let whole = cbc_decrypt(&key, &mut whole_iv, &ct).expect("整段解密应成功");
    assert_eq!(plain, whole, "整段解密必须还原");

    for seg_blocks in [1usize, 5, 13] {
        let mut iv = iv0;
        let mut out = Vec::new();
        for chunk in ct.chunks(seg_blocks * BLOCK_LEN) {
            out.extend_from_slice(&cbc_decrypt(&key, &mut iv, chunk).expect("分段解密应成功"));
        }
        assert_eq!(plain, out, "段长 {seg_blocks} 分组的分段解密必须还原");
    }
}

/// `iv` 出口值契约：加密后应等于**最后一段密文**，解密后应等于**最后一段密文**（解密侧链值取密文）。
#[test]
fn iv_is_updated_in_place_to_last_cipher_block() {
    let key = [0x3du8; KEY_LEN];
    let iv0 = [0x44u8; BLOCK_LEN];
    let plain: Vec<u8> = (0..(BLOCK_LEN * 5)).map(|i| (i * 7 + 1) as u8).collect();

    let mut iv = iv0;
    let ct = cbc_encrypt(&key, &mut iv, &plain).expect("加密应成功");
    assert_eq!(
        &ct[ct.len() - BLOCK_LEN..],
        &iv[..],
        "加密侧 iv 必须演进为最后一组密文"
    );

    let mut iv_dec = iv0;
    let _ = cbc_decrypt(&key, &mut iv_dec, &ct).expect("解密应成功");
    assert_eq!(
        &ct[ct.len() - BLOCK_LEN..],
        &iv_dec[..],
        "解密侧 iv 同样必须演进为最后一组密文"
    );
}

#[test]
fn round_trip_random_lengths() {
    let key = [0x9eu8; KEY_LEN];
    for blocks in [0usize, 1, 2, 15, 64, 257] {
        let plain: Vec<u8> = (0..blocks * BLOCK_LEN).map(|i| (i * 37 + 11) as u8).collect();
        let mut iv = [0x08u8; BLOCK_LEN];
        let ct = cbc_encrypt(&key, &mut iv, &plain).expect("加密应成功");
        assert_eq!(ct.len(), plain.len(), "无填充路径密文长度必须等于明文长度");
        let mut iv = [0x08u8; BLOCK_LEN];
        let back = cbc_decrypt(&key, &mut iv, &ct).expect("解密应成功");
        assert_eq!(plain, back, "{blocks} 个分组的往返必须还原");
    }
}

// ============ 3) 参数闸门负例 ============

#[test]
fn gate_rejects_invalid_params() {
    let key = [0x11u8; KEY_LEN];
    let iv = [0x22u8; BLOCK_LEN];
    let data = [0x33u8; BLOCK_LEN * 2];

    // 密钥长度：仅 32 字节合法（AES-256）
    let mut iv_mut = iv;
    assert!(cbc_encrypt(&key[..31], &mut iv_mut, &data).is_none());
    assert!(cbc_encrypt(&[], &mut iv_mut, &data).is_none());
    assert!(ecb_encrypt_block(&key[..16], &data[..BLOCK_LEN]).is_none());

    // IV 长度
    let mut short_iv = [0u8; 15];
    assert!(cbc_encrypt(&key, &mut short_iv, &data).is_none());
    let mut short_iv = [0u8; 15];
    assert!(cbc_decrypt(&key, &mut short_iv, &data).is_none());

    // 数据非分组整数倍（不做静默截断）
    let mut iv_mut = iv;
    assert!(cbc_encrypt(&key, &mut iv_mut, &data[..BLOCK_LEN + 1]).is_none());
    let mut iv_mut = iv;
    assert!(cbc_decrypt(&key, &mut iv_mut, &data[..BLOCK_LEN + 1]).is_none());

    // 空数据是合法输入（返回空，iv 不变）
    let mut empty_iv = iv;
    assert_eq!(
        Vec::<u8>::new(),
        cbc_encrypt(&key, &mut empty_iv, &[]).expect("空数据应成功")
    );
    assert_eq!(iv, empty_iv, "空数据不得改动 iv");
}
