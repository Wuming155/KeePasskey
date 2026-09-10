//! KeePasskey Argon2 原生 KDF 内核（Rust 秘密飞地 PoC · Batch 1）
//!
//! 目标：以 RustCrypto `argon2` 替代 `crypto/src/main/cpp/` 的 vendored PHC C 参考实现 +
//! 手写 JNI 桥，做到对 Kotlin 层零改动的 drop-in 替换，并把秘密擦除从「C 手动 malloc/wipe/free」
//! 升级为「Rust `zeroize` RAII 全路径确定性擦除」。
//!
//! 本文件（Batch 1）仅提供**纯函数** [`derive`]，复刻 C 桥 `keepasskey_argon2_jni.c` 的参数闸门，
//! 不含任何 JNI/Android 依赖，可在宿主 `cargo test` 下与 IETF 官方 KAT + 项目 BouncyCastle
//! 冻结向量逐字节对照。JNI 边界见 `jni_bridge.rs`（Batch 2）。
//!
//! 字段与语义对齐（见 NativeArgon2.deriveKey / C 桥）：
//! - `alg_type`：0 = Argon2d，2 = Argon2id（其余非法）；KDBX4 不使用 Argon2i(1)。
//! - `version`：0x10(16) / 0x13(19)（其余非法）。
//! - `memory_kib`：以 KiB 计，须 ≥ 8 × parallelism（对齐 C 闸门与 RustCrypto MIN_M_COST×p_cost）。
//! - `secret`(K) / `ad`(X)：KDBX4 罕见可选参数；`ad` 受 RustCrypto `AssociatedData::MAX_LEN=32`
//!   限制（风险 R2），> 32B 时 [`derive`] 返回 `None`（C/BC 则接受任意长度）。

pub mod aes_kdf;
mod jni_bridge;
mod jni_bridge_ext;
pub mod strength;
pub mod twofish_cbc;

use argon2::{Algorithm, AssociatedData, Argon2, ParamsBuilder, Version};
use zeroize::Zeroizing;

/// 派生输出长度（对齐 C 桥 `KP_OUT_LEN`）。
pub const OUT_LEN: usize = 32;

/// argon2_type：Argon2d（对齐 NativeArgon2.TYPE_ARGON2D）。
pub const TYPE_ARGON2D: u32 = 0;
/// argon2_type：Argon2id（对齐 NativeArgon2.TYPE_ARGON2ID）。
pub const TYPE_ARGON2ID: u32 = 2;

/// Argon2 版本 0x10。
pub const ARGON2_VERSION_10: u32 = 0x10;
/// Argon2 版本 0x13。
pub const ARGON2_VERSION_13: u32 = 0x13;

/// RustCrypto `AssociatedData` 上限（argon2 0.5.3 `Params::MAX_DATA_LEN`）——风险 R2 硬约束。
pub const MAX_AD_LEN: usize = 32;

