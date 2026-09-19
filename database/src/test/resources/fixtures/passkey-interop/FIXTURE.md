# 外部管理器口径通行密钥 fixture（读取半边）

> 由 `tools/passkey-interop/make_external_fixture.py` 生成，**请勿手工编辑**。
> 出处与用途见 `ISSUE-P2-210` 与 `AGENTS.md` 规则 8。

- 库文件：`pykeepass-kpex.kdbx`
- SHA-256：`c5110ba86dac59db5ec3af66fd92286635e69ca70be2eb526078f8935efa36bb`
- 口令：`passkey-ext-fixture-2026`
- 库名：ExternalManagerKpexFixture
- 生成器：`pykeepass` + `cryptography`（两者均非本仓实现）
- 关键约束：条目**只有** `KPEX_PASSKEY_*`，不含任何 `Passkey.*` 扩展键
  ⇒ 强制本仓走「按 PKCS#8 AlgorithmIdentifier 的 OID 纯字节嗅探」的外部条目路径

## 期望值（供 `PasskeyInteropExternalFixtureTest` 断言）

| 条目 | 算法 | RP ID | 用户名 | Credential ID | User Handle | 公钥(hex) | PRF |
|---|---|---|---|---|---|---|---|
| `External ES256 Passkey` | ES256 (-7) | `passkey-interop.example` | `ext-es256@passkey-interop.example` | `ZXh0LWtleS1lczI1Ni1jcmVkZW50aWFsLWlk` | `ZXh0LXVzZXItaGFuZGxlLWVzMjU2` | `04515c3d6eb9e396b904d3feca7f54fdcd0cc1e997bf375dca515ad0a6c3b4035f4536be3a50f318fbf9a5475902a221502bef0d57e08c53b2cc0a56f17d9f9354` | 有（32 字节） |
| `External Ed25519 Passkey` | Ed25519 (-8) | `passkey-interop.example` | `ext-ed25519@passkey-interop.example` | `ZXh0LWtleS1lZDI1NTE5LWNyZWRlbnRpYWwtaWQ` | `ZXh0LXVzZXItaGFuZGxlLWVkMjU1MTk` | `d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a` | 无 |

## 固定测试向量（公开，非真实凭据）

- ES256 私钥标量：`0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20`
- Ed25519 私钥种子：`9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60`（RFC 8032 §7.1 TEST 1）
- PRF 秘密：`000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f`（0x00..0x1f）

## 重新生成

```bash
python tools/passkey-interop/make_external_fixture.py
```
