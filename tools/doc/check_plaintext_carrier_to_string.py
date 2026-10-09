"""明文口令载体 `data class` 必须覆写 `toString()`（`ISSUE-P2-549` 的机检化）。

## 为何存在

`ISSUE-P2-68`（审计 M6）早已为同形态的 `AutofillPickerViewModel.Credentials` 覆写了
`toString()`——KDoc 明写理由：数据类默认实现会把明文口令与用户名**整份展开**，
「一次日志 / 异常插值即泄漏」。但同仓另一处同形态载体 `ExtractedSaveCredentials`
（自动填充保存链路，同样持有明文口令）**漏了这层覆写**：它当前无日志消费点，
所以没出事故；代价是**新增一行 `AppLog.d(TAG, "$extracted")` 就明文出桶，编译期零护栏**。

「无消费点 ⇒ 暂时安全」正是这类缺陷能长期潜伏的形态，故按**形态**钉死而非靠人工普查
（与 `ISSUE-P1-538`「裸 `CoroutineScope(`」同型：普查口径写死 ⇒ 形态性漏检）。

## 判据（唯一，命中即退出码 1）

**`*/src/main/**` 内的 `data class`，只要有字段名命中 `password`（忽略大小写）且类型为
`String` / `String?` 的构造参数，就必须在该类体内 `override fun toString(`。**

- 扫描面＝**五个生产模块**（`MODULES`）各自的 `src/main/**` 下的 `.kt` 文件
  （与 `check_raw_coroutine_scope.py` / `count_line_tiers.py` 同口径）。**不用通配**：
  本机存在未跟踪的只读参考树 `参考项目/`（2410 个 `.kt`），通配会让本机与 CI 读数不可复现。
- 注释行（`//` / `*` / `/*`）跳过：判据盯**声明**，不是文档里的提及。
- 字段名以 `Id` / `Key` / `Ref` / `Index`（复数同）结尾的**豁免**：那是**标识符 / 字典键**，
  不是明文本身（实扫反证见模块内 `EXEMPT_SUFFIX_RE` 注释）。豁免必须窄，否则真命中
  （`revealedPassword` 就是揭示出来的明文口令）会被噪声淹没。
- 类体范围＝从参数表右括号到**下一个行首 `}`**（Kotlin 顶层声明的收口列）；
  类名与允许清单按「相对仓库根的 posix 路径 + 类名」登记。
- 类型判定只看 `String` / `String?`——`CharArray` / `ByteArray` 的默认 `toString()` 在
  data class 里是**内容展开**（`contentToString()`），同属泄漏面，但本判据先钉
  `ISSUE-P2-549` AC③ 明写的 `String` 形态，其余形态留待普查后扩面（避免首版即误伤）。

## 口径声明（**不得**据其绿推定「全仓明文无泄漏」）

本机检只钉「**形态**（声明）+ **护栏存在**（覆写）」。它**看不见**：
① 覆写体内是否仍插值了口令内容（只看有没有覆写）；
② 非 `data class` 的普通类 / `value class`；
③ 字段名不叫 `password` 的敏感载体（如 `secret` / `token`）。

用法：`python tools/doc/check_plaintext_carrier_to_string.py [--root 目录] [--selftest]`；
命中即退出码 1（无命中退 0）；`--selftest` 用内嵌正 / 反样本反校判据本身。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# 允许「命中形态但不覆写」的类（相对仓库根 posix 路径 + 类名）。新成员入列须写明理由。
ALLOWED: tuple[str, ...] = ()

# 顶层 `data class Name(` 声明
DECL_RE = re.compile(r"\bdata\s+class\s+(\w+)\s*\(")

# 构造参数：`val name: Type` / `var name: Type = ...`（只取主构造参数表里的显式声明）
PARAM_RE = re.compile(r"^\s*(?:val|var)\s+(\w+)\s*:\s*([A-Za-z_][\w.?<>,\s]*?)\s*(?:=[^,]+)?$")

# 敏感字段名：命中即视为明文口令载体（忽略大小写）
SENSITIVE_NAME_RE = re.compile(r"password", re.IGNORECASE)

# 豁免后缀：**标识符 / 键名**类字段不是明文本身（忽略大小写）。
# 实扫反证：① `AutofillFieldScanner.ScanResult.passwordId` 是自动填充视图 id；
# ② `RememberedLoginFields.passwordKey` 是 `AutofillId.toString()` 的字典键（KDoc 明示非敏感）。
# 二者若计入 ⇒ 真命中（`DetailCore.revealedPassword`，揭示出来的**明文口令**）会被淹没在噪声里。
EXEMPT_SUFFIX_RE = re.compile(r"(id|ids|key|keys|ref|refs|index)$", re.IGNORECASE)

# 判据覆盖的承载类型（首版只钉 String 形态，见模块 KDoc「口径声明」）
CARRIER_TYPES = ("String", "String?")

# 扫描面：五个生产模块的 `src/main`（不随未跟踪目录漂移）
MODULES = ("app", "database", "crypto", "sync", "core")


def _is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def _matching_paren(text: str, open_idx: int) -> int:
    """返回与 `text[open_idx] == '('` 配对的右括号下标；找不到返回 -1。"""
    depth = 0
    idx = open_idx
    while idx < len(text):
        ch = text[idx]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return idx
        idx += 1
    return -1


def _split_params(params_text: str) -> list[str]:
    """按**顶层**逗号切分参数表（忽略嵌套 `<>` / `()` / `[]` 内的逗号）。"""
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    for ch in params_text:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current))
    return parts


def _class_body(text: str, body_start: int) -> str:
    """类体范围：从 `body_start` 到下一个**行首** `}`（顶层声明收口列）。"""
    m = re.search(r"^}", text[body_start:], re.M)
    end = body_start + m.start() if m else len(text)
    return text[body_start:end]


def scan(root: pathlib.Path) -> tuple[list[str], int]:
    """返回 (命中清单, 检查过的文件数)。"""
    hits: list[str] = []
    checked = 0
    for module in MODULES:
        src = root / module / "src" / "main"
        if not src.is_dir():
            continue
        for path in sorted(src.rglob("*.kt")):
            rel = path.relative_to(root).as_posix()
            checked += 1
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for m in DECL_RE.finditer(text):
                lineno = text[: m.start()].count("\n") + 1
                if _is_comment(text.splitlines()[lineno - 1]):
                    continue
                open_idx = text.index("(", m.end() - 1)
                close_idx = _matching_paren(text, open_idx)
                if close_idx < 0:
                    continue
                params = _split_params(text[open_idx + 1 : close_idx])
                carriers = []
                for raw in params:
                    pm = PARAM_RE.match(raw.strip())
                    if not pm:
                        continue
                    name, type_text = pm.group(1), pm.group(2).strip()
                    if (
                        SENSITIVE_NAME_RE.search(name)
                        and not EXEMPT_SUFFIX_RE.search(name)
                        and type_text in CARRIER_TYPES
                    ):
                        carriers.append("%s: %s" % (name, type_text))
                if not carriers:
                    continue
                class_name = m.group(1)
                if "%s::%s" % (rel, class_name) in ALLOWED:
                    continue
                body = _class_body(text, close_idx)
                if "override fun toString(" in body:
                    continue
                hits.append(
                    "MISSING_TO_STRING %s:%d  %s（明文口令载体 %s）"
                    % (rel, lineno, class_name, ", ".join(carriers))
                )
    return hits, checked


# `--selftest` 内嵌样本：绿样本（已覆写 / 非 String 承载 / 字段名不命中）0 命中；红样本命中一次。
SELFTEST_GOOD = """package demo

