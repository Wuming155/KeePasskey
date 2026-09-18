#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""二级设置页 `Scaffold + TopAppBar` 骨架块的**等价性指纹**比对。

用途：把若干页各自写的 `Scaffold(...)` 调用块归一化后取哈希分组，
用于回答「这几处骨架是否真的逐字相同」——这正是 `SettingsSubscreenScaffold`
收敛工作的**成立前提**，前提不成立即构成视觉回归（§188 的
`PrivilegedBrowserSettingsScreen` 即为此例，见 `ISSUE-P3-195`）。

归一化只抹去**已由骨架参数承载的差异**（标题资源、`snackbarHost` 行、
尾随 lambda 的形参名），其余一字不改 ⇒ **配色 / insets / actions 等任何差异都会分桶**。
「归一化掉了什么」即「骨架必须以参数暴露什么」。

用法：
    python tools/doc/scaffold_block_fingerprint.py <git rev> <相对目录> <页名，不带 .kt>…
例：
    python tools/doc/scaffold_block_fingerprint.py 5690ecf^ \\
        app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens \\
        AboutSettingsScreen TotpSettingsScreen PrivilegedBrowserSettingsScreen

立规缘由（§175 / §178 同因）：一次性脚本口径不固化就会重演「实测结论靠记忆抄录」的失真。
"""
import hashlib
import re
import subprocess
import sys


def source(rev: str, path: str) -> str:
    out = subprocess.run(['git', 'show', f'{rev}:{path}'],
                         capture_output=True, text=True, encoding='utf-8')
    if out.returncode != 0:
        raise SystemExit(f'git show 失败：{rev}:{path}\n{out.stderr}')
    return out.stdout


def scaffold_block(src: str) -> str:
    """取出首个 `Scaffold(` 调用块（按括号配平，含字符串字面量里的括号亦计入——
    骨架块内不含字符串括号，故不处理；若将来出现须先按 `long_functions.py` 的口径剥离）。"""
    start = src.index('    Scaffold(')
    i = start + len('    Scaffold(')
    depth = 1
    while depth:
        ch = src[i]
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        i += 1
    return src[start:i + 1]


def normalize(block: str) -> str:
    text = re.sub(r'text = stringResource\([^)]*\)', 'text = <TITLE>', block)
    text = re.sub(r'\n *snackbarHost = \{[^}]*\},', '\n', text)
    return re.sub(r'\) \{ \w+ ->', ') {', text)


def main() -> int:
    if len(sys.argv) < 4:
        raise SystemExit('用法：scaffold_block_fingerprint.py <git rev> <相对目录> <页名>…')
    rev, directory, pages = sys.argv[1], sys.argv[2], sys.argv[3:]
    groups: dict[str, list[tuple[str, int]]] = {}
    for page in pages:
        block = scaffold_block(source(rev, f'{directory}/{page}.kt'))
        digest = hashlib.sha256(normalize(block).encode()).hexdigest()[:12]
        groups.setdefault(digest, []).append((page, len(block.splitlines())))
    for digest, members in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        print(f'{digest}  {len(members)} 页')
        for page, lines in members:
            print(f'    {lines:>3} 行  {page}')
    if len(groups) > 1:
        print(f'\n分桶 {len(groups)} 个 ⇒ 归一化项之外仍有差异，'
              f'收敛前须逐桶比对并以参数暴露（不得强行统一）。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
