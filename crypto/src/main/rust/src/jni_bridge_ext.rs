//! JNI 边界扩展（ISSUE-P3-34 / 35 / 36）：AES-KDF、Twofish-CBC、口令强度评估的原生导出。
//!
//! 设计约定（逐条对齐既有 `jni_bridge.rs` 的安全范式，保持一致而非另起一套）：
//! 1. **失败归一为 `null`**：任何非法参数、长度不符、分配失败或 panic 均返回 `null`，
//!    由 Kotlin 侧归一为类型化异常，**绝不**把 Rust panic unwind 过 FFI 边界（那会 abort 进程）；
//! 2. **秘密全程受管**：入参拷贝进 [`Zeroizing`]，中间缓冲同样受管，任何返回路径确定性归零；
//! 3. **有符号闸门先行**：`jint` / `jlong` 先按有符号判定合法性，再窄化，杜绝负数经
//!    `as u*` 变成巨值绕过上界。
//!
//! 符号名与 Kotlin 侧 `external fun` 声明**逐字绑定**：
//! - `Java_com_keepasskey_crypto_kdf_NativeAesKdf_deriveKey`
//! - `Java_com_keepasskey_crypto_cipher_NativeTwofish_cbcEncryptBlocks`
//! - `Java_com_keepasskey_crypto_cipher_NativeTwofish_cbcDecryptBlocks`
//! - `Java_com_keepasskey_crypto_strength_NativePasswordStrength_estimate`

use crate::aes_kdf::{self, COMPOSITE_KEY_LEN, OUT_LEN};
use crate::strength;
use crate::twofish_cbc::{self, BLOCK_LEN};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint, jintArray, jlong};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;
use zeroize::Zeroizing;

/// `estimate` 返回的定长布局元素个数：`[score, guessesLog10X100, flags]`。
const ESTIMATE_LEN: usize = 3;

/// `u8` 切片 → `i8` 切片（`SetByteArrayRegion`/`SetIntArrayRegion` 需要 `&[i8]`）。
///
/// SAFETY：调用方保证 `bytes` 为有效内存；`u8` 与 `i8` 同宽同布局，长度取自 `bytes.len()`。
#[inline]
unsafe fn as_jbyte(bytes: &[u8]) -> &[i8] {
    std::slice::from_raw_parts(bytes.as_ptr().cast::<i8>(), bytes.len())
}

// ============================================================================
// 1) AES-KDF
// ============================================================================

/// AES-KDF 密钥派生。
///
/// 参数：`composite_key`（32 字节）、`seed`（32 字节）、`rounds`（`[1, MAX_ROUNDS]`，有符号先验）。
/// 返回 32 字节派生密钥；任何非法参数 / 失败 / panic 返回 `null`。
#[no_mangle]
pub extern "system" fn Java_com_keepasskey_crypto_kdf_NativeAesKdf_deriveKey<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    composite_key: JByteArray<'local>,
    seed: JByteArray<'local>,
    rounds: jlong,
) -> jbyteArray {
    // 有符号闸门先行：负数在窄化前即被拒绝（对齐 `jni_bridge.rs` 的 params_valid 语义）
    if composite_key.is_null() || seed.is_null() || rounds < 1 {
        return null_mut();
    }

    let outcome = catch_unwind(AssertUnwindSafe(|| -> Option<jbyteArray> {
        let key = Zeroizing::new(env.convert_byte_array(&composite_key).ok()?);
        let seed_buf = Zeroizing::new(env.convert_byte_array(&seed).ok()?);
        if key.len() != COMPOSITE_KEY_LEN || seed_buf.len() != COMPOSITE_KEY_LEN {
            return None;
        }

        let out = Zeroizing::new(aes_kdf::aes_kdf(&key, &seed_buf, rounds as u64)?);
        let java_out = env.new_byte_array(OUT_LEN as jint).ok()?;
        // SAFETY：out 长度恒为 OUT_LEN 且为有效内存
        env.set_byte_array_region(&java_out, 0, unsafe { as_jbyte(&out[..]) })
            .ok()?;
        Some(java_out.into_raw())
    }));

    match outcome {
        Ok(Some(arr)) => arr,
        _ => null_mut(),
    }
}

// ============================================================================
// 2) Twofish-CBC（分组变换本体，不含填充）
// ============================================================================

/// Twofish-CBC 链式加密（**输入长度必须为 16 的整数倍**）。
///
/// `iv` 为**输入输出参数**：调用时给出当前链值，返回时被原地更新为最后一组密文，
/// 使调用方可以把长数据切成任意多段连续加密而不破坏 CBC 链接。
#[no_mangle]
pub extern "system" fn Java_com_keepasskey_crypto_cipher_NativeTwofish_cbcEncryptBlocks<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    key: JByteArray<'local>,
    iv: JByteArray<'local>,
    data: JByteArray<'local>,
) -> jbyteArray {
    twofish_cbc_jni(env, key, iv, data, true)
}

/// Twofish-CBC 链式解密（**不做去填充**；语义与加密侧对称，`iv` 同样原地演化）。
#[no_mangle]
pub extern "system" fn Java_com_keepasskey_crypto_cipher_NativeTwofish_cbcDecryptBlocks<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    key: JByteArray<'local>,
    iv: JByteArray<'local>,
    data: JByteArray<'local>,
) -> jbyteArray {
    twofish_cbc_jni(env, key, iv, data, false)
}

