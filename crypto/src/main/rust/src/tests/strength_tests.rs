use super::*;

fn score(pw: &str) -> i32 {
    estimate(pw.as_bytes()).score
}

// ================= 分档已知答案 =================

#[test]
fn known_weak_passwords_score_zero() {
    for pw in ["password", "123456", "qwerty", "111111", "abc123", "qwertyuiop"] {
        assert_eq!(score(pw), 0, "[{pw}] 应属最低档");
    }
}

#[test]
fn strong_passwords_score_max() {
    // 20 位全类别随机串（无任何模式）
    assert_eq!(score("tR7#kL9@mQ2!xZ4&vB6*"), SCORE_MAX);
    assert_eq!(score("Xk92!mQp#7Lz@4Rt&8Wn"), SCORE_MAX);
}

#[test]
fn empty_password_is_lowest() {
    let e = estimate(b"");
    assert_eq!(e.score, SCORE_MIN);
    assert_eq!(e.guesses_log10_x100, 0);
    assert_ne!(e.flags & FLAG_TOO_SHORT, 0);
}

// ================= 单调性 =================

/// 同一前缀族下加长不降档（防止「越长越弱」的反常）。
#[test]
fn longer_is_not_worse_within_family() {
    let mut prev = -1;
    for pw in ["aB3$", "aB3$kL", "aB3$kLmQ", "aB3$kLmQ7#", "aB3$kLmQ7#xZ"] {
        let s = score(pw);
        assert!(s >= prev, "[{pw}] 分档 {s} 低于更短的前缀 {prev}");
        prev = s;
    }
}

/// 类别从单一扩到多类不降档。
#[test]
fn multi_class_is_not_worse_than_single_class() {
    assert!(score("mypassword") <= score("MyPassword"));
    assert!(score("MyPassword") <= score("MyPassword7"));
    assert!(score("MyPassword7") <= score("MyPassword7#"));
}

// ================= 各模式正例 / 反例 =================

#[test]
fn repeated_run_flag() {
    assert_ne!(estimate(b"aaaaBBBBcccc").flags & FLAG_REPEATED_RUN, 0);
    // 反例：无三连重复
    assert_eq!(estimate(b"aBcDeFgHiJ").flags & FLAG_REPEATED_RUN, 0);
}

#[test]
fn sequence_flag() {
    assert_ne!(estimate(b"xyz987wvu").flags & FLAG_SEQUENCE, 0);
    assert_ne!(estimate(b"abcdEFGH").flags & FLAG_SEQUENCE, 0);
    // 反例：跨类别或跨度非 1
    assert_eq!(estimate(b"aC3fH9kM").flags & FLAG_SEQUENCE, 0);
}

#[test]
fn keyboard_walk_flag() {
    assert_ne!(estimate(b"qwertyui").flags & FLAG_KEYBOARD_WALK, 0);
    assert_ne!(estimate(b"asdfghjk").flags & FLAG_KEYBOARD_WALK, 0);
    // 反例
    assert_eq!(estimate(b"qazplmok").flags & FLAG_KEYBOARD_WALK, 0);
}

#[test]
fn date_like_flag() {
    assert_ne!(estimate(b"20260101").flags & FLAG_DATE_LIKE, 0);
    assert_ne!(estimate(b"1999").flags & FLAG_DATE_LIKE, 0);
    assert_ne!(estimate(b"user1990name").flags & FLAG_DATE_LIKE, 0);
    // 反例：非年份四位数字
    assert_eq!(estimate(b"user1234name").flags & FLAG_DATE_LIKE, 0);
}

#[test]
fn single_class_and_low_unique_flags() {
    assert_ne!(estimate(b"abcdefghijkl").flags & FLAG_SINGLE_CHAR_CLASS, 0);
    assert_eq!(estimate(b"abcDEF123!@#").flags & FLAG_SINGLE_CHAR_CLASS, 0);
    assert_ne!(estimate(b"aaaaaaaaaa").flags & FLAG_LOW_UNIQUE_RATIO, 0);
    assert_eq!(estimate(b"aBcDeFgHiJkL").flags & FLAG_LOW_UNIQUE_RATIO, 0);
}

#[test]
fn periodic_repeat_flag_and_score() {
    let e = estimate(b"abcabcabc");
    assert_ne!(e.flags & FLAG_PERIODIC_REPEAT, 0);
    assert!(e.score <= 1, "周期重复串不应被评为强口令（实际 {}）", e.score);
    assert_ne!(estimate(b"xyxyxyxy").flags & FLAG_PERIODIC_REPEAT, 0);
    // 反例：非周期
    assert_eq!(estimate(b"abcdefghi").flags & FLAG_PERIODIC_REPEAT, 0);
}

