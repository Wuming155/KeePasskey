#!/usr/bin/env python3
"""运行文档链接与敏感数据检查；任一检查失败时返回非零退出码。"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKS = (
    "check_md_links.py",
    "check_projection_read_safety.py",
    "check_raw_coroutine_scope.py",
    "check_plaintext_carrier_to_string.py",
    "check_message_not_in_user_text.py",
)


def main() -> int:
    failed = []
    for name in CHECKS:
        path = Path("tools/doc") / name
        result = subprocess.run([sys.executable, str(path)], cwd=ROOT, check=False)
        print(f"{name}: {'PASS' if result.returncode == 0 else 'FAIL'}", flush=True)
        if result.returncode:
            failed.append(name)
    return int(bool(failed))


if __name__ == "__main__":
    sys.exit(main())
