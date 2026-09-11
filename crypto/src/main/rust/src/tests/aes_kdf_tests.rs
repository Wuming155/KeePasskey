use super::*;

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// **独立第三方已知答案向量**：由 Python `cryptography` 的 AES-ECB 独立复算
/// （非本实现自身产物，2026-09-10 实算），`rounds = 4` 便于人工逐步复核。
///
/// 复算脚本（实跑输出见下方注释）：
/// ```python
/// from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
/// import hashlib
/// seed = bytes(range(32))          # 0x00..0x1f，作 **AES 密钥**
/// buf  = bytes(range(32, 64))      # 0x20..0x3f，作 **明文**
/// enc  = Cipher(algorithms.AES(seed), modes.ECB()).encryptor()
/// for _ in range(4):
///     buf = enc.update(buf)
/// print(hashlib.sha256(buf).hexdigest())
/// # 逐步输出：
/// # r1 61a6936e4e8f101c1cc1f993b542a0d4e2740e8afad4e4d15d0d661b382eca89
/// # r2 813efd24102eb0bc47c81b02c0d9141810a26b2f74427492a8840d11c832fd2a
/// # r3 383c959652efd1b0666f6c015ccd7a37196638264142d00f2d5baa2adc0703e4
/// # r4 fff93cdce6f238d23ba432aeae3df64b4dd1d3b3a659ec4a4cf6bf5295737ff1
/// ```
#[test]
fn known_answer_vector_matches_independent_recomputation() {
    // seed = 0x00..0x1f（AES 密钥）；composite_key = 0x20..0x3f（明文）
    let mut seed = [0u8; 32];
    let mut key = [0u8; 32];
    for i in 0..32 {
        seed[i] = i as u8;
        key[i] = (i + 32) as u8;
    }
    let out = aes_kdf(&key, &seed, 4).expect("合法参数应派生成功");
    assert_eq!(
        hex(&out),
        "f836155ae7cb1d120da836de66f478ec1dbf042d041d9ca50b49f400d398eff5"
    );
}

/// 防「seed / compositeKey 互换」：交换两个入参必须产出不同结果。
#[test]
fn seed_and_composite_key_are_not_interchangeable() {
    let mut a = [0u8; 32];
    let mut b = [0u8; 32];
    for i in 0..32 {
        a[i] = i as u8;
        b[i] = (i + 32) as u8;
    }
    assert_ne!(aes_kdf(&b, &a, 4).unwrap(), aes_kdf(&a, &b, 4).unwrap());
}

/// 语义锚点：`rounds == 1` 时结果必须等于「单次 AES-ECB 加密后 SHA-256」——
/// 用 RustCrypto `aes` 独立拼装一遍，交叉验证循环未多做／少做一轮。
#[test]
fn rounds_one_equals_single_aes_block_round() {
    let seed = [0x11u8; 32];
    let key = [0x22u8; 32];

    let cipher = Aes256::new_from_slice(&seed).unwrap();
    let mut single = key;
    let (lo, hi) = single.split_at_mut(BLOCK_LEN);
    cipher.encrypt_block(as_block_mut(lo));
    cipher.encrypt_block(as_block_mut(hi));
    let mut hasher = Sha256::new();
    hasher.update(&single[..]);
    let expected = hasher.finalize();

    let got = aes_kdf(&key, &seed, 1).unwrap();
    assert_eq!(got, expected[..]);
}

/// 不同轮数必须产出不同结果（防「常量返回」型假实现）。
#[test]
fn distinct_rounds_yield_distinct_output() {
    let seed = [0x33u8; 32];
    let key = [0x44u8; 32];
    let a = aes_kdf(&key, &seed, 1).unwrap();
    let b = aes_kdf(&key, &seed, 2).unwrap();
    let c = aes_kdf(&key, &seed, 3).unwrap();
    assert_ne!(a, b);
    assert_ne!(b, c);
    assert_ne!(a, c);
}

/// 确定性：同参数重复派生结果一致。
#[test]
fn deterministic() {
    let seed = [0x55u8; 32];
    let key = [0x66u8; 32];
    assert_eq!(aes_kdf(&key, &seed, 16).unwrap(), aes_kdf(&key, &seed, 16).unwrap());
}

/// 参数闸门负例：长度、轮数越界一律 fail-closed 返回 None。
#[test]
fn gate_rejects_invalid_params() {
    let seed = [0u8; 32];
    let key = [0u8; 32];
    // 长度不足 / 超长
    assert!(aes_kdf(&key[..31], &seed, 1).is_none());
    assert!(aes_kdf(&key, &seed[..16], 1).is_none());
    assert!(aes_kdf(&[], &seed, 1).is_none());
    // 轮数 0 / 越界（含 MAX_ROUNDS + 1 与 u64::MAX）——上界之外必须立即返回 None，
    // 而非进入循环；注意此处**不可**断言 MAX_ROUNDS 本身成功（2.68 亿轮在 debug 下不可接受）
    assert!(aes_kdf(&key, &seed, 0).is_none());
    assert!(aes_kdf(&key, &seed, MAX_ROUNDS + 1).is_none());
    assert!(aes_kdf(&key, &seed, u64::MAX).is_none());
    // 合法下界
    assert!(aes_kdf(&key, &seed, 1).is_some());
}
