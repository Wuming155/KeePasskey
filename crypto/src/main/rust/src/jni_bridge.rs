//! JNI 边界（Rust 秘密飞地 PoC · Batch 2）
//!
//! 导出与既有 C 桥 `keepasskey_argon2_jni.c` **符号名与签名逐字一致**的原生方法：
//! `Java_com_keepasskey_crypto_kdf_NativeArgon2_deriveKey`。Kotlin 侧 `NativeArgon2`
//! （库名 `keepasskey_argon2`、`external fun deriveKey(...)`、null 语义）**零改动** drop-in。
//!
//! 相对 C 桥的安全增益：
//! 1. **秘密确定性擦除**：password/salt/secret/AD 拷入 `Zeroizing<Vec<u8>>`，派生输出拷入
//!    `Zeroizing<[u8;32]>`——无论正常返回还是任一 `?` 提前返回/错误路径，RAII 保证归零，
//!    消除 C 侧「忘记 kp_wipe / 错误路径漏擦」隐患。
//! 2. **panic 不跨 FFI**：整个函数体裹 `catch_unwind`，任何 Rust panic 归一为返回 `null`
//!    （对齐 C 的失败返回 NULL），绝不 unwind 过 JNI 边界（那会直接 abort App 进程）。
//! 3. **有符号闸门先行**：C 桥在 `(uint32_t)` 转换**之前**以 `jint` 判负；此处同样先以有符号
//!    `jint` 复刻闸门（`params_valid`），再转 `u32` 调 [`crate::derive`]，杜绝负值经 `as u32`
//!    变巨值绕过下界检查。

use crate::{derive, ARGON2_VERSION_10, ARGON2_VERSION_13, OUT_LEN, TYPE_ARGON2D, TYPE_ARGON2ID};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;
use zeroize::Zeroizing;

/// 参数闸门（逐条对齐 C 桥 `keepasskey_argon2_jni.c:55-58`，基于**有符号** jint）。
///
/// 返回 false 即对应 C 的 `return NULL`：type∉{0,2} / version∉{0x10,0x13} /
/// iterations<1 / parallelism<1 / memoryKib<8×parallelism。内存下界用 i64 计算防溢出绕过。
fn params_valid(
    alg_type: jint,
    version: jint,
    iterations: jint,
    memory_kib: jint,
    parallelism: jint,
) -> bool {
    if alg_type != TYPE_ARGON2D as jint && alg_type != TYPE_ARGON2ID as jint {
        return false;
    }
    if version != ARGON2_VERSION_10 as jint && version != ARGON2_VERSION_13 as jint {
        return false;
    }
    if iterations < 1 || parallelism < 1 {
        return false;
    }
    if (memory_kib as i64) < 8i64 * (parallelism as i64) {
        return false;
    }
    true
}

