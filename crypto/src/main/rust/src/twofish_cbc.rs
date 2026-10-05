//! Twofish-CBC 分组变换原生内核（ISSUE-P3-35，纯函数，无 JNI/Android 依赖）。
//!
//! 职责边界（**有意为之，与 [`crate::aes_cbc`] 完全同构**，`ISSUE-P3-478` 收口对称性）：本模块只做
//! **分组变换本体**（CBC 链接 + Twofish 加/解密），不含任何填充（PKCS7）与流式语义——那两者由
//! Kotlin 侧统一承担（`TwofishCipherEngine` + `CbcStreams`），以保证「同一份填充实现」被整型与流式
//! 两条路径共用，避免出现两份可能漂移的 padding 代码。
//!
//! 为何原生化（ISSUE-P3-35 背景）：KDBX 三种可选 cipher 中，AES-256-CBC（默认）与 ChaCha20
//! 都走在硬件加速路径上，**只有 Twofish 依赖 BouncyCastle 纯 Java 实现**，而它作用于**整库数据流**，
//! 属数据面热点而非一次性开销。
//!
//! 秘密擦除：链值缓冲与分组缓冲经 [`Zeroizing`] 包装（含提前返回路径）；Twofish 对象因
//! `Cargo.toml` 开启 `zeroize` 特性，其**密钥调度随析构确定性归零**。
//!
//! **性能纪律（§147 实测教训，`ISSUE-P3-474` 收口）**：逐块循环里**禁止**任何逐块分配 / 逐块
//! `Vec::push` / 逐块 `Zeroizing` —— 早期版本每块构造 `Zeroizing<[u8;16]>` 并 `extend_from_slice`
//! 入 `Vec`，单块开销显著高于 Twofish 本身。现版本（与 [`crate::aes_cbc`] 同形）直接写入**预分配
//! 输出缓冲**的对应切片，链值用单缓冲承载；整型与原地两条出口共享唯一一份循环（[`cbc_encrypt`] /
//! [`cbc_decrypt`] 薄委托至 [`cbc_encrypt_in_place`] / [`cbc_decrypt_in_place`]）。

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

/// `&mut [u8]` → `&mut Block`（同上，长度由调用点保证）。
#[inline]
fn as_block_mut(slice: &mut [u8]) -> &mut Block {
    <&mut Block>::try_from(slice).expect("Twofish 分组长度恒为 16")
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
///
/// `ISSUE-P2-467` 同款收口：本函数为薄委托——闸门与循环只留 [`cbc_encrypt_in_place`] 一份，
/// 此处仅拷贝输入后复用原地实现，避免两份手写 CBC 逻辑静默漂移。
pub fn cbc_encrypt(key: &[u8], iv: &mut [u8], data: &[u8]) -> Option<Vec<u8>> {
    let mut out = data.to_vec();
    cbc_encrypt_in_place(key, iv, &mut out)?;
    Some(out)
}

/// CBC 链式解密（**不做去填充**）。
///
/// 语义与 [`cbc_encrypt`] 对称：`iv` 原地更新为最后一组**密文**（解密侧链值同样取密文）。
/// 数据非整数倍分组时返回 `None`——**不做任何静默截断**，交由上层 fail-closed。
///
/// `ISSUE-P2-467` 同款收口：同上，薄委托至 [`cbc_decrypt_in_place`]。
pub fn cbc_decrypt(key: &[u8], iv: &mut [u8], data: &[u8]) -> Option<Vec<u8>> {
    let mut out = data.to_vec();
    cbc_decrypt_in_place(key, iv, &mut out)?;
    Some(out)
}

/// CBC 链式加密（**原地形态**，与 [`crate::aes_cbc::cbc_encrypt_in_place`] 同构）。
///
/// 与 [`cbc_encrypt`] 语义逐字节一致（含 `iv` 出口契约），区别仅在输出**覆写输入缓冲**：
/// 加密侧 `dst = E(src ⊕ chain)` 天然可原地——明文块先就地异或链值、再就地加密，
/// 明文在被密文覆写前已完整消费。参数闸门同 [`cbc_encrypt`]（含「非整数倍分组返回 `None`」）；
/// 闸门通过后循环内**无失败路径**，不存在「部分变换」的中间态。
///
/// **性能纪律（§147 / `ISSUE-P3-474`）**：链值单缓冲 + 逐块零分配，直接写输入缓冲切片。
pub fn cbc_encrypt_in_place(key: &[u8], iv: &mut [u8], data: &mut [u8]) -> Option<()> {
    if !key_len_is_supported(key.len()) || iv.len() != BLOCK_LEN || data.len() % BLOCK_LEN != 0 {
        return None;
    }
    let cipher = Twofish::new_from_slice(key).ok()?;

    // 链值单缓冲（全路径 Zeroizing）；**逐块零分配**：直接在输入缓冲上原地推进
    let mut chain = Zeroizing::new([0u8; BLOCK_LEN]);
    chain.copy_from_slice(iv);

    for block in data.chunks_exact_mut(BLOCK_LEN) {
        // dst = E(src ⊕ chain)：先就地异或链值，再就地加密；密文本身即下一链值（CBC 定义）
        for i in 0..BLOCK_LEN {
            block[i] ^= chain[i];
        }
        cipher.encrypt_block(as_block_mut(block));
        chain.copy_from_slice(block);
    }

    iv.copy_from_slice(&chain[..]);
    Some(())
}

/// CBC 链式解密（**不做去填充**；原地形态，语义与 [`cbc_decrypt`] 逐字节一致）。
///
/// 解密侧原地覆写需要**先暂存本组密文**——它是下一轮的链值，覆写后即丢失
/// （与 [`cbc_encrypt_in_place`] 的无暂存形态不同，暂存缓冲同样全路径 [`Zeroizing`]）。
pub fn cbc_decrypt_in_place(key: &[u8], iv: &mut [u8], data: &mut [u8]) -> Option<()> {
    if !key_len_is_supported(key.len()) || iv.len() != BLOCK_LEN || data.len() % BLOCK_LEN != 0 {
        return None;
    }
    let cipher = Twofish::new_from_slice(key).ok()?;

    let mut chain = Zeroizing::new([0u8; BLOCK_LEN]);
    chain.copy_from_slice(iv);
    let mut next_chain = Zeroizing::new([0u8; BLOCK_LEN]);

    for block in data.chunks_exact_mut(BLOCK_LEN) {
        // dst = D(src) ⊕ chain；解密侧链值取**密文**（本组输入），原地覆写前必须暂存
        next_chain.copy_from_slice(block);
        cipher.decrypt_block(as_block_mut(block));
        for i in 0..BLOCK_LEN {
            block[i] ^= chain[i];
        }
        chain.copy_from_slice(&next_chain[..]);
    }

    iv.copy_from_slice(&chain[..]);
    Some(())
}

#[cfg(test)]
#[path = "tests/twofish_cbc_tests.rs"]
mod tests;
