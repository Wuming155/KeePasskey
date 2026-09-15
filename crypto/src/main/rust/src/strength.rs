//! 口令强度评估原生内核（ISSUE-P3-36，纯函数，无 JNI/Android 依赖）。
//!
//! **范围声明（务必如实理解，勿当作 zxcvbn）**：本模块是**自研的轻量强度模型**，
//! 由「字符集熵基线 + 模式惩罚」两部分构成：
//! 1. 基线：`len × log2(字符集规模)`（NIST SP 800-63B 附录 A 的经典估算方式）；
//! 2. 惩罚：完全/近似命中常见口令表、同字符重复段、单调顺序段、键盘相邻行走、
//!    日期/年份形状、周期重复块、字符集单一、字符唯一率过低。
//!
//! **不是** zxcvbn 移植：不引入 `zxcvbn` crate 及其数十万条频率语料——那会与
//! `Cargo.toml` 既定的「最小化依赖面（供应链收敛）」纪律与 `deny.toml` 的审计面直接冲突。
//! 凡对外描述本能力，一律称「模式惩罚型强度评估」，**禁止**称「zxcvbn」。
//!
//! 为何放在原生侧（本条目选择 Rust 的**首要理由**）：
//! - **秘密治理**：口令以 UTF-8 字节进入，全程只在 [`Zeroizing`] 缓冲中驻留，
//!   不在 JVM 侧物化为不可擦除的 `String`；`chars` 缓冲同样经 `Zeroizing` 归零；
//! - 全库审计时对每条口令做一遍模式扫描属纯 CPU 工作。
//!   吞吐**不是**本条目宣称的收益点。
//!
//! 跨语言契约：本模块的 [`FLAG_*`] 位值必须与 Kotlin 侧
//! `com.keepasskey.crypto.strength.PasswordStrengthFlags` **逐位一致**，
//! 且 JNI 层返回 `IntArray[score, guessesLog10X100, flags]` 的**定长 3 元布局**。
//!
//! **热路径预算（ISSUE-P2-58，审计 RUST-03）**：全库审计会对**每条口令**调用本模块，
//! 恶意库可用超长字符串放大耗时。故：
//! - [`estimate`] 只对前 [`MAX_ANALYZED_CHARS`] 个字符做模式识别与熵基线（超额部分线性惩罚）；
//! - 三条原平方级路径均已线性化：[`longest_keyboard_walk`]（单趟）、[`unique_char_count`]
//!   （ASCII 位图）、[`minimal_period`]（KMP 前缀函数）；
//! - 结果：单条口令的耗时上界为 `O(MAX_ANALYZED_CHARS)` + 周期检测 `O(真实长度)`，
//!   与恶意输入的规模**脱钩**（周期检测为线性且常数极小，故不受截断影响，跑全量字符）。

use zeroize::Zeroizing;

/// 最低分档（极弱）。
pub const SCORE_MIN: i32 = 0;
/// 最高分档（强）。
pub const SCORE_MAX: i32 = 4;

/// 完全或近似命中常见口令表。
pub const FLAG_COMMON_PASSWORD: i32 = 1;
/// 长度不足（< [`MIN_RECOMMENDED_LEN`]）。
pub const FLAG_TOO_SHORT: i32 = 1 << 1;
/// 存在同字符重复段（长度 ≥ 3）。
pub const FLAG_REPEATED_RUN: i32 = 1 << 2;
/// 存在单调顺序段（如 `abc` / `321`，长度 ≥ 3）。
pub const FLAG_SEQUENCE: i32 = 1 << 3;
/// 存在键盘相邻行走段（如 `qwert` / `asdf`，长度 ≥ 4）。
pub const FLAG_KEYBOARD_WALK: i32 = 1 << 4;
/// 呈日期/年份形状。
pub const FLAG_DATE_LIKE: i32 = 1 << 5;
/// 仅含单一字符类别（全小写 / 全数字 …）。
pub const FLAG_SINGLE_CHAR_CLASS: i32 = 1 << 6;
/// 字符唯一率过低（< 50%）。
pub const FLAG_LOW_UNIQUE_RATIO: i32 = 1 << 7;
/// 整串是短周期块的高次重复（如 `abcabcabc` / `xyxyxy`）。
pub const FLAG_PERIODIC_REPEAT: i32 = 1 << 8;

/// 建议的最短长度（对齐 NIST SP 800-63B 下限与既有 `HealthCheckEngine` 的 `< 8` 判据）。
pub const MIN_RECOMMENDED_LEN: usize = 8;