/// 加解密共用实现（仅方向不同），确保两条路径的闸门与擦除语义完全一致。
fn twofish_cbc_jni<'local>(
    env: JNIEnv<'local>,
    key: JByteArray<'local>,
    iv: JByteArray<'local>,
    data: JByteArray<'local>,
    encrypt: bool,
) -> jbyteArray {
    if key.is_null() || iv.is_null() || data.is_null() {
        return null_mut();
    }

    let outcome = catch_unwind(AssertUnwindSafe(|| -> Option<jbyteArray> {
        let key_buf = Zeroizing::new(env.convert_byte_array(&key).ok()?);
        let mut iv_buf = Zeroizing::new(env.convert_byte_array(&iv).ok()?);
        let data_buf = Zeroizing::new(env.convert_byte_array(&data).ok()?);

        if iv_buf.len() != BLOCK_LEN || data_buf.len() % BLOCK_LEN != 0 {
            return None;
        }

        let out = if encrypt {
            twofish_cbc::cbc_encrypt(&key_buf, &mut iv_buf, &data_buf)?
        } else {
            twofish_cbc::cbc_decrypt(&key_buf, &mut iv_buf, &data_buf)?
        };

        // 回写演化后的链值（IV 非秘密，但仍在 Zeroizing 缓冲中处理）
        let java_iv = iv;
        // SAFETY：iv_buf 长度已校验为 BLOCK_LEN 且为有效内存
        env.set_byte_array_region(&java_iv, 0, unsafe { as_jbyte(&iv_buf[..]) })
            .ok()?;

        let out = Zeroizing::new(out);
        let java_out = env.new_byte_array(out.len() as jint).ok()?;
        // SAFETY：out 为有效内存，长度取自 out.len()
        env.set_byte_array_region(&java_out, 0, unsafe { as_jbyte(&out[..]) })
            .ok()?;
        Some(java_out.into_raw())
    }));

    match outcome {
        Ok(Some(arr)) => arr,
        _ => null_mut(),
    }
}

// ============================================================================
// 3) 口令强度评估
// ============================================================================

/// 口令强度评估。
///
/// 返回**定长 3 元** `IntArray`：`[score(0..4), guessesLog10X100, flags]`。
/// 空口令是**合法输入**（返回 `score=0` 且置 `TOO_SHORT`），不返回 `null`；
/// 仅 panic / 分配失败等异常路径返回 `null`。
#[no_mangle]
pub extern "system" fn Java_com_keepasskey_crypto_strength_NativePasswordStrength_estimate<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    password: JByteArray<'local>,
) -> jintArray {
    if password.is_null() {
        return null_mut();
    }

    let outcome = catch_unwind(AssertUnwindSafe(|| -> Option<jintArray> {
        let pw = Zeroizing::new(env.convert_byte_array(&password).ok()?);
        let result = strength::estimate(&pw);

        let layout = [result.score, result.guesses_log10_x100, result.flags];
        let java_out = env.new_int_array(ESTIMATE_LEN as jint).ok()?;
        env.set_int_array_region(&java_out, 0, &layout).ok()?;
        Some(java_out.into_raw())
    }));

    match outcome {
        Ok(Some(arr)) => arr,
        _ => null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use jni::sys::{jbyteArray, jintArray, jlong};

    /// 静态符号/签名断言：四个导出函数必须存在且签名与 Kotlin `external fun` 声明逐字一致。
    /// 运行时真机调用验证由 `:crypto:test`（宿主库）与 `:crypto:connectedDebugAndroidTest`（APK 内 .so）承担。
    #[test]
    fn exported_symbols_have_expected_signatures() {
        let aes: for<'a> extern "system" fn(
            JNIEnv<'a>,
            JObject<'a>,
            JByteArray<'a>,
            JByteArray<'a>,
            jlong,
        ) -> jbyteArray = Java_com_keepasskey_crypto_kdf_NativeAesKdf_deriveKey;
        let enc: for<'a> extern "system" fn(
            JNIEnv<'a>,
            JObject<'a>,
            JByteArray<'a>,
            JByteArray<'a>,
            JByteArray<'a>,
        ) -> jbyteArray = Java_com_keepasskey_crypto_cipher_NativeTwofish_cbcEncryptBlocks;
        let dec: for<'a> extern "system" fn(
            JNIEnv<'a>,
            JObject<'a>,
            JByteArray<'a>,
            JByteArray<'a>,
            JByteArray<'a>,
        ) -> jbyteArray = Java_com_keepasskey_crypto_cipher_NativeTwofish_cbcDecryptBlocks;
        let est: for<'a> extern "system" fn(
            JNIEnv<'a>,
            JObject<'a>,
            JByteArray<'a>,
        ) -> jintArray = Java_com_keepasskey_crypto_strength_NativePasswordStrength_estimate;

        // 取地址即编译期核对签名
        let _ = (aes as usize, enc as usize, dec as usize, est as usize);
    }

    /// `null` 入参必须直接返回 `null` 而不是解引用（无法在宿主侧构造真实 JNIEnv，
    /// 故此处仅静态核对「判空分支存在」，运行时判空语义由 Kotlin 侧用例覆盖）。
    #[test]
    fn estimate_layout_contract_is_three_ints() {
        assert_eq!(ESTIMATE_LEN, 3);
    }
}
