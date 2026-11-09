#!/usr/bin/env python3
"""运行文档链接与敏感数据检查；任一检查失败时返回非零退出码。

静态门禁的覆盖与失效口径（ENG-20261028-12）：

- **不得静默少跑**：清单里的每个脚本都必须存在，缺失即判失败。此前只按清单调
  `subprocess.run`，脚本被删 / 改名会以「少跑一项」的形式让本入口看起来仍然全绿。
- **判据自身先反校**：支持 `--selftest` 的四个扫描（受保护读口、裸 `CoroutineScope(`、
  明文载体 `toString`、异常 `message` 入文案槽）在正式扫描前先跑内嵌正 / 反样本，
  selftest 不过即整项判失败。理由：这四个脚本的价值全在正则判据上，判据写坏（例如
  锚点漂移、样本不再命中）时会**恒绿**——门禁存在不等于门禁被执行。
- **不整体退役**：这些扫描约束的是可枚举的静态规则（受保护读口白名单、作用域构造形态、
  明文载体护栏、文案槽来源），不是「锁源码文本」的用例，且各自带口径声明（哪些形态
  看不见）。逐条风险若有行为替代，应在对应条目的整改中说明后再改本清单，不在此处删除。
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# (脚本名, 是否支持 --selftest 判据反校)
CHECKS = (
    ("check_md_links.py", False),
    ("check_projection_read_safety.py", True),
    ("check_raw_coroutine_scope.py", True),
    ("check_plaintext_carrier_to_string.py", True),
    ("check_message_not_in_user_text.py", True),
)


def run(script: Path, args: list[str]) -> int:
    return subprocess.run(
        [sys.executable, str(script), *args], cwd=ROOT, check=False
    ).returncode


def main() -> int:
    failed: list[str] = []
    for name, has_selftest in CHECKS:
        path = Path("tools/doc") / name
        if not path.is_file():
            print(f"{name}: FAIL（检查脚本缺失，门禁少跑一项）", flush=True)
            failed.append(name)
            continue
        if has_selftest and run(path, ["--selftest"]) != 0:
            print(f"{name}: FAIL（--selftest 判据反校未通过，正式扫描读数不可信）", flush=True)
            failed.append(name)
            continue
        if run(path, []) != 0:
            print(f"{name}: FAIL", flush=True)
            failed.append(name)
            continue
        print(f"{name}: PASS", flush=True)

    if failed:
        print("未通过：" + "、".join(failed), flush=True)
    return int(bool(failed))


if __name__ == "__main__":
    sys.exit(main())