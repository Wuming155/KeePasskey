#!/usr/bin/env bash
# 安全复核报告一致性扫描 —— bash 薄封装（逻辑单源见 check_recheck_consistency.py）
#
# 用途 / 判据 / 豁免 / 退出码：见同目录 `check_recheck_consistency.py` 模块 docstring。
# §283 起实现迁至 Python（PowerShell 会话可直接 `python tools/audit/check_recheck_consistency.py`）；
# 本文件仅为历史命令行（`bash tools/audit/check_recheck_consistency.sh`）保留入口。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if command -v python3 >/dev/null 2>&1; then
  exec python3 "$here/check_recheck_consistency.py" "$@"
elif command -v python >/dev/null 2>&1; then
  exec python "$here/check_recheck_consistency.py" "$@"
else
  echo "FAIL: 未找到 python3 / python" >&2
  exit 2
fi