/// 「足够长」阈值：达到该长度才允许评到最高档（见 [`length_cap`]）。
pub const LONG_ENOUGH_LEN: usize = 12;

/// 同字符重复段的判定阈值。
const REPEAT_RUN_MIN: usize = 3;
/// 单调顺序段的判定阈值。
const SEQUENCE_RUN_MIN: usize = 3;
/// 键盘行走段的判定阈值。
const KEYBOARD_WALK_MIN: usize = 4;
/// 周期重复块的最小重复次数。
const PERIODIC_MIN_REPEATS: usize = 3;
/// 唯一率惩罚阈值。
const LOW_UNIQUE_RATIO: f64 = 0.5;

/// **热路径分析长度上限**（ISSUE-P2-58，审计 RUST-03）。
///
/// 为何需要：`MAX_TEXT_CHARS`（XML 节点封顶）对本模块的**热路径不构成约束**——
/// `HealthCheckEngine` 会对**全库每条口令**循环评估，恶意库可塞入超长口令字符串把
/// 平方级路径（周期检测 / 键盘行走 / 唯一字符统计）乘性放大成 CPU DoS。
///
/// 语义：超过该长度的输入**只分析前 N 个字符**，未分析尾部
/// ① **不获得任何熵信用**（熵基线按已分析长度计）、② 另按 [`EXCESS_PENALTY_PER_CHAR`]
/// **线性扣减**。两项合起来确保超长重复串（如 `'1234' × N`）**不会**因"超出只按长度评分"
/// 被误判为极强（陷阱 #7）。
pub const MAX_ANALYZED_CHARS: usize = 256;

/// 超出 [`MAX_ANALYZED_CHARS`] 的每个字符的**线性惩罚**（`log10(猜测次数)` 维度）。
///
/// 取 `0.05`：约每 60 个超额字符扣掉一个分档（分档间距约 `3`），
/// 既不让超长输入被轻视（真随机长口令仍会落在最高档），也不让其被高估。
const EXCESS_PENALTY_PER_CHAR: f64 = 0.05;

/// `log2(10)`——用于把「比特」换算为「log10(猜测次数)」。
const LOG2_10: f64 = 3.321_928_092_440_296;

/// 常见口令表（全小写；对齐 `HealthCheckEngine.COMMON_WEAK_PASSWORDS` 并补充键盘行走与
/// 低基数重复型；**不含任何真实用户凭据**）。
const COMMON_PASSWORDS: &[&str] = &[
    // —— 与 HealthCheckEngine 既有 15 条保持一致（不减少既有判定能力）——
    "123456",
    "password",
    "12345678",
    "qwerty",
    "123456789",
    "12345",
    "1234",
    "111111",
    "1234567",
    "dragon",
    "welcome",
    "admin",
    "admin123",
    "root",
    "pass123",
    // —— 键盘行走 / 低基数重复 / 经典弱口令补充 ——
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm",
    "1qaz2wsx",
    "qazwsx",
    "qwerty123",
    "abc123",
    "a123456",
    "letmein",
    "iloveyou",
    "monkey",
    "000000",
    "666666",
    "888888",
    "123123",
    "112233",
    "121212",
    "111111111",
];

/// 评估结果。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Estimate {
    /// 分档 `0..=4`（越大越强）。
    pub score: i32,
    /// `log10(猜测次数) × 100` 的定点整数（避免跨 FFI 传递浮点）。
    pub guesses_log10_x100: i32,
    /// [`FLAG_*`] 位或。
    pub flags: i32,
}

/// 键盘三行 + 数字行（用于相邻行走识别；仅横向相邻，不含对角线）。
const KEYBOARD_ROWS: [&str; 4] = [
    "`1234567890-=",
    "qwertyuiop[]\\",
    "asdfghjkl;'",
    "zxcvbnm,./",
];

