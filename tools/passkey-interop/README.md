# 通行密钥 KPEX 互操作对拍工具（`ISSUE-P2-210` / `ISSUE-P2-211`）

本目录是 `AGENTS.md` 规则 8「`.kdbx` 互操作证据纪律」在**通行密钥面**的落地：
`KPEX_PASSKEY_*` schema 的互操作性**只认官方实现端到端对拍**，自家 writer → 自家 reader 不构成证据。

## 两个方向

| 方向 | 承担者 | 判据 |
|---|---|---|
| **写入半边**：本仓产出 → 官方实现读取 | `PasskeyInteropProbeTest`（产物）+ `verify_interop.py`（判据） | `pykeepass` 与 `keepassxc-cli` **各自独立**读出全部 KPEX 键 / 保护位；`cryptography` 独立解析 PKCS#8 PEM 并由私钥重导出公钥，与产物公开公钥逐字节一致 |
| **读取半边**：官方实现产出 → 本仓读取 | `make_external_fixture.py`（产出 fixture）+ `PasskeyInteropExternalFixtureTest` | fixture 由 `pykeepass` + `cryptography` 写出、**不含**任何 `Passkey.*` 扩展键 ⇒ 强制走本仓「按 PKCS#8 OID 纯字节嗅探」的外部条目路径；该私钥经生产通道还原后能真正签名并被外部公钥验签 |

## 常用命令

```bash
# 写入半边：产出探针产物 → 跑权威对拍（退出码 1 即失败，可直接引为硬证据）
./gradlew.bat :database:testDebugUnitTest --tests "*PasskeyInteropProbeTest*"
python tools/passkey-interop/verify_interop.py

# 读取半边：重新生成外部 fixture（已入库；仅在需要换向量 / 换生成器逻辑时执行）
python tools/passkey-interop/make_external_fixture.py
./gradlew.bat :database:testDebugUnitTest --tests "*PasskeyInteropExternalFixtureTest*"

# 目视核对（两个独立实现的原始输出，留痕用）
echo -n 'passkey-interop-probe-2026' | keepassxc-cli show -q --all -s \
  database/build/interop-probe/keepasskey-passkey-probe.kdbx 'Passkey ES256 (passkey-interop.example)'
```

## 依赖（本机已实测可用）

`pykeepass >= 4.2`、`cryptography >= 41`、`keepassxc-cli`（KeePassXC 官方 CLI）。
任一缺失时脚本**显式失败**并说明缺什么，不做静默降级。

## 证据留痕

每次对拍的判据数、产物 SHA-256 与结论记入
[`docs/records/通行密钥互操作对拍记录_2026-09-19.md`](../../docs/records/通行密钥互操作对拍记录_2026-09-19.md)。

## 为什么必须把 `version` 钉在断言里（`ISSUE-P2-211`）

本工具首次运行时即暴露出一个**自家两侧都看不见**的缺陷：Ed25519 私钥被写成 RFC 5958
`OneAsymmetricKey`（`version = 1` + `publicKey [1]`），本仓解码器接受，但 **OpenSSL 系实现
（含 KeePassXC）拒收**。⇒ 取配的护栏是「产出必须能被**非本仓**实现解析」，而不是「本仓能读回」。
