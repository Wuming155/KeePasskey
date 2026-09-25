"""
从 main 源集扫描 @Preview 预览函数：
1) 将 private 预览函数提升为 internal（供 screenshotTest 调用）
2) 按 package 生成 screenshotTest wrapper（带相同 @Preview 注解 + 统一 locale）

用法:
  python tools/export_previews/generate_screenshot_test_wrappers.py
  PREVIEW_LOCALE=en python tools/export_previews/generate_screenshot_test_wrappers.py

默认 locale=zh-CN（导出中文界面）；PREVIEW_LOCALE=en 则资源串走英文。
夹具里硬编码的中文文案不受 locale 影响。
"""
from __future__ import annotations

import os
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN_ROOT = ROOT / "app" / "src" / "main" / "java"
OUT_ROOT = ROOT / "app" / "src" / "screenshotTest" / "kotlin"
DEFAULT_LOCALE = "zh-CN"

# 匹配：@Preview（可跨行）+ 可选中间注解（@OptIn 等）+ @Composable + private/internal fun Name() {
# ISSUE-P3-318：参数组原写作 `(?:\((?:[^()\n]|\([^()\n]*\))*\))?`，其 `(?:A|B)*` 结构被
# CodeQL 判为 py/redos 指数回溯（high，#355/#356）。现改写为**线性时间等价**形态
# `[^()\n]*(?:\([^()\n]*\)[^()\n]*)*`——无交替分支重叠，任一位置只有唯一解析路径
# （`(` 只能开组、组内只能是非括号字符），语言完全等价，匹配结果零变更。
PREVIEW_BLOCK = re.compile(
    r"(?P<ann>(?:[ \t]*(?:@androidx\.compose\.ui\.tooling\.preview\.Preview|@Preview)"
    r"(?:\([^()\n]*(?:\([^()\n]*\)[^()\n]*)*\))?\n)+)"
    r"(?P<extra>(?:[ \t]*@\w+(?:\([^)\n]*\))?[^\n]*\n)*?)"
    r"(?P<composable>[ \t]*@Composable\n)"
    r"(?P<vis>[ \t]*)(?P<mods>private|internal)\s+fun\s+(?P<name>\w+)\s*\(\s*\)\s*\{",
    re.MULTILINE,
)
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)
PREVIEW_LINE_RE = re.compile(r"^@Preview\s*\(", re.MULTILINE)


def inject_locale(ann_text: str, locale: str) -> str:
    """给每条 @Preview(...) 注入 locale="xx"，若已有 locale 则替换。"""
    out_lines: list[str] = []
    i = 0
    lines = ann_text.splitlines()
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if not (
            stripped.startswith("@Preview(")
            or stripped.startswith("@androidx.compose.ui.tooling.preview.Preview(")
        ):
            # 单行收尾或其它内容
            out_lines.append(line)
            i += 1
            continue
        # 收集完整注解（可能跨多行，直到括号平衡）
        block = [line]
        balance = line.count("(") - line.count(")")
        i += 1
        while balance > 0 and i < len(lines):
            block.append(lines[i])
            balance += lines[i].count("(") - lines[i].count(")")
            i += 1
        joined = "\n".join(block)
        # 统一短名
        joined = joined.replace(
            "@androidx.compose.ui.tooling.preview.Preview", "@Preview"
        )
        # `ISSUE-P3-188` §167：源面已改用平台命名常量 `Configuration.UI_MODE_NIGHT_YES`，
        # 派生面**照原样搬运**并补 import（原实现在此处把它改写回裸 `0x20`，是为省一行 import
        # 而把魔法数字重新引回——现由下方 `needs_configuration_import` 统一处理）。
        # 去掉已有 locale
        joined = re.sub(r"\s*locale\s*=\s*\"[^\"]*\"\s*,?", "", joined)
        # 在 @Preview( 后插入 locale
        joined = re.sub(
            r"(@Preview\s*\(\s*)",
            r'\1locale = "' + locale + r'", ',
            joined,
            count=1,
        )
        # 若变成 @Preview(locale = "zh-CN", ) 这类尾逗号，收一下
        joined = re.sub(r",\s*\)", ")", joined)
        for bl in joined.splitlines():
            out_lines.append("    " + bl.strip() if bl.strip() else bl)
    return "\n".join(out_lines)