/// Argon2 密钥派生（纯函数，无 JNI/Android 依赖）。
///
/// 复刻 C 桥 `Java_..._deriveKey` 的参数闸门与 null 语义：任何非法参数、分配/派生失败、
/// 或 AD 超过 [`MAX_AD_LEN`] 均返回 `None`（对应 JNI 层返回 `null` → Kotlin `KdfException`）。
///
/// 秘密擦除：派生输出经 [`Zeroizing`] 包装，函数返回（含提前 `?`/错误路径）即确定性归零；
/// 入参 `password/salt/secret/ad` 为借用切片，其擦除责任在调用方（JNI 层用 `Zeroizing<Vec<u8>>`）。
#[allow(clippy::too_many_arguments)]
pub fn derive(
    password: &[u8],
    salt: &[u8],
    secret: Option<&[u8]>,
    ad: Option<&[u8]>,
    iterations: u32,
    memory_kib: u32,
    parallelism: u32,
    version: u32,
    alg_type: u32,
) -> Option<[u8; OUT_LEN]> {
    // —— 参数闸门（逐条对齐 C 桥 keepasskey_argon2_jni.c:55-58）——
    let algorithm = match alg_type {
        TYPE_ARGON2D => Algorithm::Argon2d,
        TYPE_ARGON2ID => Algorithm::Argon2id,
        _ => return None, // type != Argon2_d && type != Argon2_id
    };
    let version = match version {
        ARGON2_VERSION_10 => Version::V0x10,
        ARGON2_VERSION_13 => Version::V0x13,
        _ => return None,
    };
    if iterations < 1 || parallelism < 1 {
        return None;
    }
    // C: memoryKib < 8 * parallelism → NULL（防 8×p 下界；saturating 防 u32 溢出绕过）
    if memory_kib < 8u32.saturating_mul(parallelism) {
        return None;
    }
    // R2：AD 超过 RustCrypto 32B 上限则无法表达，fail-closed 返回 None（由上层回退 BC）。
    if let Some(ad) = ad {
        if ad.len() > MAX_AD_LEN {
            return None;
        }
    }

    // —— 构造 Params（AD 经 ParamsBuilder::data 注入 H0 的 X 段）——
    let mut builder = ParamsBuilder::new();
    builder
        .m_cost(memory_kib)
        .t_cost(iterations)
        .p_cost(parallelism)
        .output_len(OUT_LEN);
    if let Some(ad) = ad {
        if !ad.is_empty() {
            // AssociatedData::new 对 > MAX_LEN 返回 Err；上面已闸门，此处 ok()? 为纵深防御。
            builder.data(AssociatedData::new(ad).ok()?);
        }
    }
    let params = builder.build().ok()?;

    // —— secret(K) 经 new_with_secret 注入 H0 的 K 段；空 secret 等价于无 secret（H0 均为 len=0）——
    let ctx = match secret {
        Some(s) if !s.is_empty() => Argon2::new_with_secret(s, algorithm, version, params).ok()?,
        _ => Argon2::new(algorithm, version, params),
    };

    let mut out = Zeroizing::new([0u8; OUT_LEN]);
    ctx.hash_password_into(password, salt, out.as_mut_slice())
        .ok()?;
    Some(*out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn hex(s: &str) -> Vec<u8> {
        hex::decode(s.trim()).expect("非法 hex")
    }

    // ============ 1) IETF draft-irtf-cfrg-argon2-12 §5 官方 KAT（独立第三方对照）============
    // 参数：m=32 KiB, t=3, p=4, AD=[0x04;12], secret=[0x03;8], pwd=[0x01;32], salt=[0x02;16]
    // 取自 argon2 0.5.3 crate tests/kat.rs（其源头为 Argon2 参考实现 + IETF draft）。
    fn official(alg_type: u32, version: u32) -> Option<[u8; OUT_LEN]> {
        derive(
            &[0x01u8; 32],
            &[0x02u8; 16],
            Some(&[0x03u8; 8]),
            Some(&[0x04u8; 12]),
            3,
            32,
            4,
            version,
            alg_type,
        )
    }

    #[test]
    fn official_kat_argon2d_v0x10() {
        let out = official(TYPE_ARGON2D, ARGON2_VERSION_10).expect("d/v0x10 应成功");
        assert_eq!(
            hex::encode(out),
            "96a9d4e5a1734092c85e29f410a45914a5dd1f5cbf08b2670da68a0285abf32b"
        );
    }

    #[test]
    fn official_kat_argon2id_v0x10() {
        let out = official(TYPE_ARGON2ID, ARGON2_VERSION_10).expect("id/v0x10 应成功");
        assert_eq!(
            hex::encode(out),
            "b64615f07789b66b645b67ee9ed3b377ae350b6bfcbb0fc95141ea8f322613c0"
        );
    }

    #[test]
    fn official_kat_argon2d_v0x13() {
        let out = official(TYPE_ARGON2D, ARGON2_VERSION_13).expect("d/v0x13 应成功");
        assert_eq!(
            hex::encode(out),
            "512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb"
        );
    }

    #[test]
    fn official_kat_argon2id_v0x13() {
        let out = official(TYPE_ARGON2ID, ARGON2_VERSION_13).expect("id/v0x13 应成功");
        assert_eq!(
            hex::encode(out),
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"
        );
    }

    // ============ 2) 与项目 BouncyCastle 冻结向量逐字节等价（Batch 0 产出）============
    // 读取 crypto/src/test/resources/argon2-interop/argon2-bc-vectors.json。
    // AD ≤ 32B 的向量须与 BC 完全一致；AD > 32B 的 R2 探针须返回 None（BC 有值、Rust 受 32B 上限）。
    #[test]
    fn bc_frozen_vectors_equivalence() {
        let manifest = env!("CARGO_MANIFEST_DIR");
        let path = Path::new(manifest)
            .join("../../../src/test/resources/argon2-interop/argon2-bc-vectors.json");
        let raw = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("读取 BC 向量失败 {}: {}", path.display(), e));
        let json: serde_json::Value = serde_json::from_str(&raw).expect("向量 JSON 解析失败");
        let vectors = json["vectors"].as_array().expect("缺少 vectors 数组");
        assert!(!vectors.is_empty(), "向量为空");

        let mut compared = 0usize;
        let mut r2_capped = 0usize;
        for v in vectors {
            let name = v["name"].as_str().unwrap().to_string();
            let alg_type = v["type"].as_u64().unwrap() as u32;
            let version = v["version"].as_u64().unwrap() as u32;
            let iterations = v["iterations"].as_u64().unwrap() as u32;
            let memory_kib = v["memoryKib"].as_u64().unwrap() as u32;
            let parallelism = v["parallelism"].as_u64().unwrap() as u32;
            let password = hex(v["passwordHex"].as_str().unwrap());
            let salt = hex(v["saltHex"].as_str().unwrap());
            let secret = v["secretHex"].as_str().map(hex);
            let ad = v["adHex"].as_str().map(hex);

            let got = derive(
                &password,
                &salt,
                secret.as_deref(),
                ad.as_deref(),
                iterations,
                memory_kib,
                parallelism,
                version,
                alg_type,
            );

            let ad_len = ad.as_ref().map(|a| a.len()).unwrap_or(0);
            if ad_len > MAX_AD_LEN {
                // R2：BC 可派生长 AD，RustCrypto 0.5.3 上限 32B → derive 必须 fail-closed 返回 None
                assert!(
                    got.is_none(),
                    "[{name}] AD={ad_len}B 超 32B 上限，Rust 应返回 None（R2 fail-closed）"
                );
                r2_capped += 1;
            } else {
                let expected = hex(v["expectedOutHex"].as_str().unwrap());
                let got = got.unwrap_or_else(|| panic!("[{name}] Rust 派生失败但 BC 有值"));
                assert_eq!(
                    hex::encode(got),
                    hex::encode(&expected),
                    "[{name}] Rust 输出与 BC 冻结向量不一致"
                );
                compared += 1;
            }
        }
        // 14 条语料：12 条 AD≤32 逐字节等价，2 条 64B 长 AD 探针命中 R2 上限
        assert_eq!(compared, 12, "应逐字节等价 12 条 AD≤32 向量");
        assert_eq!(r2_capped, 2, "应有 2 条长 AD 探针命中 R2 32B 上限");
    }

    // ============ 3) 参数闸门负例（复刻 C 桥非法返回 NULL 语义）============
    #[test]
    fn gate_rejects_invalid_params() {
        let pwd = [0x01u8; 32];
        let salt = [0x02u8; 16];
        // 合法基线（应成功）
        assert!(derive(&pwd, &salt, None, None, 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).is_some());

        // 非法 type（1=Argon2i 不在 KDBX 域；3 越界）
        assert!(derive(&pwd, &salt, None, None, 2, 256, 2, ARGON2_VERSION_13, 1).is_none());
        assert!(derive(&pwd, &salt, None, None, 2, 256, 2, ARGON2_VERSION_13, 3).is_none());

        // 非法 version
        assert!(derive(&pwd, &salt, None, None, 2, 256, 2, 0x11, TYPE_ARGON2ID).is_none());

        // iterations < 1
        assert!(derive(&pwd, &salt, None, None, 0, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).is_none());

        // parallelism < 1
        assert!(derive(&pwd, &salt, None, None, 2, 256, 0, ARGON2_VERSION_13, TYPE_ARGON2ID).is_none());

        // memoryKib < 8 * parallelism（p=4 需 ≥32）
        assert!(derive(&pwd, &salt, None, None, 2, 31, 4, ARGON2_VERSION_13, TYPE_ARGON2ID).is_none());
        assert!(derive(&pwd, &salt, None, None, 2, 32, 4, ARGON2_VERSION_13, TYPE_ARGON2ID).is_some());

        // salt 过短（< MIN_SALT_LEN=8）→ RustCrypto Err(SaltTooShort) → None
        assert!(derive(&pwd, &[0x02u8; 7], None, None, 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).is_none());

        // AD 恰好 32B 合法；33B 越界（R2）
        assert!(derive(&pwd, &salt, None, Some(&[0x04u8; 32]), 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).is_some());
        assert!(derive(&pwd, &salt, None, Some(&[0x04u8; 33]), 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).is_none());
    }

    // ============ 4) 确定性 + secret/AD 影响输出（非直通）============
    #[test]
    fn deterministic_and_secret_ad_matter() {
        let pwd = [0x01u8; 32];
        let salt = [0x02u8; 16];
        let base = derive(&pwd, &salt, None, None, 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).unwrap();
        // 确定性
        let again = derive(&pwd, &salt, None, None, 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).unwrap();
        assert_eq!(base, again);
        // secret 改变输出
        let with_secret =
            derive(&pwd, &salt, Some(&[0x03u8; 16]), None, 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).unwrap();
        assert_ne!(base, with_secret);
        // AD 改变输出
        let with_ad =
            derive(&pwd, &salt, None, Some(&[0x04u8; 12]), 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).unwrap();
        assert_ne!(base, with_ad);
        // 空 secret / 空 AD 等价于 None（H0 中 len=0）
        let empty_secret = derive(&pwd, &salt, Some(&[]), None, 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).unwrap();
        assert_eq!(base, empty_secret);
        let empty_ad = derive(&pwd, &salt, None, Some(&[]), 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID).unwrap();
        assert_eq!(base, empty_ad);
    }
}
