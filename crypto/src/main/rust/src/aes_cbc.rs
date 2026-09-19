//! AES-256-CBC 分组变换原生内核（ISSUE-P3-155 追问 / §147，纯函数，无 JNI/Android 依赖）。
//!
//! 职责边界（**有意为之，与 [`crate::twofish_cbc`] 完全同构**）：本模块只做**分组变换本体**
//! （CBC 链接 + AES 加/解密），**不含任何填充（PKCS#7）与流式语义**——那两者由 Kotlin 侧统一
//! 承担（`AesCipherEngine` + `CbcStreams` + `Pkcs7`），以保证「同一份填充实现」被整型与流式
//! 两条路径共用，避免两份可能漂移的 padding 代码。
//!
//! 密钥长度：**仅 32 字节（AES-256）**——KDBX 的 AES cipher 只有 AES-256
//! （`KdbxConstants.Cipher.AES_256_CBC`），故其余长度一律 fail-closed 返回 `None`。
//!
//! 秘密擦除：链值缓冲经 [`Zeroizing`] 包装（含提前返回路径）；`Aes256` 因 `Cargo.toml`
//! 开启 `zeroize` 特性，其**密钥调度随析构确定性归零**。
//!
//! **性能纪律（§147 实测教训）**：逐块循环里**禁止**任何逐块分配 / 逐块 `Vec::push` /
//! 逐块 `Zeroizing` —— 早期版本每块构造 `Zeroizing<[u8;16]>` 并 `extend_from_slice` 入 `Vec`，
//! 单块开销显著高于 AES 本身。现版本直接写入**预分配输出缓冲**的对应切片，链值用单缓冲承载。

use aes::cipher::array::Array;
use aes::cipher::consts::U16;
use aes::cipher::{BlockCipherDecrypt, BlockCipherEncrypt, KeyInit};
use aes::Aes256;
use zeroize::Zeroizing;

/// 128 位分组类型（与 `cipher::Block<Aes256>` 为同一类型）。
type Block = Array<u8, U16>;

/// `&[u8]` → `&Block`（长度不符即 panic：调用点均保证长度恒为 [`BLOCK_LEN`]）。
#[inline]
fn as_block_ref(slice: &[u8]) -> &Block {
    <&Block>::try_from(slice).expect("AES 分组长度恒为 16")
}

/// `&mut [u8]` → `&mut Block`（同上，长度由调用点保证）。
#[inline]
fn as_block_mut(slice: &mut [u8]) -> &mut Block {
    <&mut Block>::try_from(slice).expect("AES 分组长度恒为 16")
}

/// 分组长度（字节）。
pub const BLOCK_LEN: usize = 16;

/// 唯一受支持的密钥长度（AES-256；KDBX AES cipher 无其它规格）。
pub const KEY_LEN: usize = 32;

/// 单分组 ECB 加密（**仅供已知答案向量校验**；生产管线一律走 [`cbc_encrypt`]）。
pub fn ecb_encrypt_block(key: &[u8], plain: &[u8]) -> Option<[u8; BLOCK_LEN]> {
    if key.len() != KEY_LEN || plain.len() != BLOCK_LEN {
        return None;
    }
    let cipher = Aes256::new_from_slice(key).ok()?;
    let mut block = Zeroizing::new(*as_block_ref(plain));
    cipher.encrypt_block(&mut block);
    Some((*block).into())
}