/// 口令强度评估（纯函数）。
///
/// `password` 为 UTF-8 字节（调用方保证有效；非法字节按替换字符容错处理，绝不 panic）。
/// 空口令返回 `score = 0` 且置 [`FLAG_TOO_SHORT`]。
pub fn estimate(password: &[u8]) -> Estimate {
    // 解码为字符序列并立即置于受管缓冲（函数返回即归零）
    let chars: Zeroizing<Vec<char>> = Zeroizing::new(
        std::str::from_utf8(password)
            .map(|s| s.chars().collect())
            .unwrap_or_else(|_| String::from_utf8_lossy(password).chars().collect()),
    );

    let len = chars.len();
    if len == 0 {
        return Estimate {
            score: SCORE_MIN,
            guesses_log10_x100: 0,
            flags: FLAG_TOO_SHORT,
        };
    }

    // ISSUE-P2-58（审计 RUST-03）：热路径长度上限 + 线性惩罚。
    // - `len`（真实长度）仍用于长度分档上限与 `FLAG_TOO_SHORT`（口径不变）；
    // - `analyzed`（≤ MAX_ANALYZED_CHARS 的前缀）承担**全部模式识别与熵基线**，
    //   使本函数对任意输入长度的最坏耗时被封顶（恶意库的 CPU DoS 面收敛）；
    // - `excess` 尾部既不计熵信用、又按线性惩罚扣减（见估算末尾）。
    let analyzed: &[char] = &chars[..len.min(MAX_ANALYZED_CHARS)];
    let excess = len.saturating_sub(MAX_ANALYZED_CHARS);
    let analyzed_len = analyzed.len();

    let mut flags: i32 = 0;
    if len < MIN_RECOMMENDED_LEN {
        flags |= FLAG_TOO_SHORT;
    }

    // —— 字符集规模与类别计数 ——
    let (charset_size, class_count) = charset_profile(analyzed);
    if class_count == 1 {
        flags |= FLAG_SINGLE_CHAR_CLASS;
    }

    // —— 基线：已分析长度 × log2(charset) 比特 → log10(猜测次数) ——
    // 未分析尾部不参与基线（不给"未经审视的长度"任何熵信用）
    let mut log10 = (analyzed_len as f64) * (charset_size as f64).log2() / LOG2_10;

    // —— 完全/近似命中常见口令表 ——
    let lowered: Zeroizing<String> = Zeroizing::new(analyzed.iter().flat_map(|c| c.to_lowercase()).collect());
    if COMMON_PASSWORDS.contains(&lowered.as_str()) {
        flags |= FLAG_COMMON_PASSWORD;
        log10 = 1.0_f64.min(log10);
    } else {
        // 近似：剥除尾部数字与符号后命中（`password1`、`qwerty!` 一族）
        let stem_len = lowered
            .trim_end_matches(|c: char| c.is_ascii_digit() || c.is_ascii_punctuation())
            .len();
        if stem_len > 0 && stem_len < lowered.len() {
            let stem = &lowered[..stem_len];
            if COMMON_PASSWORDS.contains(&stem) {
                flags |= FLAG_COMMON_PASSWORD;
                log10 = 4.0_f64.min(log10);
            }
        }
    }

    // —— 整串周期性（须先于单段惩罚，因为它把整串按「一个单元」重估）——
    // 注意：本判据跑**全量字符**（KMP 版本为 O(n)，见 `minimal_period` KDoc）——
    // 截断会让 `abc` × 100 一类超长重复块因不整除而漏判，进而被熵基线抬到高档。
    if let Some((unit_len, repeats)) = minimal_period(&chars) {
        if repeats >= PERIODIC_MIN_REPEATS {
            flags |= FLAG_PERIODIC_REPEAT;
            let unit_charset = charset_profile(&chars[..unit_len]).0;
            let unit_log10 = (unit_len as f64) * (unit_charset as f64).log2() / LOG2_10;
            log10 = (unit_log10 + (repeats as f64).log10()).min(log10);
        }
    }

    // —— 同字符重复段 ——
    let repeat_run = longest_repeat_run(analyzed);
    if repeat_run >= REPEAT_RUN_MIN {
        flags |= FLAG_REPEATED_RUN;
        log10 -= 0.8 * (repeat_run as f64);
    }

    // —— 单调顺序段 ——
    let seq_run = longest_sequence_run(analyzed);
    if seq_run >= SEQUENCE_RUN_MIN {
        flags |= FLAG_SEQUENCE;
        log10 -= 0.7 * (seq_run as f64);
    }

    // —— 键盘相邻行走 ——
    let walk = longest_keyboard_walk(analyzed);
    if walk >= KEYBOARD_WALK_MIN {
        flags |= FLAG_KEYBOARD_WALK;
        log10 -= 0.9 * (walk as f64);
    }

    // —— 日期/年份形状 ——
    if let Some(weight) = date_like_weight(analyzed) {
        flags |= FLAG_DATE_LIKE;
        log10 -= weight;
    }

    // —— 字符唯一率过低 ——
    let unique = unique_char_count(analyzed);
    let unique_ratio = unique as f64 / analyzed_len as f64;
    if unique_ratio < LOW_UNIQUE_RATIO {
        flags |= FLAG_LOW_UNIQUE_RATIO;
        log10 -= (LOW_UNIQUE_RATIO - unique_ratio) * 8.0;
    }

    // —— 超长输入的线性惩罚（ISSUE-P2-58 陷阱 #7 防线）——
    // 未分析尾部已不获熵信用（基线按 analyzed_len 计），此处再对其线性扣减：
    // 既不为"未经审视的长度"付溢价，也让超长重复串无法靠长度冲分。
    if excess > 0 {
        log10 -= EXCESS_PENALTY_PER_CHAR * excess as f64;
    }

    // 分档上限与 `FLAG_TOO_SHORT` 仍按**真实长度**裁决（口径不变）
    let log10 = log10.max(0.0);
    Estimate {
        score: score_of(log10).min(length_cap(len)),
        guesses_log10_x100: clamp_x100(log10),
        flags,
    }
}

