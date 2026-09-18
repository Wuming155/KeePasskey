"""装配表「逻辑行」分类计数（PD-11 重开条件的可执行判据）。

用法：
    python tools/doc/logic_lines.py <相对路径.kt> <函数名> [<函数名>…]

把函数体逐行分类并输出计数：
- `composable(` 分支行 / `name = …` 具名实参行 / `val x by …` 状态绑定行 / 注释 / 空行
- **逻辑行**：以 `if` / `when` / `for` / `while` / `try` / `return` / `throw` 起始的行

PD-11 的重开条件即「装配表内逻辑行 ≥ 5，或出现 `when` / 循环 / 状态写入」⇒ 本工具给出可复核的数字，
不靠印象。函数名按「`fun <名>(`」定位（`NavGraphBuilder.keepasskeySettingsNavGraph` 这类
带接收者的声明，直接用 `fun 函数名(` 会找不到——§184 首版即踩此坑）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOGIC = re.compile(r"^\s*(if |when |for |while |try |return |throw )")


def classify(rel: str, fn: str) -> None:
    path = ROOT / rel
    if not path.is_file():
        print(f"ERR 文件不存在：{rel}")
        return
    lines = path.read_text(encoding="utf-8").splitlines()
    starts = [
        i for i, l in enumerate(lines)
        if re.search(rf"\bfun\s+(?:[A-Za-z0-9_]+\.)?{re.escape(fn)}\s*\(", l)
    ]
    if not starts:
        print(f"ERR 未找到函数 {fn}（检查声明是否带接收者 / 是否已改名）")
        return
    for i in starts:
        depth, end, started = 0, i, False
        for j in range(i, len(lines)):
            depth += lines[j].count("{") - lines[j].count("}")
            started = started or "{" in lines[j]
            if started and depth == 0:
                end = j
                break
        body = lines[i:end + 1]
        logic = [l.strip() for l in body if LOGIC.match(l)]
        counts = {
            "总行": len(body),
            "composable分支": sum(1 for l in body if re.match(r"^\s+composable\(", l)),
            "具名实参": sum(1 for l in body if re.match(r"^\s+\w+ = ", l)),
            "状态绑定": sum(1 for l in body if re.match(r"^\s+val \w+( :.*)? by ", l) or "hiltViewModel()" in l),
            "注释": sum(1 for l in body if l.strip().startswith("//")),
            "空行": sum(1 for l in body if not l.strip()),
            "逻辑行": len(logic),
        }
        # 只报「是否触发 PD-11 重开条件」——**是否属装配表由人判定**（PD-11 边界①：豁免只覆盖
        # NavGraphBuilder 扩展函数，屏幕内容函数即使逻辑行为 0 也不因此豁免）。
        reopened = len(logic) >= 5 or any(l.startswith(("when ", "for ", "while ")) for l in logic)
        print(f"{fn} (L{i + 1}-{end + 1}): " + "  ".join(f"{k}={v}" for k, v in counts.items()))
        print(f"    → PD-11 重开条件：{'**已触发**（逻辑行 ≥5 或含 when/循环）' if reopened else '未触发'}"
              "；是否属豁免范围须按 PD-11 边界①自行判定")
        for l in logic[:10]:
            print(f"      逻辑行: {l[:100]}")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    classify(argv[0], argv[1])
    for fn in argv[2:]:
        classify(argv[0], fn)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
