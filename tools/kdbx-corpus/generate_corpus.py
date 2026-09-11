#!/usr/bin/env python3
"""KeePasskey `.kdbx` 互操作语料生成 / 校验脚本（ISSUE-P3-38）。

它消除的是 `crypto/src/test/resources/argon2-interop/README.md` §3 里「人工 GUI 七步 + 逐条复核 +
手写伴生 JSON + 双落位」这段**易错且不可复现**的人工流程；产物仍必须是
**KeePass 官方实现（KeePass 2.61.1 / KeePassXC 官方 CLI）** 的输出——**严禁**以本仓库自生成
`.kdbx` 或第三方库（pykeepass / kdbxweb 等）产物冒充互操作证据。

设计原则（与仓库既有纪律一致）：
1. **fail-closed**：环境缺失 / 参数不符 / 文件名与文件头不一致 / 目标已存在且未 `--force`，
   一律非零退出并给出可操作指引，**绝不**产出「文件名撒谎」的语料；
2. **零第三方依赖**：只用标准库 + 同目录的 `kdbx_header.py`；
3. **口令不落 argv**：一次性测试口令经 stdin 传入子进程，避免出现在进程列表里；
4. **真实参数为准**：伴生 JSON 的全部 KDF 字段与文件名一律取自**文件头实测值**，不取自预期值。

用法见同目录 `README.md`。
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import kdbx_header

# ============================================================================
# 常量（公开的一次性测试口令与占位内容；严禁替换为真实凭据/数据）
# ============================================================================

#: 一次性测试口令。与 `RealKdbxCorpusUnlockTest.CORPUS_PASSPHRASE` 及 README §4 保持同值。
THROWAWAY_PASSPHRASE = "Test-Vector-Only-2026!"

#: 占位条目（标题/用户名/密码一律为明显的假值，README §3.0 第 2 条）
PLACEHOLDER_TITLES: Tuple[str, ...] = ("Vector-Sample-1", "Vector-Sample-2", "Vector-Sample-3")
PLACEHOLDER_USER = "vector-user"
PLACEHOLDER_SECRET = "vector-not-a-real-secret"

REPO_ROOT = Path(__file__).resolve().parents[2]

#: 双落位目录（README §3.4：`src/test/resources` 与 `androidTest/assets` **不可互替**）
PLACEMENT_DIRS: Tuple[Path, ...] = (
    REPO_ROOT / "crypto" / "src" / "test" / "resources" / "argon2-interop",
    REPO_ROOT / "database" / "src" / "androidTest" / "assets" / "argon2-interop",
)

#: 来源短标签 → 伴生 JSON `source` 字段（用例要求含 `keepass`，忽略大小写）
SOURCE_LABELS: Dict[str, str] = {
    "keepass2611": "KeePass 2.61.1",
    "keepassxc": "KeePassXC",
}

ALLOWED_KDF = ("argon2d", "argon2id")
SUPPORTED_ARGON2_VERSIONS = (16, 19)

#: 第三方实现关键字（含子串 `keepass`，能骗过 README §6.2 的宽子串规则）；
#: 用于拒绝「以非官方实现产物冒充互操作证据」。
THIRD_PARTY_PRODUCERS = ("pykeepass", "kdbxweb", "keepass2android", "keepassxc-browser", "python-keepass")

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_ENV = 3
EXIT_MISMATCH = 4
EXIT_REFUSED = 5


class CorpusError(Exception):
    """带退出码的脚本级错误。"""

    def __init__(self, message: str, code: int = EXIT_REFUSED) -> None:
        super().__init__(message)
        self.code = code


# ============================================================================
# 命名与校验
# ============================================================================


def canonical_name(info: kdbx_header.KdbxHeaderInfo, source_key: str) -> str:
    """按 README §6.1 由**文件头实测参数**推导规范文件名。"""
    if info.kdf not in ALLOWED_KDF:
        raise CorpusError(f"文件头 KDF 为 {info.kdf}，语料仅接受 {ALLOWED_KDF}", EXIT_MISMATCH)
    if info.argon2_version not in SUPPORTED_ARGON2_VERSIONS:
        raise CorpusError(
            f"文件头 Argon2 版本为 {info.argon2_version}，语料仅接受 {SUPPORTED_ARGON2_VERSIONS}",
            EXIT_MISMATCH,
        )
    for field, value in (
        ("迭代数 I", info.iterations),
        ("内存 M", info.memory_kib),
        ("并行度 P", info.parallelism),
        ("盐 S", info.salt_hex),
    ):
        if value is None:
            raise CorpusError(f"文件头缺失 KDF 参数「{field}」，无法构造规范文件名", EXIT_MISMATCH)
    # README §6.1 规定文件名中的内存单位为 **MiB**（例：64 MiB → `-m64`），故由 KiB 换算；
    # 非整 MiB 无法按约定表达，fail-closed 拒绝（避免产出「文件名撒谎」的语料）。
    if info.memory_kib % 1024 != 0:
        raise CorpusError(
            f"文件头内存 M={info.memory_kib}KiB 非整 MiB，无法按 README §6.1 命名",
            EXIT_MISMATCH,
        )
    return (
        f"{info.kdf}-v{info.argon2_version}-t{info.iterations}"
        f"-m{info.memory_kib // 1024}-p{info.parallelism}-{source_key}.kdbx"
    )


def build_companion_json(
    info: kdbx_header.KdbxHeaderInfo,
    source_key: str,
    entry_count: int,
    entry_titles: Sequence[str],
) -> dict:
    """构造伴生元数据（字段与 README §6.2 schema 逐项对齐）。"""
    if entry_count <= 0:
        raise CorpusError("entryCount 必须为正整数（语料必须含占位条目）", EXIT_MISMATCH)
    return {
        "source": SOURCE_LABELS[source_key],
        "kdf": info.kdf,
        "version": info.argon2_version,
        "iterations": info.iterations,
        "memoryKib": info.memory_kib,
        "parallelism": info.parallelism,
        "entryCount": entry_count,
        "entryTitles": list(entry_titles),
        "saltHex": info.salt_hex,
        "passphraseIsThrowaway": True,
        "containsRealData": False,
        "note": "一次性口令生成、库内仅占位条目；口令为公开测试常量，见 README §4",
    }


def assert_consistent_with_json(info: kdbx_header.KdbxHeaderInfo, payload: dict) -> None:
    """逐字段核对「文件头实测值」与既有伴生 JSON（复核入库前的人工/外部产物）。"""
    expectations = (
        ("kdf", info.kdf),
        ("version", info.argon2_version),
        ("iterations", info.iterations),
        ("memoryKib", info.memory_kib),
        ("parallelism", info.parallelism),
        ("saltHex", info.salt_hex),
    )
    for key, actual in expectations:
        if key in payload and payload[key] != actual:
            raise CorpusError(
                f"伴生 JSON 与文件头不一致：{key} JSON={payload[key]!r} 文件头={actual!r}",
                EXIT_MISMATCH,
            )
    if payload.get("passphraseIsThrowaway") is not True:
        raise CorpusError("伴生 JSON 的 passphraseIsThrowaway 必须为 true", EXIT_MISMATCH)
    if payload.get("containsRealData") is not False:
        raise CorpusError("伴生 JSON 的 containsRealData 必须为 false", EXIT_MISMATCH)

    source = str(payload.get("source", ""))
    lowered = source.lower()
    if "keepass" not in lowered:
        raise CorpusError(
            f"伴生 JSON 的 source 必须含 'keepass'（禁止自建夹具冒充互操作语料）：{source!r}",
            EXIT_MISMATCH,
        )
    # 加固：README §6.2 的子串规则过宽——`pykeepass` / `kdbxweb-keepass` 一类**第三方实现**
    # 也含子串 `keepass`，会被原规则放行，从而以「非官方实现产物」冒充互操作证据。
    # 官方允许的来源只有 KeePass 2.61.1 与 KeePassXC（README §3）。
    for third_party in THIRD_PARTY_PRODUCERS:
        if third_party in lowered:
            raise CorpusError(
                f"伴生 JSON 的 source 命中了第三方实现关键字 `{third_party}`：{source!r}。"
                "官方允许来源仅 KeePass 2.61.1 / KeePassXC（README §3），"
                "第三方实现（pykeepass / kdbxweb 等）产物不得充当互操作证据。",
                EXIT_MISMATCH,
            )


# ============================================================================
# 子进程
# ============================================================================


def run_cli(cli: str, args: Sequence[str], stdin_lines: Sequence[str] = ()) -> Tuple[int, str, str]:
    """执行 keepassxc-cli；口令经 stdin 传入（不落 argv）。"""
    stdin_text = "".join(f"{line}\n" for line in stdin_lines)
    try:
        completed = subprocess.run(
            [cli, *args],
            input=stdin_text,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    except OSError as exc:
        raise CorpusError(f"无法执行 {cli}：{exc}", EXIT_ENV) from exc
    return completed.returncode, completed.stdout, completed.stderr


def probe_cli(cli: str) -> dict:
    """探测 CLI 可用性与能力；返回可用的 KDF 参数开关（缺失即如实登记）。"""
    rc, out, err = run_cli(cli, ["--version"])
    if rc != 0:
        raise CorpusError(f"{cli} --version 失败（rc={rc}）：{err.strip() or out.strip()}", EXIT_ENV)
    version = (out + err).strip().splitlines()[0] if (out + err).strip() else "(版本输出为空)"

    rc, help_out, help_err = run_cli(cli, ["db-create", "--help"])
    if rc != 0:
        raise CorpusError(
            f"{cli} 不支持 `db-create` 子命令（rc={rc}）：{help_err.strip() or help_out.strip()}",
            EXIT_ENV,
        )
    help_text = help_out + help_err
    # KeePassXC CLI 历来**不提供** Argon2 变体/版本的直接开关；此处只做事实探测，不假定能力。
    kdf_switches = [
        switch
        for switch in ("--kdf", "--iterations", "--memory", "--parallelism", "--decryption-time")
        if switch in help_text
    ]
    return {"version": version, "kdf_switches": kdf_switches}


def list_entries(cli: str, db_path: Path) -> List[str]:
    """用 `keepassxc-cli ls -R` 列出条目（标题），用于统计 entryCount / entryTitles。"""
    rc, out, err = run_cli(cli, ["ls", "-R", "-q", str(db_path)], [THROWAWAY_PASSPHRASE])
    if rc != 0:
        raise CorpusError(
            f"列出条目失败（rc={rc}）：{err.strip() or out.strip()}；"
            "可用 --entry-count/--titles 显式提供，或用官方 GUI 复核后手工填写",
            EXIT_MISMATCH,
        )
    entries = [line.strip() for line in out.splitlines() if line.strip()]
    if not entries:
        raise CorpusError("库内条目为 0，语料必须含占位条目", EXIT_MISMATCH)
    return entries


# ============================================================================
# 落位
# ============================================================================


def place_corpus(kdbx_path: Path, payload: dict, target_name: str, force: bool) -> List[Path]:
    """把 `.kdbx` 与伴生 `.json` 复制到两个落位目录（已存在且未 --force 即拒绝）。"""
    written: List[Path] = []
    json_text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    companion_name = Path(target_name).with_suffix(".json").name
    for directory in PLACEMENT_DIRS:
        directory.mkdir(parents=True, exist_ok=True)
        target_kdbx = directory / target_name
        target_json = directory / companion_name
        if not force:
            for existing in (target_kdbx, target_json):
                if existing.exists():
                    raise CorpusError(f"目标已存在，拒绝覆盖（如需覆盖请加 --force）：{existing}")
        shutil.copy2(kdbx_path, target_kdbx)
        target_json.write_text(json_text, encoding="utf-8")
        written.extend([target_kdbx, target_json])
    return written


# ============================================================================
# 子命令
# ============================================================================


def cmd_check(cli: str) -> int:
    """环境自检：CLI 可用性与能力、落位目录可写性。缺 CLI 即非零退出（fail-closed）。"""
    print(f"[check] keepassxc-cli：{cli}")
    try:
        probe = probe_cli(cli)
    except CorpusError as exc:
        print(f"[check] 失败：{exc}", file=sys.stderr)
        print(
            "[check] 请先安装 KeePassXC 官方 CLI 并确保 `keepassxc-cli` 在 PATH 中；"
            "Windows 默认路径形如 `C:\\Program Files\\KeePassXC\\keepassxc-cli.exe`，可用 --cli 指定。",
            file=sys.stderr,
        )
        return exc.code
    print(f"[check] 版本：{probe['version']}")
    switches = probe["kdf_switches"]
    if switches:
        print(f"[check] db-create 可见的 KDF 相关开关：{', '.join(switches)}")
    else:
        print(
            "[check] 注意：db-create 帮助中**未见**任何 KDF 参数开关。"
            "KeePassXC CLI 历来不提供 Argon2 变体/版本/t/m/p 的直接设定，"
            "因此 `--generate` 产出的库很可能与本目录要求的参数不符；"
            "此时应改用官方 GUI 建库（README §3.2）后执行 `--ingest` 复核入库。"
        )
    for directory in PLACEMENT_DIRS:
        directory.mkdir(parents=True, exist_ok=True)
        probe_file = directory / ".write-probe"
        try:
            probe_file.write_text("ok", encoding="utf-8")
            probe_file.unlink()
        except OSError as exc:
            print(f"[check] 失败：落位目录不可写 {directory}：{exc}", file=sys.stderr)
            return EXIT_ENV
        print(f"[check] 落位目录可写：{directory}")
    print("[check] 通过。注意：本检查**不**代表语料已存在——语料是否入库请看 --verify。")
    return EXIT_OK


def cmd_verify(kdbx_path: Path, json_path: Optional[Path]) -> int:
    """只读核验：解析文件头参数；给了伴生 JSON 就逐字段比对。"""
    if not kdbx_path.is_file():
        print(f"[verify] 文件不存在：{kdbx_path}", file=sys.stderr)
        return EXIT_USAGE
    info = kdbx_header.parse_file(str(kdbx_path))
    print(f"[verify] {kdbx_path}")
    print(f"[verify]   version={info.major_version}.{info.minor_version} cipher={info.cipher} kdf={info.kdf}")
    print(
        f"[verify]   t={info.iterations} m={info.memory_kib}KiB p={info.parallelism} "
        f"argon2Version={info.argon2_version}"
    )
    print(f"[verify]   salt={info.salt_hex}")
    if json_path is not None:
        payload = json.loads(json_path.read_text(encoding="utf-8"))
        assert_consistent_with_json(info, payload)
        print(f"[verify] 与伴生 JSON 逐字段一致：{json_path}")
    print("[verify] 通过（仅证明文件头自洽，不构成互操作证据——互操作证据由设备侧用例产出）")
    return EXIT_OK


def cmd_ingest(
    kdbx_path: Path,
    source_key: str,
    cli: str,
    entry_count: Optional[int],
    titles: Optional[Sequence[str]],
    force: bool,
) -> int:
    """复核外部（GUI）产出的 `.kdbx` 并落位两个目录：按**实测参数**推导规范文件名与伴生 JSON。"""
    if not kdbx_path.is_file():
        print(f"[ingest] 文件不存在：{kdbx_path}", file=sys.stderr)
        return EXIT_USAGE

    info = kdbx_header.parse_file(str(kdbx_path))
    expected_name = canonical_name(info, source_key)
    if kdbx_path.name != expected_name and not force:
        print(
            f"[ingest] 文件名与文件头不一致：实际 `{kdbx_path.name}`，按文件头应为 `{expected_name}`；"
            "README §6.1 规定「文件名就是声明」，故默认拒绝。确认无误可加 --force 强制按实测参数落位。",
            file=sys.stderr,
        )
        return EXIT_MISMATCH

    resolved_titles: List[str]
    resolved_count: int
    if titles:
        resolved_titles = list(titles)
        resolved_count = entry_count if entry_count is not None else len(resolved_titles)
    else:
        resolved_titles = list_entries(cli, kdbx_path)
        resolved_count = entry_count if entry_count is not None else len(resolved_titles)

    payload = build_companion_json(info, source_key, resolved_count, resolved_titles)
    written = place_corpus(kdbx_path, payload, expected_name, force)
    print(f"[ingest] 已落位 {len(written)} 个文件：")
    for path in written:
        print(f"[ingest]   {path}")
    print(
        "[ingest] 完成。注意：这**只**说明语料已就位；「互操作验证通过」必须由 "
        "`:database:connectedDebugAndroidTest` 中的 RealKdbxCorpusUnlockTest 真实跑出来。"
    )
    return EXIT_OK


def cmd_generate(
    cli: str,
    source_key: str,
    out_dir: Path,
    entry_count: Optional[int],
    titles: Optional[Sequence[str]],
    force: bool,
    dry_run: bool,
) -> int:
    """调用官方 CLI 建库并追加占位条目，然后走与 `--ingest` 相同的复核与落位。"""
    target_titles = list(titles) if titles else list(PLACEHOLDER_TITLES)
    out_dir.mkdir(parents=True, exist_ok=True)
    draft = out_dir / f"draft-{source_key}.kdbx"

    create_cmd = [cli, "db-create", "--set-password", str(draft)]
    add_cmds = [
        [cli, "db-add", "--username", PLACEHOLDER_USER, "--url", "", "-p",
         "--password-prompt", str(draft), title]
        for title in target_titles
    ]
    print("[generate] 将执行的命令序列（口令经 stdin，不落 argv）：")
    for command in [create_cmd, *add_cmds]:
        print(f"[generate]   {' '.join(command)}")
    if dry_run:
        print(f"[generate] --dry-run：实际将产出 {draft}，并按文件头实测参数落位两个目录。")
        return EXIT_OK

    if draft.exists() and not force:
        print(f"[generate] 草稿已存在，拒绝覆盖（如需覆盖请加 --force）：{draft}", file=sys.stderr)
        return EXIT_REFUSED

    rc, out, err = run_cli(cli, create_cmd[1:], [THROWAWAY_PASSPHRASE, THROWAWAY_PASSPHRASE])
    if rc != 0:
        print(f"[generate] db-create 失败（rc={rc}）：{err.strip() or out.strip()}", file=sys.stderr)
        return EXIT_ENV

    for command in add_cmds:
        rc, out, err = run_cli(cli, command[1:], [THROWAWAY_PASSPHRASE])
        if rc != 0:
            print(f"[generate] db-add 失败（rc={rc}）：{err.strip() or out.strip()}", file=sys.stderr)
            return EXIT_ENV

    return cmd_ingest(draft, source_key, cli, entry_count, target_titles, force=True)


# ============================================================================
# 入口
# ============================================================================


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="generate_corpus.py",
        description="KeePasskey .kdbx 互操作语料生成/校验（ISSUE-P3-38）",
    )
    parser.add_argument("--cli", default="keepassxc-cli", help="keepassxc-cli 可执行文件路径")
    parser.add_argument("--check", action="store_true", help="仅做环境自检（不产出语料）")
    parser.add_argument("--verify", metavar="FILE.kdbx", help="只读核验文件头参数（可选配 --json）")
    parser.add_argument("--json", metavar="FILE.json", help="与 --verify 配合，逐字段核对伴生 JSON")
    parser.add_argument("--ingest", metavar="FILE.kdbx", help="复核外部（GUI）产出并落位两个目录")
    parser.add_argument("--generate", action="store_true", help="调用官方 CLI 建库后落位")
    parser.add_argument("--source", choices=sorted(SOURCE_LABELS), default="keepassxc", help="语料来源标签")
    parser.add_argument("--out-dir", default=str(Path(__file__).parent / "_drafts"), help="草稿输出目录")
    parser.add_argument("--entry-count", type=int, help="条目总数（缺省时尝试由 CLI 列出）")
    parser.add_argument("--titles", help="占位条目标题，逗号分隔")
    parser.add_argument("--force", action="store_true", help="允许覆盖既有文件 / 文件名与文件头不一致")
    parser.add_argument("--dry-run", action="store_true", help="只打印将执行的命令与落位路径")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    titles = [t.strip() for t in args.titles.split(",") if t.strip()] if args.titles else None

    try:
        if args.check:
            return cmd_check(args.cli)
        if args.dry_run and not args.generate and args.ingest is None and args.verify is None:
            print("[dry-run] 计划：")
            print(f"[dry-run]   --check  ：探测 {args.cli} 与两个落位目录")
            print("[dry-run]   落位目录：")
            for directory in PLACEMENT_DIRS:
                print(f"[dry-run]     {directory}")
            print("[dry-run]   规范文件名为 <kdf>-v<ver>-t<t>-m<m>-p<p>-<source>.kdbx（由文件头实测参数推导）")
            return EXIT_OK
        if args.verify:
            return cmd_verify(Path(args.verify), Path(args.json) if args.json else None)
        if args.ingest:
            return cmd_ingest(Path(args.ingest), args.source, args.cli, args.entry_count, titles, args.force)
        if args.generate:
            return cmd_generate(
                args.cli, args.source, Path(args.out_dir), args.entry_count, titles, args.force, args.dry_run
            )
        build_parser().print_help()
        return EXIT_USAGE
    except kdbx_header.KdbxHeaderError as exc:
        print(f"错误：KDBX 头部解析失败：{exc}", file=sys.stderr)
        return EXIT_MISMATCH
    except CorpusError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return exc.code


if __name__ == "__main__":
    sys.exit(main())