def normalize_ann(block: str, locale: str) -> str:
    return inject_locale(block, locale)


def main() -> int:
    locale = os.environ.get("PREVIEW_LOCALE", DEFAULT_LOCALE).strip() or DEFAULT_LOCALE
    by_pkg: dict[str, list[str]] = defaultdict(list)
    promoted = 0

    for kt in sorted(MAIN_ROOT.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        if "@Preview" not in text and "tooling.preview.Preview" not in text:
            continue
        pkg_m = PACKAGE_RE.search(text)
        if not pkg_m:
            continue
        pkg = pkg_m.group(1)

        def repl(m: re.Match[str]) -> str:
            nonlocal promoted
            name = m.group("name")
            ann = normalize_ann(m.group("ann"), locale)
            by_pkg[pkg].append(
                f"// 源: {kt.relative_to(ROOT).as_posix()}\n"
                f"{ann}\n"
                f"    @PreviewTest\n"
                f"    @Composable\n"
                f"    internal fun {name}ScreenshotExport() = {name}()\n"
            )
            promoted += 1
            # 保留 @Preview / @OptIn / @Composable，只把 private 提升为 internal
            return (
                f"{m.group('ann')}"
                f"{m.group('extra')}"
                f"{m.group('composable')}"
                f"{m.group('vis')}internal fun {name}() {{"
            )

        new_text, n = PREVIEW_BLOCK.subn(repl, text)
        if n:
            # `ISSUE-P3-188` §167 附带修复：原写法 `kt.write_text(...)` 在 Windows 上按
            # `os.linesep` 落盘，会把 LF 文件**整文件改写成 CRLF**——正是 `.gitattributes`
            # （`* text=auto eol=lf`）里登记过的历史事故形态（git 层会在 add 时归一，故不污染
            # 提交，但工作区整文件行尾被翻，`git status` 与后续工具全部吃噪声）。
            # 读写统一显式 `newline=""`：读到什么行尾就写回什么行尾。
            with open(kt, "w", encoding="utf-8", newline="") as fh:
                fh.write(new_text)

    # 清掉旧生成物
    if OUT_ROOT.exists():
        for old in OUT_ROOT.rglob("Generated*PreviewWrappers.kt"):
            old.unlink()

    count = 0
    for pkg, chunks in sorted(by_pkg.items()):
        rel = Path(*pkg.split(".")) / "GeneratedPreviewWrappers.kt"
        out = OUT_ROOT / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        # 源面用了平台命名常量时，派生面必须带上其 import（否则 unresolved reference）
        cfg_import = (
            "import android.content.res.Configuration\n"
            if any("Configuration." in c for c in chunks)
            else ""
        )
        body = (
            "// 自动生成：勿手改。tools/export_previews/generate_screenshot_test_wrappers.py\n"
            f"// preview-screenshot-test-engine 用；locale={locale}\n\n"
            f"package {pkg}\n\n"
            + cfg_import
            + "import androidx.compose.runtime.Composable\n"
            "import androidx.compose.ui.tooling.preview.Preview\n"
            "import com.android.tools.screenshot.PreviewTest\n\n"
            + "\n".join(chunks)
        )
        # 与上面同源：显式 `newline=""` + 内存中的 `\n` ⇒ 生成物一律 LF（对齐 `* text=auto eol=lf`）
        with open(out, "w", encoding="utf-8", newline="") as fh:
            fh.write(body)
        count += len(chunks)

    print(f"locale={locale} promoted={promoted} wrappers={count} packages={len(by_pkg)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
