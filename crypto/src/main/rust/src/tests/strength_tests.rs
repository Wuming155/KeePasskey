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

// ================= ISSUE-P2-58（审计 RUST-03）：长度上限 + 线性惩罚 + 平方级路径线性化 =====

/// 陷阱 #7 防线：**超长重复串不得被评为强口令**。
///
/// 若按「超出上限只按长度评分」实现（原审计点名的错误做法），`1234 × 100`（400 字符）
/// 会因 400 字符的长度而落在最高档；正确实现须让模式识别与线性惩罚共同压住它。
#[test]
fn oversized_repetitive_password_is_not_rated_strong() {
    let long_digits = "1234".repeat(100); // 400 字符
    let e = estimate(long_digits.as_bytes());
    assert_ne!(e.flags & FLAG_PERIODIC_REPEAT, 0, "超长重复串必须命中周期标志");
    assert!(e.score <= 1, "超长重复数字串不得被评为强口令（实际 {}）", e.score);

    // 同类：长重复字母块（截断会让 `abc` × 100 因不整除而漏判，须靠全长 O(n) 周期检测捕获）
    let long_alpha = "abc".repeat(100); // 300 字符
    let e2 = estimate(long_alpha.as_bytes());
    assert_ne!(e2.flags & FLAG_PERIODIC_REPEAT, 0, "超长重复字母块必须命中周期标志");
    assert!(e2.score <= 1, "超长重复字母块不得被评为强口令（实际 {}）", e2.score);
}

/// 白名单：真随机的长口令不得被长度上限**误伤**（线性惩罚不得把长随机串降档）。
#[test]
fn oversized_random_like_password_is_not_penalized() {
    // 300 字符、四类字符、无周期/无长重复段（构造为逐字符递增的混合族，避免意外命中模式）
    let mut pw = String::new();
    for i in 0..300u32 {
        let c = match i % 4 {
            0 => char::from(b'a' + (i % 26) as u8),
            1 => char::from(b'A' + ((i * 7) % 26) as u8),
            2 => char::from(b'0' + ((i * 3) % 10) as u8),
            _ => ['#', '$', '!', '%'][(i % 4) as usize],
        };
        pw.push(c);
    }
    assert_eq!(
        estimate(pw.as_bytes()).score,
        SCORE_MAX,
        "长且杂的随机串应保持最高档（长度上限不得误伤）"
    );
}

/// 超长输入（10 万字符）必须**迅速返回**且语义合理——原实现的三条平方级路径在此规模下
/// 是 `10^10` 量级操作（本用例会直接超时）；线性化后为毫秒级。
#[test]
fn very_long_input_is_handled_in_linear_time() {
    let e = estimate(&vec![b'a'; 100_000]);
    assert!(e.score <= 1, "10 万字符单字重复串不得被评为强口令（实际 {}）", e.score);
    assert_ne!(e.flags & FLAG_PERIODIC_REPEAT, 0);

    // 95 个可打印 ASCII 循环 1000 次（95000 字符）：全长 O(n) 周期检测须命中，
    // 且超长线性惩罚把分档压到最低——**不得**因长度冲分
    let printable: Vec<u8> = (0x20u8..0x7Fu8).collect();
    let long: Vec<u8> = printable.iter().copied().cycle().take(95_000).collect();
    let e2 = estimate(&long);
    assert_ne!(e2.flags & FLAG_PERIODIC_REPEAT, 0, "95 字符周期块须被全长周期检测捕获");
    assert!(e2.score <= 1, "超长周期串不得被评为强口令（实际 {}）", e2.score);
}

/// ASCII 位图计数路径（`unique_char_count` 的 128 位图分支）：95 个互异可打印字符全部计入。
/// 唯一率**恰为 0.5**（95/190）时不触发低唯一率（判据为严格小于），借此确认位图无漏计。
#[test]
fn ascii_bitmap_counts_all_distinct_printable_chars() {
    let printable: Vec<u8> = (0x20u8..0x7Fu8).collect();
    let two_rounds: Vec<u8> = printable.iter().copied().chain(printable.iter().copied()).collect();
    assert_eq!(two_rounds.len(), 190);
    let e = estimate(&two_rounds);
    assert_eq!(
        e.flags & FLAG_LOW_UNIQUE_RATIO,
        0,
        "唯一率恰为 0.5 时不得触发低唯一率（若位图漏计会误触发）"
    );
}

/// 非 ASCII 计数分支（`unique_char_count` 的 `others` 小表）语义不变：
/// 全部互异 → 唯一率 1.0（不触发低唯一率）；全同 → 唯一率 1/n（触发）。
#[test]
fn non_ascii_unique_count_semantics_preserved() {
    assert_eq!(estimate("αβγδεζηθ".as_bytes()).flags & FLAG_LOW_UNIQUE_RATIO, 0);
    assert_ne!(estimate("αααααααα".as_bytes()).flags & FLAG_LOW_UNIQUE_RATIO, 0);
    // 混合：ASCII 与非 ASCII 互异字符均计入
    assert_eq!(estimate("aαbβcγdδeε".as_bytes()).flags & FLAG_LOW_UNIQUE_RATIO, 0);
}

/// 键盘行走单趟化的语义等价性：长同排行走进阶仍被识别，跨排组合仍不命中。
#[test]
fn keyboard_walk_single_pass_equivalence() {
    // 数字行整排（同排列号相邻，长度 12）→ 命中
    assert_ne!(estimate(b"1234567890-=").flags & FLAG_KEYBOARD_WALK, 0);
    // 字母行 `asdfghjkl`（同排相邻 9 个）→ 命中
    assert_ne!(estimate(b"asdfghjkl").flags & FLAG_KEYBOARD_WALK, 0);
    // 非键盘字符打断行走段：`qwe` + 空格 + `rty` 应为 3（< KEYBOARD_WALK_MIN=4）→ 不命中
    assert_eq!(estimate(b"qwe rty").flags & FLAG_KEYBOARD_WALK, 0);
    // 跨排负例（既有用例的同型复核）：`p`（上排末）与 `a`（中排首）不同排
    assert_eq!(estimate(b"pa").flags & FLAG_KEYBOARD_WALK, 0);
}

/// 恰好等于 / 超过长度上限的边界语义：上限内不触发线性惩罚，超 1 字符即开始扣减。
#[test]
fn analyzed_length_boundary_semantics() {
    let at_cap: String = "aB3$kLmQ7#xZ".chars().cycle().take(MAX_ANALYZED_CHARS).collect();
    let over_cap: String = format!("{at_cap}Z");
    assert_eq!(at_cap.chars().count(), MAX_ANALYZED_CHARS);
    assert_eq!(over_cap.chars().count(), MAX_ANALYZED_CHARS + 1);

    let at = estimate(at_cap.as_bytes());
    let over = estimate(over_cap.as_bytes());
    // 超限 1 字符的惩罚为 0.05（log10），定点量级 < 10，且分档不因此上升
    assert!(over.score <= at.score, "超限输入不得比上限内同内容更强");
    assert!(
        over.guesses_log10_x100 <= at.guesses_log10_x100,
        "超限惩罚须单调扣减（{} vs {}）",
        over.guesses_log10_x100,
        at.guesses_log10_x100
    );
}
