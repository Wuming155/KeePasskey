//! KeePass 经典 AES-KDF 原生内核（ISSUE-P3-34，纯函数，无 JNI/Android 依赖）。
//!
//! 算法语义（对齐 KeePass 官方 `AesKdf.Transform` / 既有 Kotlin `AesKdfEngine`）：
//! ```text
//! buffer = compositeKey                       // 32 字节（KDBX 规约的复合密钥摘要）
//! 重复 R 次：buffer = AES-256-ECB-Encrypt(key = seed, buffer)
//! transformedKey = SHA-256(buffer)            // 32 字节
//! ```
//! 注意方向：**seed 作密钥、compositeKey 作明文**（与「用 compositeKey 解密密文」相反），
//! 这是 KeePass 的历史设计，KDBX 3 与选用 AES-KDF 的 KDBX 4 库均如此。
//!
//! 为何原生化（ISSUE-P3-34 背景）：
//! - 轮数达千万级（`KdfBenchmark.DEFAULT_AES_ROUNDS = 6_000_000`），且**严格串行链式依赖**
//!   （第 n 轮输入 = 第 n-1 轮输出），既无法并行也不能靠加宽缓冲摊薄，只能靠实现质量；
//! - 32 字节明文恰为 **2 个独立 AES 分组**（ECB 下两块互不依赖），Rust 侧可在同一循环内
//!   让两条链由 CPU 乱序执行重叠推进；同时消除 Kotlin 侧「每轮一次 JCE `update`」的跨界开销。
//!
//! 秘密擦除：明文缓冲经 [`Zeroizing`] 包装，函数返回（含任一 `?`／闸门提前返回）即确定性归零。

use aes::cipher::array::Array;
use aes::cipher::consts::U16;
use aes::cipher::{BlockCipherEncrypt, KeyInit};
use aes::Aes256;
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

/// 128 位分组类型（与 `cipher::Block<Aes256>` 为同一类型）。
type Block = Array<u8, U16>;

/// `&mut [u8]` → `&mut Block`（长度不符即 panic：调用点均保证长度恒为 [`BLOCK_LEN`]）。
#[inline]
fn as_block_mut(slice: &mut [u8]) -> &mut Block {
    <&mut Block>::try_from(slice).expect("AES 分组长度恒为 16")
}

/// 复合密钥长度（字节）：KDBX 规约的 SHA-256 摘要。
pub const COMPOSITE_KEY_LEN: usize = 32;

/// 派生输出长度（字节）＝ SHA-256 摘要长度。
pub const OUT_LEN: usize = 32;

/// AES 分组长度（字节）。
const BLOCK_LEN: usize = 16;

/// 明文缓冲长度：32 字节恰为两个独立 AES 分组。
const BUF_LEN: usize = COMPOSITE_KEY_LEN;

/// 轮数上界（纵深防御）。
///
/// 与 Kotlin 侧 `KdbxKdfParameterCodec.AES_KDF_MAX_ROUNDS = 1L shl 28` **同值**：
/// 该上界已在 KDF 参数反序列化阶段先行裁决，此处为**第二道**闸门，
/// 确保即便有调用方绕过 Kotlin 裁决，原生侧也不会被 `u64::MAX` 轮数拖入无限循环。
pub const MAX_ROUNDS: u64 = 1 << 28;

/// AES-KDF 密钥派生（纯函数）。
///
/// `composite_key` 与 `seed` 均须为 32 字节；`rounds` 须落在 `[1, MAX_ROUNDS]`。
/// 任一条件不满足返回 `None`（对应 JNI 层的 `null` → Kotlin `KdfException`）。
///
/// 秘密擦除：`buf` 为 [`Zeroizing<[u8; 32]>`]，任何返回路径（含下方所有 `?`）均确定性归零。
pub fn aes_kdf(composite_key: &[u8], seed: &[u8], rounds: u64) -> Option<[u8; OUT_LEN]> {
    // —— 参数闸门 ——
    if composite_key.len() != COMPOSITE_KEY_LEN || seed.len() != COMPOSITE_KEY_LEN {
        return None;
    }
    if rounds < 1 || rounds > MAX_ROUNDS {
        return None;
    }

    let cipher = Aes256::new_from_slice(seed).ok()?;

    let mut buf = Zeroizing::new([0u8; BUF_LEN]);
    buf.copy_from_slice(composite_key);

    for _ in 0..rounds {
        // 两个分组互不依赖（ECB），拆开分别加密后由 CPU 乱序执行自然重叠两条链。
        let (lo, hi) = buf.split_at_mut(BLOCK_LEN);
        cipher.encrypt_block(as_block_mut(lo));
        cipher.encrypt_block(as_block_mut(hi));
    }

    let mut hasher = Sha256::new();
    hasher.update(&buf[..]);
    let digest = hasher.finalize();

    let mut out = [0u8; OUT_LEN];
    out.copy_from_slice(&digest);
    Some(out)
}

#[cfg(test)]
#[path = "tests/aes_kdf_tests.rs"]
mod tests;
