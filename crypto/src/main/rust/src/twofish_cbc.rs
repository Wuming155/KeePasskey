//! Twofish-CBC 分组变换原生内核（ISSUE-P3-35，纯函数，无 JNI/Android 依赖）。
//!
//! 职责边界（**有意为之**）：本模块只做**分组变换本体**（CBC 链接 + Twofish 加/解密），
//! 不含任何填充（PKCS7）与流式语义——那两者由 Kotlin 侧统一承担
//! （`TwofishCipherEngine` + `CbcStreams`），以保证「同一份填充实现」被整型与流式两条路径共用，
//! 避免出现两份可能漂移的 padding 代码。
//!
//! 为何原生化（ISSUE-P3-35 背景）：KDBX 三种可选 cipher 中，AES-256-CBC（默认）与 ChaCha20
//! 都走在硬件加速路径上，**只有 Twofish 依赖 BouncyCastle 纯 Java 实现**，而它作用于**整库数据流**，
//! 属数据面热点而非一次性开销。
//!
//! 秘密擦除：链值缓冲与分组缓冲经 [`Zeroizing`] 包装（含提前返回路径）；Twofish 对象因
//! `Cargo.toml` 开启 `zeroize` 特性，其**密钥调度随析构确定性归零**。

use twofish::cipher::array::Array;
use twofish::cipher::consts::U16;
use twofish::cipher::{BlockCipherDecrypt, BlockCipherEncrypt, KeyInit};
use twofish::Twofish;
use zeroize::Zeroizing;

/// 128 位分组类型（与 `cipher::Block<Twofish>` 为同一类型）。
/// 注：`twofish::Block` 别名在 0.8.0 为 crate 私有，故直接以 `Array<u8, U16>` 表达，不依赖私有导出。
type Block = Array<u8, U16>;

/// `&[u8]` → `&Block`（长度不符即 panic：调用点均保证长度恒为 [`BLOCK_LEN`]）。
#[inline]
fn as_block_ref(slice: &[u8]) -> &Block {
    <&Block>::try_from(slice).expect("Twofish 分组长度恒为 16")
}

/// 分组长度（字节）。
pub const BLOCK_LEN: usize = 16;

/// 支持的密钥长度：16 / 24 / 32 字节（Twofish 规范允许的全部规格）。
const SUPPORTED_KEY_LENS: [usize; 3] = [16, 24, 32];

#[inline]
fn key_len_is_supported(len: usize) -> bool {
    SUPPORTED_KEY_LENS.contains(&len)
}

/// 单分组 ECB 加密（仅供已知答案向量校验；生产管线一律走 [`cbc_encrypt`]）。
pub fn ecb_encrypt_block(key: &[u8], plain: &[u8]) -> Option<[u8; BLOCK_LEN]> {
    if !key_len_is_supported(key.len()) || plain.len() != BLOCK_LEN {
        return None;
    }
    let cipher = Twofish::new_from_slice(key).ok()?;
    let mut block = Zeroizing::new(*as_block_ref(plain));
    cipher.encrypt_block(&mut block);
    Some((*block).into())
}

/// CBC 链式加密（**不做填充**）。
///
/// - `data.len()` 必须为 [`BLOCK_LEN`] 的整数倍（含 0，返回空 `Vec`）；
/// - `iv.len()` 必须为 [`BLOCK_LEN`]；函数返回时 **[`iv`] 被原地更新为最后一组密文**，
///   即「下一段数据的起始链值」——调用方据此把长数据切成任意多段连续加密而不破坏 CBC 链接。
///
/// 任一前置条件不满足返回 `None`（对应 JNI 层 `null` → Kotlin 抛 `CipherException`）。
pub fn cbc_encrypt(key: &[u8], iv: &mut [u8], data: &[u8]) -> Option<Vec<u8>> {
    if !key_len_is_supported(key.len()) || iv.len() != BLOCK_LEN || data.len() % BLOCK_LEN != 0 {
        return None;
    }
    let cipher = Twofish::new_from_slice(key).ok()?;

    let mut prev = Zeroizing::new([0u8; BLOCK_LEN]);
    prev.copy_from_slice(iv);

    let mut out: Vec<u8> = Vec::with_capacity(data.len());
    for chunk in data.chunks_exact(BLOCK_LEN) {
        let mut block = Zeroizing::new(*as_block_ref(chunk));
        for (b, p) in block.iter_mut().zip(prev.iter()) {
            *b ^= *p;
        }
        cipher.encrypt_block(&mut block);
        // 密文既是输出也是下一组的链值（CBC 定义）
        prev.copy_from_slice(&block[..]);
        out.extend_from_slice(&block[..]);
    }

    iv.copy_from_slice(&prev[..]);
    Some(out)
}

/// CBC 链式解密（**不做去填充**）。
///
/// 语义与 [`cbc_encrypt`] 对称：`iv` 原地更新为最后一组**密文**（解密侧链值同样取密文）。
/// 数据非整数倍分组时返回 `None`——**不做任何静默截断**，交由上层 fail-closed。
pub fn cbc_decrypt(key: &[u8], iv: &mut [u8], data: &[u8]) -> Option<Vec<u8>> {
    if !key_len_is_supported(key.len()) || iv.len() != BLOCK_LEN || data.len() % BLOCK_LEN != 0 {
        return None;
    }
    let cipher = Twofish::new_from_slice(key).ok()?;

    let mut prev = Zeroizing::new([0u8; BLOCK_LEN]);
    prev.copy_from_slice(iv);

    let mut out: Vec<u8> = Vec::with_capacity(data.len());
    for chunk in data.chunks_exact(BLOCK_LEN) {
        let mut block = Zeroizing::new(*as_block_ref(chunk));
        cipher.decrypt_block(&mut block);
        for (b, p) in block.iter_mut().zip(prev.iter()) {
            *b ^= *p;
        }
        // 解密侧链值取**密文**（即本组的输入）
        prev.copy_from_slice(chunk);
        out.extend_from_slice(&block[..]);
    }

    iv.copy_from_slice(&prev[..]);
    Some(out)
}

#[cfg(test)]
mod tests {
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
}
