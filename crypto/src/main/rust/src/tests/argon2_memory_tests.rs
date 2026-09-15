//! ISSUE-P2-56（审计 RUST-01）回归：m_cost 工作内存自持 `Zeroizing<Vec<Block>>` 清零。
//!
//! 测试落位遵守 ISSUE-P3-57 约定（`tests/<name>_tests.rs` + `#[path]` 引用，
//! 避免内联 mod tests 的测试向量被 CodeQL `rust/hard-coded-cryptographic-value` 逐条误报）。

use crate::derive;
use crate::{ARGON2_VERSION_13, OUT_LEN, TYPE_ARGON2ID};
use argon2::Block;
use zeroize::Zeroize;

/// 自持工作内存路径与 crate 内部分配路径（hash_password_into）输出逐字节一致：
/// 证明 `hash_password_into_with_memory` + 自持 blocks 是纯内存管理替换，零算法漂移。
#[test]
fn self_held_blocks_output_matches_crate_path() {
    let pwd = [0x01u8; 32];
    let salt = [0x02u8; 16];

    // 本 crate 的 derive（P2-56 整改后：自持 Zeroizing<Vec<Block>>）
    let via_self_held =
        derive(&pwd, &salt, None, None, 2, 256, 2, ARGON2_VERSION_13, TYPE_ARGON2ID)
            .expect("自持内存路径应成功");

    // crate 内部分配路径（原实现形态，P2-56 前后行为未变）
    let params = argon2::Params::new(256, 2, 2, Some(OUT_LEN)).expect("参数合法");
    let ctx = argon2::Argon2::new(argon2::Algorithm::Argon2id, argon2::Version::V0x13, params);
    let mut via_crate = [0u8; OUT_LEN];
    ctx.hash_password_into(&pwd, &salt, &mut via_crate).expect("crate 路径应成功");

    assert_eq!(via_self_held, via_crate, "自持内存路径与 crate 路径输出必须逐字节一致");
}

/// `Zeroizing<Vec<Block>>` 的清零链路（`Vec<Block>: Zeroize` → `Block::zeroize` →
/// `u64` 数组归零）真实生效：模拟派生后状态（全 0xAA 填充）并显式触发与 Drop 相同的
/// `zeroize()`，全部 1024 字节必须归零——这是自持内存析构清零的机制级证据。
#[test]
fn zeroizing_vec_block_zeroizes_all_bytes() {
    let mut blocks: Vec<Block> = vec![Block::default(); 8];
    // 模拟派生后的非零中间态（逐块全量填 0xAA）
    for b in blocks.iter_mut() {
        b.as_mut().fill(0xAA);
    }

    let guard = zeroize::Zeroizing::new(blocks);
    // 与 Drop 相同的清零路径（Zeroizing 的 Drop 即调用 inner.zeroize()），显式触发以便断言
    let mut guard = guard;
    guard.zeroize();

    for (i, b) in guard.iter().enumerate() {
        assert!(
            b.as_ref().iter().all(|&w| w == 0),
            "第 {i} 块存在非零残留：m_cost 工作内存清零链路失效"
        );
    }
}

/// 分配失败优雅语义：超大 block_count 的 `try_reserve_exact` 失败必须返回 None
/// 而非 panic/abort（对齐原 `Blocks::new → Error::OutOfMemory` 契约；abort 在
/// App 进程内比现状更坏，AC 明令禁止）。
#[test]
fn oversized_allocation_fails_gracefully() {
    // m_cost = 2^31 KiB ≈ 2 TiB：任何合理测试环境都无法满足，
    // 且远超 KdbxKdfParameterCodec 的 4 GiB 上界——分配必然失败。
    let huge_memory_kib: u32 = 1 << 31;
    let got = derive(
        &[0x01u8; 32],
        &[0x02u8; 16],
        None,
        None,
        2,
        huge_memory_kib,
        2,
        ARGON2_VERSION_13,
        TYPE_ARGON2ID,
    );
    assert!(got.is_none(), "超大 m_cost 分配失败必须优雅返回 None（不得 panic/abort）");
}

/// ISSUE-P2-57（审计 RUST-02）：`derive_into` 把结果直接写入调用方受管缓冲，
/// 与门面 `derive`（返回普通栈副本，仅测试用）**逐字节一致**——证明消除栈副本
/// 是纯内存管理替换，零算法漂移。
#[test]
fn derive_into_matches_wrapper_byte_for_byte() {
    let pwd = [0x01u8; 32];
    let salt = [0x02u8; 16];
    let secret = [0x03u8; 16];
    let ad = [0x04u8; 12];

    let via_wrapper = derive(
        &pwd,
        &salt,
        Some(&secret),
        Some(&ad),
        2,
        256,
        2,
        ARGON2_VERSION_13,
        TYPE_ARGON2ID,
    )
    .expect("门面路径应成功");

    let mut buf = zeroize::Zeroizing::new([0u8; OUT_LEN]);
    crate::derive_into(
        &pwd,
        &salt,
        Some(&secret),
        Some(&ad),
        2,
        256,
        2,
        ARGON2_VERSION_13,
        TYPE_ARGON2ID,
        &mut buf,
    )
    .expect("受管缓冲路径应成功");

    assert_eq!(
        &buf[..],
        &via_wrapper[..],
        "derive_into 与 derive 输出必须逐字节一致"
    );
}