#[test]
fn common_password_flags_exact_and_stem() {
    assert_ne!(estimate(b"password").flags & FLAG_COMMON_PASSWORD, 0);
    assert_ne!(estimate(b"PASSWORD").flags & FLAG_COMMON_PASSWORD, 0);
    // 近似：剥离尾部数字/符号后命中
    assert_ne!(estimate(b"password1").flags & FLAG_COMMON_PASSWORD, 0);
    assert_ne!(estimate(b"qwerty!").flags & FLAG_COMMON_PASSWORD, 0);
    // 反例
    assert_eq!(estimate(b"tR7#kL9@mQ2!xZ4&vB6*").flags & FLAG_COMMON_PASSWORD, 0);
}

#[test]
fn too_short_flag() {
    assert_ne!(estimate(b"aB3$").flags & FLAG_TOO_SHORT, 0);
    assert_eq!(estimate(b"aB3$kLmQ").flags & FLAG_TOO_SHORT, 0);
}

/// 长度分档上限（策略项）：短口令即便字符集很杂也不得评到高档。
#[test]
fn short_passwords_are_capped_by_policy() {
    // 6 位四类：纯熵算约 10^11.9，若无上限会误判为 4
    assert!(score("aB3$kL") <= 2, "6 位口令不应超过 2 档");
    assert!(score("Xk9!mQ") <= 2, "6 位口令不应超过 2 档");
    // 10 位四类：受「12 位才允许最高档」约束
    assert!(score("aB3$kLmQ7#") <= 3, "10 位口令不应达到 4 档");
    // 12 位四类：可到最高档
    assert_eq!(score("aB3$kLmQ7#xZ"), SCORE_MAX);
}

/// 键盘行走**不得跨排**：上一排末位与下一排首位在展平索引上相邻，但视觉/物理上不相邻。
#[test]
fn keyboard_walk_does_not_bridge_rows() {
    // `0`（数字行末）与 `q`（字母首行首）展平索引相邻，但跨排
    assert_eq!(estimate(b"0qPz7w").flags & FLAG_KEYBOARD_WALK, 0);
    // 反例对照：真同排行走必须命中
    assert_ne!(estimate(b"qwerty").flags & FLAG_KEYBOARD_WALK, 0);
}

/// 顺序段**不得跨字符类别**：`C`(大写第 2 位) 与 `3`(数字第 3 位) 索引差为 1 但无顺序关系。
#[test]
fn sequence_does_not_bridge_char_classes() {
    assert_eq!(estimate(b"wC3gJ8mR").flags & FLAG_SEQUENCE, 0);
    // 反例对照：同类别递增必须命中
    assert_ne!(estimate(b"wxyz").flags & FLAG_SEQUENCE, 0);
    assert_ne!(estimate(b"7890").flags & FLAG_SEQUENCE, 0);
}

// ================= 健壮性 =================

#[test]
fn never_panics_on_arbitrary_bytes() {
    // 非法 UTF-8、超长串、控制字符、非 ASCII
    let _ = estimate(&[0xFF, 0xFE, 0x00, 0x80]);
    let _ = estimate(&vec![b'a'; 10_000]);
    let _ = estimate("口令密码测试".as_bytes());
    let _ = estimate("🔐🔐🔐🔐🔐🔐".as_bytes());
    let _ = estimate(&[0u8; 1]);
}

#[test]
fn deterministic() {
    for pw in ["password", "tR7#kL9@mQ2!xZ4&vB6*", "abcabcabc"] {
        assert_eq!(estimate(pw.as_bytes()), estimate(pw.as_bytes()));
    }
}

#[test]
fn flags_are_within_defined_bitmask() {
    const ALL: i32 = FLAG_COMMON_PASSWORD
        | FLAG_TOO_SHORT
        | FLAG_REPEATED_RUN
        | FLAG_SEQUENCE
        | FLAG_KEYBOARD_WALK
        | FLAG_DATE_LIKE
        | FLAG_SINGLE_CHAR_CLASS
        | FLAG_LOW_UNIQUE_RATIO
        | FLAG_PERIODIC_REPEAT;
    for pw in [
        "", "password", "123456", "qwertyuiop", "abcdefgh", "20260101", "abcabcabc", "aaaa",
    ] {
        let f = estimate(pw.as_bytes()).flags;
        assert_eq!(f & !ALL, 0, "[{pw}] 出现未定义标志位");
    }
}