/// CBC 链式加密（**不做填充**）。
///
/// - `data.len()` 必须为 [`BLOCK_LEN`] 的整数倍（含 0，返回空 `Vec`）；
/// - `iv.len()` 必须为 [`BLOCK_LEN`]；函数返回时 **[`iv`] 被原地更新为最后一组密文**，
///   即「下一段数据的起始链值」——调用方据此把长数据切成任意多段连续加密而不破坏 CBC 链接
///   （流式路径每 64 KiB 一段，正是靠该契约推进）。
///
/// 任一前置条件不满足返回 `None`（对应 JNI 层 `null` → Kotlin 抛 `CipherException`）。
pub fn cbc_encrypt(key: &[u8], iv: &mut [u8], data: &[u8]) -> Option<Vec<u8>> {
    if key.len() != KEY_LEN || iv.len() != BLOCK_LEN || data.len() % BLOCK_LEN != 0 {
        return None;
    }
    let cipher = Aes256::new_from_slice(key).ok()?;

    // 链值单缓冲（全路径 Zeroizing）；**逐块零分配**：直接写进输出缓冲，不经 Vec::push
    let mut chain = Zeroizing::new([0u8; BLOCK_LEN]);
    chain.copy_from_slice(iv);

    let mut out = vec![0u8; data.len()];
    for (src, dst) in data
        .chunks_exact(BLOCK_LEN)
        .zip(out.chunks_exact_mut(BLOCK_LEN))
    {
        // dst = E(src ⊕ chain)，随后密文本身成为下一链值（CBC 定义）
        for i in 0..BLOCK_LEN {
            dst[i] = src[i] ^ chain[i];
        }
        cipher.encrypt_block(as_block_mut(dst));
        chain.copy_from_slice(dst);
    }

    iv.copy_from_slice(&chain[..]);
    Some(out)
}

/// CBC 链式解密（**不做去填充**）。
///
/// 语义与 [`cbc_encrypt`] 对称：`iv` 原地更新为最后一组**密文**（解密侧链值同样取密文）。
/// 数据非整数倍分组时返回 `None`——**不做任何静默截断**，交由上层 fail-closed。
pub fn cbc_decrypt(key: &[u8], iv: &mut [u8], data: &[u8]) -> Option<Vec<u8>> {
    if key.len() != KEY_LEN || iv.len() != BLOCK_LEN || data.len() % BLOCK_LEN != 0 {
        return None;
    }
    let cipher = Aes256::new_from_slice(key).ok()?;

    let mut chain = Zeroizing::new([0u8; BLOCK_LEN]);
    chain.copy_from_slice(iv);

    let mut out = vec![0u8; data.len()];
    for (src, dst) in data
        .chunks_exact(BLOCK_LEN)
        .zip(out.chunks_exact_mut(BLOCK_LEN))
    {
        // dst = D(src) ⊕ chain；解密侧链值取**密文**（即本组输入 src，故无需副本）
        dst.copy_from_slice(src);
        cipher.decrypt_block(as_block_mut(dst));
        for i in 0..BLOCK_LEN {
            dst[i] ^= chain[i];
        }
        chain.copy_from_slice(src);
    }

    iv.copy_from_slice(&chain[..]);
    Some(out)
}

/// CBC 链式加密（**原地形态**，供 direct `ByteBuffer` 零拷贝路径使用，`ISSUE-P3-198`）。
///
/// 与 [`cbc_encrypt`] 语义逐字节一致（含 `iv` 出口契约），区别仅在输出**覆写输入缓冲**：
/// 加密侧 `dst = E(src ⊕ chain)` 天然可原地——明文块先就地异或链值、再就地加密，
/// 明文在被密文覆写前已完整消费。参数闸门同 [`cbc_encrypt`]（含「非整数倍分组返回 `None`」）；
/// 闸门通过后循环内**无失败路径**，不存在「部分变换」的中间态（JNI 层据此保证失败时不留半截密文）。
pub fn cbc_encrypt_in_place(key: &[u8], iv: &mut [u8], data: &mut [u8]) -> Option<()> {
    if key.len() != KEY_LEN || iv.len() != BLOCK_LEN || data.len() % BLOCK_LEN != 0 {
        return None;
    }
    let cipher = Aes256::new_from_slice(key).ok()?;

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

/// CBC 链式解密（**不做去填充**；原地形态，语义与 [`cbc_decrypt`] 逐字节一致，`ISSUE-P3-198`）。
///
/// 解密侧原地覆写需要**先暂存本组密文**——它是下一轮的链值，覆写后即丢失
/// （与 [`cbc_encrypt_in_place`] 的无暂存形态不同，暂存缓冲同样全路径 [`Zeroizing`]）。
pub fn cbc_decrypt_in_place(key: &[u8], iv: &mut [u8], data: &mut [u8]) -> Option<()> {
    if key.len() != KEY_LEN || iv.len() != BLOCK_LEN || data.len() % BLOCK_LEN != 0 {
        return None;
    }
    let cipher = Aes256::new_from_slice(key).ok()?;

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
#[path = "tests/aes_cbc_tests.rs"]
mod tests;