/// 分档映射（阈值按 `log10(猜测次数)`）：`<3 → 0`、`<6 → 1`、`<8 → 2`、`<11 → 3`、其余 `4`。
fn score_of(log10: f64) -> i32 {
    if log10 < 3.0 {
        0
    } else if log10 < 6.0 {
        1
    } else if log10 < 8.0 {
        2
    } else if log10 < 11.0 {
        3
    } else {
        SCORE_MAX
    }
}

/// **长度分档上限（策略项，非熵推导）**：不足 [`MIN_RECOMMENDED_LEN`] 位不得高于 `2`；
/// 不足 `LONG_ENOUGH_LEN` 位不得高于 `3`。
///
/// 立据：纯字符集熵对短口令系统性高估——`aB3$kL`（6 位四类）按熵算约 `10^11.9` 次猜测，
/// 会落到最高档，但现实中 6 位口令的搜索空间对离线爆破毫无门槛。该上限是本模型**有意引入的
/// 策略约束**（与 `FLAG_TOO_SHORT` 标志互补：标志供 UI 提示，上限约束分档上限），
/// 已在文档中如实声明，**不是**从熵推导出的结论。
fn length_cap(len: usize) -> i32 {
    if len < MIN_RECOMMENDED_LEN {
        2
    } else if len < LONG_ENOUGH_LEN {
        3
    } else {
        SCORE_MAX
    }
}

/// `log10 × 100` 定点化，饱和到 `i32` 安全区（理论极大值远低于该界，防溢出而非语义裁剪）。
fn clamp_x100(log10: f64) -> i32 {
    let scaled = (log10 * 100.0).round();
    if scaled <= 0.0 {
        0
    } else if scaled >= 1_000_000.0 {
        1_000_000
    } else {
        scaled as i32
    }
}

/// 返回 (字符集规模, 出现的字符类别数)。
fn charset_profile(chars: &[char]) -> (usize, usize) {
    let mut has_lower = false;
    let mut has_upper = false;
    let mut has_digit = false;
    let mut has_symbol = false;
    let mut has_space = false;
    let mut has_other = false;
    for &c in chars {
        if c.is_ascii_lowercase() {
            has_lower = true;
        } else if c.is_ascii_uppercase() {
            has_upper = true;
        } else if c.is_ascii_digit() {
            has_digit = true;
        } else if c.is_whitespace() {
            has_space = true;
        } else if c.is_ascii() {
            has_symbol = true;
        } else {
            has_other = true;
        }
    }
    let mut size = 0usize;
    let mut classes = 0usize;
    for (present, width) in [
        (has_lower, 26usize),
        (has_upper, 26),
        (has_digit, 10),
        (has_symbol, 33),
        (has_space, 1),
        // 非 ASCII 保守取 100 个候选（不放大非拉丁语系的强度）
        (has_other, 100),
    ] {
        if present {
            size += width;
            classes += 1;
        }
    }
    (size, classes)
}

/// 最长同字符重复段长度（`aaaa` → 4）。
fn longest_repeat_run(chars: &[char]) -> usize {
    let mut best = 1usize;
    let mut cur = 1usize;
    for i in 1..chars.len() {
        if chars[i] == chars[i - 1] {
            cur += 1;
            best = best.max(cur);
        } else {
            cur = 1;
        }
    }
    if chars.is_empty() {
        0
    } else {
        best
    }
}

