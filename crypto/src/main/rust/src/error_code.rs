//! JNI 边界失败原因码（`ISSUE-P2` C4）。
//!
//! ## 为何存在
//!
//! 各导出函数沿用既有契约：**失败返回 `null` / `-1`**。这对调用方足够（Kotlin 归一为类型化异常），
//! 但**丢失了失败原因**——`catch_unwind` 把 panic、非法入参、JNI 环境错误、内核运算失败
//! 压成同一个空值，诊断时无法区分「库坏了」与「我们传错了参数」。
//!
//! 本模块给每条失败路径标一个**线程局部**原因码，导出函数在返回失败值**之前**写入；调用方在拿到
//! 失败值后调用 [`Java_com_keepasskey_crypto_NativeErrorCode_lastErrorCode`] 读取。
//!
//! ## 契约
//!
//! - **不改既有返回语义**：失败仍是 `null` / `-1`，新增的是**旁路查询**，旧调用方零改动；
//! - **线程局部**：一次失败的原因只对发起该次调用的线程可见，不跨线程串味；
//! - **成功即清零**：每次进入导出函数先置 [`ERR_NONE`]，成功路径不会残留上一次的失败码；
//! - **不承载秘密**：只有枚举值，无参数、无长度、无数据内容。
//!
//! ## 边界（如实声明）
//!
//! 原因码是**尽力而为**的标注：`?` 提前返回的中间步骤若未显式记录，会保留最后一次显式记录的值
//! （缺省为 [`ERR_KERNEL`]）。因此它用于**定位**（「是 panic 还是入参」），不作为安全判据——
//! 任何失败码都必须按失败处理，不得据此放行。

use jni::objects::JObject;
use jni::sys::jint;
use jni::JNIEnv;
use std::cell::Cell;

/// 无失败（成功，或尚未发生调用）。
pub const ERR_NONE: i32 = 0;
/// 入参非法：null 数组 / 参数闸门不过 / 长度不符。
pub const ERR_INVALID_ARGUMENT: i32 = 1;
/// JNI 环境错误：`convert_byte_array` / `new_byte_array` / `set_byte_array_region` 等失败。
pub const ERR_JNI: i32 = 2;
/// 内核返回失败：运算本身拒绝该输入（含内部分配失败）。
pub const ERR_KERNEL: i32 = 3;
/// Rust panic 被 `catch_unwind` 拦截（进程未 abort）。
pub const ERR_PANIC: i32 = 4;

thread_local! {
    static LAST_ERROR: Cell<i32> = const { Cell::new(ERR_NONE) };
}

/// 记录本次调用的失败原因（成功路径应记录 [`ERR_NONE`]）。
#[inline]
pub fn record(code: i32) {
    LAST_ERROR.with(|slot| slot.set(code));
}

/// 读取当前线程最近一次失败原因。
#[inline]
pub fn last() -> i32 {
    LAST_ERROR.with(Cell::get)
}

/// 进入导出函数时复位原因码（避免上一次调用的失败码污染本次成功路径）。
#[inline]
pub fn reset() {
    record(ERR_NONE);
}

/// `body` 未显式标注原因时，把「返回失败值」记成内核拒绝（最保守的归类）。
#[inline]
pub fn note_kernel_if_unset() {
    if last() == ERR_NONE {
        record(ERR_KERNEL);
    }
}

/// JNI 调用失败时标注 [`ERR_JNI`]（供 `map_err` 直接使用）。
#[inline]
pub fn note_jni_error<E>(_err: E) {
    record(ERR_JNI);
}

/// 失败原因码读取入口。
///
/// 调用方在拿到 `null` / `-1` 之后调用本函数，得到 [`ERR_NONE`] 之外的码即失败原因。
/// 读本身**不改变**原因码（可重复读取）；下一次进入任一导出函数时复位。
#[no_mangle]
pub extern "system" fn Java_com_keepasskey_crypto_NativeErrorCode_lastErrorCode<'local>(
    _env: JNIEnv<'local>,
    _thiz: JObject<'local>,
) -> jint {
    last() as jint
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 原因码可写入 / 读取 / 复位，且默认值为 `ERR_NONE`。
    #[test]
    fn record_read_reset_roundtrip() {
        reset();
        assert_eq!(ERR_NONE, last());
        record(ERR_PANIC);
        assert_eq!(ERR_PANIC, last());
        record(ERR_INVALID_ARGUMENT);
        assert_eq!(ERR_INVALID_ARGUMENT, last());
        reset();
        assert_eq!(ERR_NONE, last());
    }

    /// 静态符号断言：读取入口存在且签名与 Kotlin `external fun` 一致。
    #[test]
    fn exported_symbol_has_expected_signature() {
        let f: for<'a> extern "system" fn(JNIEnv<'a>, JObject<'a>) -> jint =
            Java_com_keepasskey_crypto_NativeErrorCode_lastErrorCode;
        let _ = f as usize;
    }

    /// 原因码是线程局部的：子线程的失败不污染调用线程。
    #[test]
    fn error_code_is_thread_local() {
        reset();
        record(ERR_KERNEL);
        std::thread::spawn(|| {
            assert_eq!(ERR_NONE, last());
            record(ERR_PANIC);
        })
        .join()
        .expect("子线程");
        assert_eq!(ERR_KERNEL, last());
    }
}