/// Argon2 密钥派生 JNI 原生方法（符号/签名与既有 C 桥完全一致）。
///
/// 参数：`password`/`salt` 非空 `ByteArray`；`secret`/`associatedData` 可为 null；
/// 其余为 `Int`。返回 32B `ByteArray`；任何非法参数 / 派生失败 / 分配失败 / panic 均返回 `null`。
#[no_mangle]
pub extern "system" fn Java_com_keepasskey_crypto_kdf_NativeArgon2_deriveKey<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    password: JByteArray<'local>,
    salt: JByteArray<'local>,
    secret: JByteArray<'local>,
    associated_data: JByteArray<'local>,
    iterations: jint,
    memory_kib: jint,
    parallelism: jint,
    version: jint,
    alg_type: jint,
) -> jbyteArray {
    // C: if (password == NULL || salt == NULL) return NULL;
    if password.is_null() || salt.is_null() {
        return null_mut();
    }
    // C: 参数闸门（有符号先行）
    if !params_valid(alg_type, version, iterations, memory_kib, parallelism) {
        return null_mut();
    }

    // FFI 体裹 catch_unwind：panic → null，绝不跨 JNI 边界 unwind
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Option<jbyteArray> {
        // 拷入即受管：敏感输入全部 Zeroizing，任何返回路径（含 ? 提前返回）确定性擦除。
        // convert_byte_array 对 null 数组会 Err，故 secret/AD 先判空再拷贝。
        let pwd = Zeroizing::new(env.convert_byte_array(&password).ok()?);
        let salt_buf = Zeroizing::new(env.convert_byte_array(&salt).ok()?);
        let secret_buf = if secret.is_null() {
            None
        } else {
            Some(Zeroizing::new(env.convert_byte_array(&secret).ok()?))
        };
        let ad_buf = if associated_data.is_null() {
            None
        } else {
            Some(Zeroizing::new(env.convert_byte_array(&associated_data).ok()?))
        };

        // 闸门已在上面以有符号 jint 通过，此处 as u32 安全；derive 内部另有冗余闸门。
        let out_bytes = derive(
            &pwd,
            &salt_buf,
            secret_buf.as_deref().map(Vec::as_slice),
            ad_buf.as_deref().map(Vec::as_slice),
            iterations as u32,
            memory_kib as u32,
            parallelism as u32,
            version as u32,
            alg_type as u32,
        )?;

        // 输出缓冲同样受管：写入 Java 数组后，Zeroizing 在闭包返回时归零本地副本。
        let out = Zeroizing::new(out_bytes);
        let java_out = env.new_byte_array(OUT_LEN as jint).ok()?;
        // u8 → jbyte(i8)：同宽同对齐的位重解释（SetByteArrayRegion 需要 &[i8]）。
        // SAFETY: out 为 [u8; 32] 有效内存，i8 与 u8 布局一致，长度取自 out.len()。
        let as_jbyte: &[i8] =
            unsafe { std::slice::from_raw_parts(out.as_ptr().cast::<i8>(), out.len()) };
        env.set_byte_array_region(&java_out, 0, as_jbyte).ok()?;
        Some(java_out.into_raw())
    }));

    match outcome {
        Ok(Some(arr)) => arr,
        // panic 或任何失败 → null（对齐 C 桥失败返回 NULL；Kotlin 归一为 KdfException）
        _ => null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 有符号闸门负例：确保负值在 `as u32` 之前被拦截，复刻 C 桥语义。
    #[test]
    fn gate_mirrors_c_on_signed_jint() {
        // 合法
        assert!(params_valid(2, 0x13, 2, 256, 2));
        assert!(params_valid(0, 0x10, 1, 8, 1));
        assert!(params_valid(2, 0x13, 2, 32, 4)); // m = 8p 边界
        // 非法 type（Argon2i=1 不在 KDBX 域；3 越界）
        assert!(!params_valid(1, 0x13, 2, 256, 2));
        assert!(!params_valid(3, 0x13, 2, 256, 2));
        // 非法 version
        assert!(!params_valid(2, 0x11, 2, 256, 2));
        // t / p < 1，且负值必须被拦截（不能经 as u32 变巨值绕过）
        assert!(!params_valid(2, 0x13, 0, 256, 2));
        assert!(!params_valid(2, 0x13, -1, 256, 2));
        assert!(!params_valid(2, 0x13, 2, 256, 0));
        assert!(!params_valid(2, 0x13, 2, 256, -1));
        // memoryKib < 8×parallelism
        assert!(!params_valid(2, 0x13, 2, 31, 4));
    }

    /// 静态符号/签名断言：导出函数存在且签名与既有 C 桥逐字对齐（#[no_mangle] 固定符号名）。
    /// 运行时真机调用验证放 Batch 4（androidTest）；.so 符号表 readelf 核对放 Batch 3。
    #[test]
    fn exported_symbol_has_c_parity_signature() {
        let f: for<'a> extern "system" fn(
            JNIEnv<'a>,
            JObject<'a>,
            JByteArray<'a>,
            JByteArray<'a>,
            JByteArray<'a>,
            JByteArray<'a>,
            jint,
            jint,
            jint,
            jint,
            jint,
        ) -> jbyteArray = Java_com_keepasskey_crypto_kdf_NativeArgon2_deriveKey;
        // 取地址即证明符号存在且类型完全匹配（编译期核对）
        let _ = f as usize;
    }
}