/// 最长单调顺序段长度（步长 ±1；同类别内比较，`abc`/`cba`/`456`/`654` 均命中）。
fn longest_sequence_run(chars: &[char]) -> usize {
    let mut best = 1usize;
    let mut cur = 1usize;
    for i in 1..chars.len() {
        let step = step_of(chars[i - 1], chars[i]);
        let prev_step = if i >= 2 { step_of(chars[i - 2], chars[i - 1]) } else { None };
        let continued = step.is_some() && (cur == 1 || step == prev_step);
        if continued {
            cur += 1;
            best = best.max(cur);
        } else {
            cur = 1;
        }
    }
    if chars.is_empty() {
        0
    } else {
        best
    }
}

/// 相邻字符在「同类别内」的索引差（±1 视为顺序步长）。
///
/// **必须同类别**：`C`（大写字母表第 2 位）与 `3`（数字表第 3 位）索引差恰为 1，
/// 但它们毫无顺序关系；若不做类别判定会把 `aC3f` 误判为顺序串（本模块自测已锁定该负例）。
fn step_of(a: char, b: char) -> Option<i32> {
    let (class_a, idx_a) = class_index(a)?;
    let (class_b, idx_b) = class_index(b)?;
    if class_a != class_b {
        return None;
    }
    let d = idx_b - idx_a;
    if d == 1 || d == -1 {
        Some(d)
    } else {
        None
    }
}

/// 字符类别标签（用于判定「顺序段必须同类别」）。
type CharClass = u8;
/// 小写字母类别。
const CLASS_LOWER: CharClass = 0;
/// 大写字母类别。
const CLASS_UPPER: CharClass = 1;
/// 数字类别。
const CLASS_DIGIT: CharClass = 2;

/// 字符的（类别, 该类别字母表内序号）：小写 `a..z`、大写 `A..Z`、数字 `0..9`；其余返回 `None`。
fn class_index(c: char) -> Option<(CharClass, i32)> {
    if c.is_ascii_lowercase() {
        Some((CLASS_LOWER, c as i32 - 'a' as i32))
    } else if c.is_ascii_uppercase() {
        Some((CLASS_UPPER, c as i32 - 'A' as i32))
    } else if c.is_ascii_digit() {
        Some((CLASS_DIGIT, c as i32 - '0' as i32))
    } else {
        None
    }
}

/// 最长键盘相邻行走段长度（**同排内**横向相邻，如 `qwert`；大小写不敏感）。
///
/// **单趟 O(n)**（ISSUE-P2-58 AC②：原实现逐起点内扫，最坏 O(n²)）：顺序扫描时只需与
/// **前一个**字符比较即可递推当前段长——「同排且列号相邻」是**相邻字符间**的关系，
/// 故单趟扫描与逐起点扫描在语义上完全等价（既有负例 `keyboard_walk_does_not_bridge_rows`
/// 与 `-=` + `q` 跨排组合的判定保持不变）。
///
/// **必须判排**：展平拼接后上一排末位与下一排首位索引也相邻，若不判排会把跨排组合误判为行走。
fn longest_keyboard_walk(chars: &[char]) -> usize {
    let mut best = 0usize;
    let mut cur = 0usize;
    let mut prev: Option<(usize, usize)> = None;
    for &c in chars {
        match keyboard_index(c) {
            Some((row, col)) => {
                cur = match prev {
                    Some((prev_row, prev_col)) if prev_row == row && prev_col.abs_diff(col) == 1 => {
                        cur + 1
                    }
                    _ => 1,
                };
                best = best.max(cur);
                prev = Some((row, col));
            }
            None => {
                // 非键盘字符打断当前行走段（与逐起点扫描的 `break` 语义一致）
                cur = 0;
                prev = None;
            }
        }
    }
    best
}

/// 字符在键盘中的（排号, 列号）；不在任何排则 `None`。
fn keyboard_index(c: char) -> Option<(usize, usize)> {
    let lower = c.to_ascii_lowercase();
    for (row_no, row) in KEYBOARD_ROWS.iter().enumerate() {
        if let Some(col) = row.chars().position(|k| k == lower) {
            return Some((row_no, col));
        }
    }
    None
}