data class Safe(val username: String, val password: String) {
    override fun toString(): String = "Safe(<redacted>)"
}

data class CharCarrier(val password: CharArray)

data class IdCarrier(val passwordId: String?, val passwordKey: String?)

data class OtherField(val secret: String)
"""

SELFTEST_BAD = """package demo

data class Leaky(val username: String, val password: String)
"""


def _write(base: pathlib.Path, name: str, body: str) -> pathlib.Path:
    target = base / "app/src/main/java/demo" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")
    return base


def selftest() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        good_root = _write(pathlib.Path(tmp) / "good", "Good.kt", SELFTEST_GOOD)
        good_hits, good_checked = scan(good_root)
        bad_root = _write(pathlib.Path(tmp) / "bad", "Bad.kt", SELFTEST_BAD)
        bad_hits, bad_checked = scan(bad_root)

    ok = (
        good_checked == 1
        and bad_checked == 1
        and not good_hits
        and len(bad_hits) == 1
        and bad_hits[0].startswith("MISSING_TO_STRING")
    )
    print(
        "[selftest] 绿样本命中=%d（须 0，检查 %d 文件）；红样本命中=%d（须 1，检查 %d 文件）"
        % (len(good_hits), good_checked, len(bad_hits), bad_checked)
    )
    if not ok:
        print("[selftest] FAIL：判据与样本不符，闸门读数不可信")
        return 1
    print("[selftest] PASS：判据可分辨「已覆写 / 非 String 承载」与「明文载体未覆写」")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="明文口令载体 data class 的 toString() 护栏机检（ISSUE-P2-549）"
    )
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    parser.add_argument("--selftest", action="store_true", help="判据反校（内嵌正 / 反样本）")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve()
    hits, checked = scan(root)
    print(
        "[check_plaintext_carrier_to_string] 检查文件 %d 个（*/src/main/**）；允许清单 %d 个；命中 %d 处"
        % (checked, len(ALLOWED), len(hits))
    )
    for hit in hits:
        print("  HIT " + hit)
    if hits:
        print(
            "[check_plaintext_carrier_to_string] FAIL：承载明文口令的 data class 必须覆写 toString()"
            "（默认实现整份展开；先例 `Credentials`，见 ISSUE-P2-68 / ISSUE-P2-549）"
        )
        return 1
    print("[check_plaintext_carrier_to_string] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
