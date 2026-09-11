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
#[path = "tests/twofish_cbc_tests.rs"]
mod tests;