/// 日期/年份形状的惩罚权重；不呈日期形状返回 `None`。
///
/// - 整串为 4/6/8 位数字且可解释为年份或 `YYYYMMDD` / `YYMMDD` / `MMDDYYYY` → 权重 `4.0`；
/// - 仅**内含**一个 `1900..=2099` 的四位年份 → 权重 `2.0`。
fn date_like_weight(chars: &[char]) -> Option<f64> {
    if chars.iter().all(|c| c.is_ascii_digit()) {
        let s: Zeroizing<String> = Zeroizing::new(chars.iter().collect());
        let ok = match s.len() {
            4 => is_year(&s),
            6 => {
                let mm: u32 = s[0..2].parse().unwrap_or(0);
                let dd: u32 = s[2..4].parse().unwrap_or(0);
                (1..=12).contains(&mm) && (1..=31).contains(&dd)
            }
            8 => {
                let (y, m, d) = (&s[0..4], &s[4..6], &s[6..8]);
                is_year(y)
                    && (1..=12).contains(&m.parse().unwrap_or(0))
                    && (1..=31).contains(&d.parse().unwrap_or(0))
            }
            _ => false,
        };
        if ok {
            return Some(4.0);
        }
    }
    // 内含四位年份
    let s: Zeroizing<String> = Zeroizing::new(chars.iter().collect());
    let bytes = s.as_bytes();
    if bytes.len() >= 4 {
        for w in bytes.windows(4) {
            if w.iter().all(|b| b.is_ascii_digit()) && is_year(std::str::from_utf8(w).unwrap_or("")) {
                return Some(2.0);
            }
        }
    }
    None
}

/// 四位数字串是否为合理年份（`1900..=2099`）。
fn is_year(text: &str) -> bool {
    match text.parse::<u32>() {
        Ok(y) => (1900..=2099).contains(&y),
        Err(_) => false,
    }
}

/// 不同字符个数（**O(n)**，ISSUE-P2-58 AC③）。
///
/// 原实现用 `Vec::contains` 去重（最坏 O(n²)）；现改为「ASCII 位图 + 非 ASCII 小表」：
/// - ASCII（`U+0000..=U+007F`）用 `[bool; 128]` 位图，O(1) 判定；
/// - 非 ASCII 字符收集进 `Vec<char>`，逐个 `contains`——其长度受 [`MAX_ANALYZED_CHARS`]
///   封顶且远小于 ASCII 占比，故整体为 O(n)。
fn unique_char_count(chars: &[char]) -> usize {
    let mut ascii_seen = [false; 128];
    let mut others: Vec<char> = Vec::new();
    let mut count = 0usize;
    for &c in chars {
        let code = c as u32;
        if code < 128 {
            let slot = &mut ascii_seen[code as usize];
            if !*slot {
                *slot = true;
                count += 1;
            }
        } else if !others.contains(&c) {
            others.push(c);
            count += 1;
        }
    }
    count
}

/// 最小周期 `p` 与重复次数 `len / p`；非周期串返回 `None`。
///
/// **O(n)**（ISSUE-P2-58：原实现逐周期长度试探为最坏 O(n²)，是全模块最后一条平方级路径）：
/// 以 KMP 前缀函数求最小整周期——`p = len - pi[len-1]`，且仅当 `len % p == 0` 时成立。
/// 语义与原实现一致：只接受**恰好整周期**（`len % p == 0` 且逐字符相等），
/// 不把「偶然重复前缀」误判为周期串；`repeats < 2` 返回 `None`。
///
/// 因已线性化，本函数在 [`estimate`] 中对**全量字符**调用（不受
/// [`MAX_ANALYZED_CHARS`] 截断影响）——否则超长重复块（如 `abc` × 100）会因截断后
/// 不再整除而漏判周期，被熵基线抬到高档（正是陷阱 #7 的一种形态）。
fn minimal_period(chars: &[char]) -> Option<(usize, usize)> {
    let len = chars.len();
    if len < 2 {
        return None;
    }
    // KMP 前缀函数：pi[i] = chars[..=i] 的最长真前缀=真后缀长度
    let mut pi = vec![0usize; len];
    for i in 1..len {
        let mut k = pi[i - 1];
        while k > 0 && chars[i] != chars[k] {
            k = pi[k - 1];
        }
        if chars[i] == chars[k] {
            k += 1;
        }
        pi[i] = k;
    }
    let p = len - pi[len - 1];
    if p == 0 || len % p != 0 {
        return None;
    }
    let repeats = len / p;
    if repeats < 2 {
        return None;
    }
    Some((p, repeats))
}

#[cfg(test)]
#[path = "tests/strength_tests.rs"]
mod tests;
