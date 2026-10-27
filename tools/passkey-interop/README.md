# 通行密钥互操作验证

验证 KPEX 字段与 PKCS#8 私钥能被独立实现读取；本仓往返不替代互操作证据。

```bash
# 本仓写出，由 pykeepass、cryptography 和 KeePassXC CLI 验证
./gradlew :database:testDebugUnitTest --tests '*PasskeyInteropProbeTest*'
python tools/passkey-interop/verify_interop.py

# 外部实现生成的已入库样本由本仓读取并签名验签
./gradlew :database:testDebugUnitTest --tests '*PasskeyInteropExternalFixtureTest*'
```

依赖版本与安装命令以 `.github/workflows/build.yml` 为准，脚本会校验实际版本。退出码 0 表示判据通过，1 表示失败，2 表示缺少外部环境；环境缺失不能算通过。脚本的 `--selftest` 检查环境缺失的失败语义。

需要更新公开测试样本时运行 `make_external_fixture.py`，再重跑读取测试。运行日志与产物摘要放在构建产物或 CI 报告中，不另建永久证据流水。
