use super::*;

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// **Twofish 官方规格已知答案向量**（256 位全零密钥 / 全零明文）。
/// 该值同时是 Twofish 设计文档与 NIST AES 竞选提交材料中的标准向量，
/// 与 Kotlin 侧「BC 差分等价」用例（`TwofishNativeParityTest`）构成双重独立对照。
#[test]
fn known_answer_vector_256bit_zero_key() {
    let key = [0u8; 32];
    let plain = [0u8; 16];
    assert_eq!(
        hex(&ecb_encrypt_block(&key, &plain).unwrap()),
        "57ff739d4dc92c1bd7fc01700cc8216f"
    );
}

/// 128 位全零密钥 / 全零明文（官方规格向量）。
#[test]
fn known_answer_vector_128bit_zero_key() {
    let key = [0u8; 16];
    let plain = [0u8; 16];
    assert_eq!(
        hex(&ecb_encrypt_block(&key, &plain).unwrap()),
        "9f589f5cf6122c32b6bfec2f2ae8c35a"
    );
}

/// CBC 往返：任意长度（16 的整数倍）× 多种密钥长度，解密必须还原原文。
#[test]
fn cbc_round_trip_various_lengths_and_key_sizes() {
    for key_len in SUPPORTED_KEY_LENS {
        for blocks in [1usize, 2, 7, 64] {
            let key: Vec<u8> = (0..key_len).map(|i| (i * 7 + 3) as u8).collect();
            let plain: Vec<u8> = (0..blocks * BLOCK_LEN).map(|i| (i * 13 + 5) as u8).collect();

            let mut enc_iv = [0x5Au8; BLOCK_LEN];
            let cipher_text = cbc_encrypt(&key, &mut enc_iv, &plain).unwrap();
            assert_eq!(cipher_text.len(), plain.len());

            let mut dec_iv = [0x5Au8; BLOCK_LEN];
            let recovered = cbc_decrypt(&key, &mut dec_iv, &cipher_text).unwrap();
            assert_eq!(recovered, plain, "key_len={key_len} blocks={blocks}");

            // 两侧 IV 演化一致（同为最后一组密文）
            assert_eq!(enc_iv, dec_iv);
        }
    }
}

/// **分段调用等价性**：把同一明文切成多段连续加密（共享演化中的 IV），
/// 结果必须与一次性加密逐字节相同——这是 JNI 层「按 chunk 调用」正确性的根据。
#[test]
fn chunked_calls_match_single_shot() {
    let key = [0x21u8; 32];
    let plain: Vec<u8> = (0..(16 * 100)).map(|i| (i * 31 + 7) as u8).collect();

    let mut iv_shot = [0x11u8; BLOCK_LEN];
    let single = cbc_encrypt(&key, &mut iv_shot, &plain).unwrap();

    let mut iv_chunked = [0x11u8; BLOCK_LEN];
    let mut chunked: Vec<u8> = Vec::new();
    for part in plain.chunks(BLOCK_LEN * 7) {
        chunked.extend_from_slice(&cbc_encrypt(&key, &mut iv_chunked, part).unwrap());
    }

    assert_eq!(chunked, single);
    assert_eq!(iv_chunked, iv_shot);
}

/// 空输入：合法（返回空），且不改变 IV。
#[test]
fn empty_input_is_valid_and_keeps_iv() {
    let key = [0x11u8; 32];
    let mut iv = [0x33u8; BLOCK_LEN];
    let out = cbc_encrypt(&key, &mut iv, &[]).unwrap();
    assert!(out.is_empty());
    assert_eq!(iv, [0x33u8; BLOCK_LEN]);
}

/// 闸门负例：非整数倍分组 / IV 长度错 / 不支持的密钥长度一律 `None`（fail-closed）。
#[test]
fn gate_rejects_invalid_params() {
    let key = [0u8; 32];
    let mut iv = [0u8; BLOCK_LEN];
    // 非整数倍分组
    assert!(cbc_encrypt(&key, &mut iv, &[0u8; 15]).is_none());
    assert!(cbc_encrypt(&key, &mut iv, &[0u8; 17]).is_none());
    assert!(cbc_decrypt(&key, &mut iv, &[0u8; 31]).is_none());
    // IV 长度错
    let mut short_iv = [0u8; 8];
    assert!(cbc_encrypt(&key, &mut short_iv, &[0u8; 16]).is_none());
    // 不支持的密钥长度（Twofish 规范仅 16/24/32）
    assert!(cbc_encrypt(&[0u8; 20], &mut iv, &[0u8; 16]).is_none());
    assert!(ecb_encrypt_block(&[0u8; 20], &[0u8; 16]).is_none());
    assert!(ecb_encrypt_block(&key, &[0u8; 15]).is_none());
}

/// CBC 链接确实生效：对同一明文块、不同 IV，密文必须不同（防「退化为 ECB」）。
#[test]
fn cbc_chaining_is_effective_not_ecb() {
    let key = [0x77u8; 32];
    let plain = [0u8; 32]; // 两块相同明文
    let mut iv_a = [0u8; BLOCK_LEN];
    let a = cbc_encrypt(&key, &mut iv_a, &plain).unwrap();
    // 两块相同明文在 CBC 下必须产出不同密文块
    assert_ne!(a[0..16], a[16..32]);

    let mut iv_b = [0x01u8; BLOCK_LEN];
    let b = cbc_encrypt(&key, &mut iv_b, &plain).unwrap();
    // 换 IV 整体改变
    assert_ne!(a, b);
}
