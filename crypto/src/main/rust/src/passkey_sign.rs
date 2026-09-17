//! Passkey 签名内核（ISSUE-P3-153 / §146）：ES256 与 Ed25519 的断言签名。
//!
//! 动机：BC 纯 Java 实现真机单次签名 ES256 ≈17.7 ms / Ed25519 ≈3.8 ms（每次通行密钥断言
//! 都要签名，属用户可见时延）；Rust 候选实测 1.07 ms / 0.17 ms（16~22×，见
//! `docs/records/真机吞吐实测记录_2026-09-17.md` §2.2）。RS256 已裁定**不下沉**
//! （Rust `rsa` crate 慢于 BC 1.6~2.1×，CRT/Montgomery 优化占优）。
//!
//! **确定性口径（对拍成立的根据）**：
//! - ES256：ECDSA P-256 + SHA-256 + **RFC 6979** 确定性 nonce——BC 生产路径用
//!   `HMacDSAKCalculator(SHA256Digest())`，本内核用 RustCrypto `ecdsa` 的 RFC 6979 实现，
//!   两者对同一 (key, msg) 产出**逐字节相同**的 (r, s)（宿主对拍用例锁定）；
//! - Ed25519：EdDSA 本身确定性（RFC 8032），输出恒为 64 字节 raw。
//!
//! 秘密擦除：私钥标量 / 种子经 `SigningKey` 构造后，栈上副本即刻由调用方（JNI 层 `Zeroizing`）
//! 管理；签名输出非秘密（公可验证），但仍以受管缓冲回写。

use ed25519_dalek::Signer;
use p256::ecdsa::signature::Signer as EcdsaSigner;
use p256::ecdsa::{DerSignature, SigningKey};
use zeroize::Zeroizing;

/// ES256 私钥标量长度（P-256，32 字节无符号大端）。
pub const ES256_SCALAR_LEN: usize = 32;
/// Ed25519 私钥种子长度（RFC 8032，32 字节）。
pub const ED25519_SEED_LEN: usize = 32;

/// ES256 签名：P-256 标量（32 字节无符号大端）+ 报文 → **ASN.1 DER** 签名
/// （确定性 RFC 6979 + SHA-256，与 BC 生产路径逐字节一致）。
///
/// 参数闸门：`scalar.len() == 32` 且为合法 P-256 标量（非零且 < 曲线阶，
/// RustCrypto `from_bytes` 内建校验）——非法返回 `None`。
pub fn es256_sign_der(scalar: &[u8], data: &[u8]) -> Option<Zeroizing<Vec<u8>>> {
    if scalar.len() != ES256_SCALAR_LEN {
        return None;
    }
    let signing = SigningKey::from_bytes(scalar.into()).ok()?;
    let signature: DerSignature = signing.sign(data);
    Some(Zeroizing::new(signature.to_bytes().into()))
}

/// Ed25519 签名：种子（32 字节）+ 报文 → **64 字节 raw** 签名（RFC 8032 确定性）。
///
/// 参数闸门：`seed.len() == 32`——不符返回 `None`。
pub fn ed25519_sign_raw(seed: &[u8], data: &[u8]) -> Option<Zeroizing<Vec<u8>>> {
    if seed.len() != ED25519_SEED_LEN {
        return None;
    }
    let signing = ed25519_dalek::SigningKey::from_bytes(seed.try_into().ok()?);
    Some(Zeroizing::new(signing.sign(data).to_bytes().to_vec()))
}

#[cfg(test)]
#[path = "tests/passkey_sign_tests.rs"]
mod tests;
