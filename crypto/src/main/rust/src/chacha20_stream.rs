//! ChaCha20（RFC 7539）流密码内核（ISSUE-P3-153 / §145）。
//!
//! 动机：KDBX 三种可选外层 cipher 中，ChaCha20 的 BouncyCastle 纯 Java 实现在真机上仅
//! 2.6~2.7 MB/s（AES 硬件路径的 ~1/118；实测见
//! `docs/records/真机吞吐实测记录_2026-09-17.md` §2.1），Rust 候选实测 ≈118 MB/s（≈44×）。
//!
//! 设计（对齐既有内核范式）：
//! - **纯函数、无状态**：`apply_keystream_at(key, nonce, byte_offset, data)` 以
//!   「密钥流字节偏移」定位——ChaCha20 密钥流块 j 仅由 (key, counter₀+j, nonce) 决定，
//!   无需跨 JNI 持有流对象生命周期；调用方（JNI/Kotlin 流包装）按已处理字节数推进偏移。
//! - **任意字节偏移**：crate `StreamCipherSeek` 契约即「position in bytes」，
//!   块内偏移由 `StreamCipherCoreWrapper` 内部缓冲消化，调用方无需对齐。
//! - **秘密擦除**：密钥调度状态随 `ChaCha20` 对象析构确定性归零（crate `zeroize` feature，
//!   与 aes / twofish 同纪律）；入参切片擦除责任在调用方（JNI 层 `Zeroizing`）。

use chacha20::cipher::{KeyIvInit, StreamCipher, StreamCipherSeek};
use chacha20::ChaCha20;

/// 密钥长度（RFC 7539：256 位）。
pub const KEY_LEN: usize = 32;
/// nonce 长度（RFC 7539：96 位；KDBX `CHACHA20_NONCE_LENGTH` 同值）。
pub const NONCE_LEN: usize = 12;
/// 密钥流块长度（ChaCha20 内部 64 字节块；seek 定位粒度）。
pub const KS_BLOCK_LEN: usize = 64;

/// 对 `data` **原地**施加自 `byte_offset`（密钥流**字节**偏移）起的 ChaCha20 密钥流。
///
/// RFC 7539 加解密共用同一条密钥流（keystream ⊕ data），故本函数同时承担两个方向。
/// 任意字节偏移由 crate 的 `StreamCipherSeek`（契约即「keystream position in bytes」，
/// 见 cipher 0.5.2 `stream.rs` §StreamCipherSeek）与 `StreamCipherCoreWrapper` 的逐字节
/// 缓冲消化，调用方（JNI / Kotlin 流包装）只需按已处理字节数推进 `byte_offset`。
///
/// 参数闸门（任一不满足返回 `None`，对齐既有内核的 null 语义）：
/// - `key.len() == 32`、`nonce.len() == 12`；
/// - `data` 任意长度（含 0——空切片是幂等合法输入）；
/// - `byte_offset + data.len()` 不得越过密钥流上界 `2^32 × 64` 字节（RFC 7539 计数器为
///   32 位块计数器，即 256 GiB 密钥流）——超出即 `None`（显式闸门；KDBX 单库载荷远低于此）。
pub fn apply_keystream_at(key: &[u8], nonce: &[u8], byte_offset: u64, data: &mut [u8]) -> Option<()> {
    if key.len() != KEY_LEN || nonce.len() != NONCE_LEN {
        return None;
    }
    // 密钥流上界：块计数器 u32 ⇒ 末字节偏移 < 2^32 × 64（checked 防溢出）
    let end_offset = byte_offset.checked_add(data.len() as u64)?;
    if end_offset > (u32::MAX as u64 + 1) * KS_BLOCK_LEN as u64 {
        return None;
    }

    let mut cipher = ChaCha20::new(key.into(), nonce.into());
    // seek 以字节计（StreamCipherSeek 契约），块内偏移由 wrapper 内部缓冲消化
    cipher.seek(byte_offset);
    cipher.apply_keystream(data);
    Some(())
}

#[cfg(test)]
#[path = "tests/chacha20_stream_tests.rs"]
mod tests;
