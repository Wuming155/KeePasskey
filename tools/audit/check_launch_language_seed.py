#!/usr/bin/env python3
"""启动语言种子接线机检（ISSUE-P3-459 续：冷启动首帧闪系统语言）。

## 防的是什么

语言偏好的真源是 `SettingsRepository` 的 **DataStore**，首值是异步冷读（数十~数百毫秒）。
三条消费通道在首值到达前只能拿到默认值，于是冷启动会先按**系统 locale** 渲染一帧再翻成
应用内语言（用户上次选 English 时表现为「先闪一帧中文」）。

整改（§415）引入 `AppLaunchLanguageSeed`：`Application.onCreate` 主线程同步灌种
（SharedPreferences 冷读 1~5ms），此后所有语言读取退化为一次 volatile 读。下面四条接线
**缺一即闪一帧**——正是上次事故「机检全绿、真机全中文」的同一类假绿（§305 口径）：
机检只盯字面量有没有中文，看不见通道跟不跟语言，故本条按**接线点**钉死：

1. `MainApplication.onCreate` 必须调 `AppLaunchLanguageSeed.install(`（否则种子永不就绪）
2. `SettingsUiStateProjection` 的 `initialValue` 必须取 `AppLaunchLanguageSeed.read()`
   （否则 Compose 首帧 `appLanguage` 恒 `SYSTEM` ⇒ 首帧按系统 locale 渲染）
3. `AppLocaleTracker` 的 init 必须取 `AppLaunchLanguageSeed.read()`（否则 `StringsProvider`
   首帧回落 `context.resources`，同样是系统 locale —— §414 那次事故的原路径）
4. `RealSettingsRepository.setAppLanguage` 必须调 `AppLaunchLanguageSeed.store(`
   （否则镜像与真源漂移，表现为「切了语言重启又变回去」）

四条均为 fail-closed（非 0 即红）。**本脚本只判接线存在，不判行为**——
`SharedPreferences` 的实际取值、DataStore 覆盖时序仍须真机走查。
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile

APP_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SELFTEST_ROOT = pathlib.Path(tempfile.gettempdir()) / "keepasskey_launch_seed_selftest"

MAIN_APPLICATION = "app/src/main/java/com/keepasskey/app/MainApplication.kt"
SEED_KT = "app/src/main/java/com/keepasskey/app/ui/AppLaunchLanguageSeed.kt"
SETTINGS_PROJECTION = "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
LOCALIZATION = "app/src/main/java/com/keepasskey/app/ui/AppShellLocalization.kt"
REAL_SETTINGS = "app/src/main/java/com/keepasskey/app/data/repository/RealSettingsRepository.kt"

# (编号, 说明, 文件相对路径, 起始标记, 期待命中的子串, 期待**缺失**的子串（可选）)
RULES = (
    (
        "E1",
        "MainApplication.onCreate 灌启动种子",
        MAIN_APPLICATION,
        "override fun onCreate()",
        "AppLaunchLanguageSeed.install(",
        None,
    ),
    (
        "E2",
        "Compose 首帧 initialValue 取种子",
        SETTINGS_PROJECTION,
        "initialValue =",
        "AppLaunchLanguageSeed.read()",
        None,
    ),
    (
        "E3",
        "AppLocaleTracker.init 取种子（StringsProvider 首帧通道）",
        LOCALIZATION,
        "class AppLocaleTracker",
        "AppLaunchLanguageSeed.readOrNull()",
        None,
    ),
    (
        "E5",
        "AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）",
        LOCALIZATION,
        "class AppLocaleTracker",
        "seedFromSettingsSync",
        None,
    ),
    (
        "E4",
        "RealSettingsRepository.setAppLanguage 镜像种子",
        REAL_SETTINGS,
        "override suspend fun setAppLanguage",
        "AppLaunchLanguageSeed.store(",
        None,
    ),
)


def block_of(text: str, marker: str) -> str | None:
    """取 `marker` 所在位置到下一个同类顶层标记之间的片段（块提取，够用且不依赖 AST）。"""
    start = text.find(marker)
    if start < 0:
        return None
    rest = text[start + len(marker):]
    # 终止：下一个 `fun ` / `class ` / `object ` / `override` 顶层声明
    cut = len(rest)
    for token in ("\nfun ", "\nclass ", "\nobject ", "\ninternal ", "\n@"):
        pos = rest.find(token)
        if 0 < pos < cut:
            cut = pos
    return rest[:cut]


def check(root: pathlib.Path) -> list[tuple[str, str]]:
    failures: list[tuple[str, str]] = []
    for code, desc, rel, marker, expect, forbid in RULES:
        text = (root / rel).read_text(encoding="utf-8")
        snippet = block_of(text, marker)
        if snippet is None:
            failures.append((code, f"{desc}：在 {rel} 找不到标记 `{marker}`（接线点被改名或搬走）"))
            continue
        if expect not in snippet:
            failures.append((code, f"{desc}：{rel} 的 `{marker}` 块内未见 `{expect}`"))
        if forbid is not None and forbid in snippet:
            failures.append((code, f"{desc}：{rel} 的 `{marker}` 块内不应出现 `{forbid}`"))
    return failures


def make_tree(root: pathlib.Path, *, install: bool, initial: bool, tracker: bool, store: bool) -> None:
    """按四条接线的通/断造一棵样本仓（只写与接线有关的极简片段）。"""
    for rel in (MAIN_APPLICATION, SEED_KT, SETTINGS_PROJECTION, LOCALIZATION, REAL_SETTINGS):
        target = root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("", encoding="utf-8")
    (root / MAIN_APPLICATION).write_text(
        "override fun onCreate() {\n"
        + ("    AppLaunchLanguageSeed.install(this)\n" if install else "    // 未灌种\n")
        + "}\n",
        encoding="utf-8",
    )
    (root / SETTINGS_PROJECTION).write_text(
        "    initialValue = SettingsUiState("
        + ("appLanguage = AppLaunchLanguageSeed.read()" if initial else "")
        + ")\n",
        encoding="utf-8",
    )
    (root / LOCALIZATION).write_text(
        "class AppLocaleTracker {\n"
        + (
            "    init { val seeded = AppLaunchLanguageSeed.readOrNull()\n"
            "        locale = if (seeded != null) localeFor(seeded) else seedFromSettingsSync() }\n"
            "    private fun seedFromSettingsSync(): Locale? = runCatching {\n"
            "        runBlocking(Dispatchers.IO) {\n"
            "            withTimeout(SEED_TIMEOUT_MS) { settings.getSettings().first().appLanguage }\n"
            "        }}\n"
            "        .getOrElse { AppLanguage.SYSTEM }.let { lang ->\n"
            "            AppLaunchLanguageSeed.store(context, lang); localeFor(lang) }\n"
            if tracker
            else "    init {}\n"
        )
        + "}\n",
        encoding="utf-8",
    )
    (root / REAL_SETTINGS).write_text(
        "override suspend fun setAppLanguage(l: AppLanguage) {\n"
        + ("    AppLaunchLanguageSeed.store(context, l)\n" if store else "    // 未镜像\n")
        + "}\n",
        encoding="utf-8",
    )


def selftest() -> int:
    """自校：逐条通断造样本，验证每条判据**恰能**分辨自己那一条断掉的情形。"""
    ok = True
    cases = (
        ("四线齐备（应 0）", dict(install=True, initial=True, tracker=True, store=True), 0),
        ("只断 install", dict(install=False, initial=True, tracker=True, store=True), 1),
        ("只断 initialValue", dict(install=True, initial=False, tracker=True, store=True), 1),
        ("只断 tracker 取种（连带慢路径）", dict(install=True, initial=True, tracker=False, store=True), 2),
        ("只断 store 镜像", dict(install=True, initial=True, tracker=True, store=False), 1),
        ("全断（应 5）", dict(install=False, initial=False, tracker=False, store=False), 5),
    )
    for name, flags, expect_fail in cases:
        root = SELFTEST_ROOT / name.replace(" ", "_").replace("（", "").replace("）", "")
        make_tree(root, **flags)
        got = len(check(root))
        matched = got == expect_fail
        ok = ok and matched
        print(f"[selftest] {'OK ' if matched else 'BAD'} {name}：失败项 {got}（期望 {expect_fail}）")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="启动语言种子接线机检")
    parser.add_argument("--selftest", action="store_true", help="跑自校样本（不扫真仓）")
    parser.add_argument("--root", type=pathlib.Path, default=APP_ROOT, help="扫描根目录")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    failures = check(args.root)
    print("=== 启动语言种子接线机检（五条 fail-closed）===")
    for code, desc, *_ in RULES:
        hit = [f for f in failures if f[0] == code]
        print(f"[{code}] {desc}\t{'FAIL' if hit else 'ok'}")
    for code, msg in failures:
        print(f"  {code} FAIL：{msg}")
    if failures:
        print(f"--- 失败 {len(failures)} 条；任一不通过即冷启动首帧会闪系统语言 ---")
        return 1
    print("--- launch_language_seed=OK（五条接线齐全）---")
    return 0


if __name__ == "__main__":
    sys.exit(main())
