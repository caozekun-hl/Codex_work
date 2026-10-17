# 本次验证记录

日期：2026-10-17

## 已执行

- 核心生产类和测试编译：通过。
- `sh tools/check-core.sh`：`PASS: 60 core checks (no network, no credentials)`。
- 全部 6 个生产 Java 源文件语法解析：通过。此项不包含 Android API 类型检查。
- Manifest、主题、矢量图标 XML 格式解析：通过。
- 示例配置 JSON 格式：通过。
- Gradle Wrapper 与测试专用 JSON jar：ZIP 完整性通过。
- 官方 Gradle Wrapper Git blob SHA-1 校验：`a4b76b9530d66f5e68d973ea569d8e19de379189`，匹配 Gradle v8.10.2。

## 云端构建成功

本地环境无法下载工具后，经用户授权将工程提交到公开仓库 `caozekun-hl/Codex_work` 的独立构建分支，使用 GitHub Actions 构建。主分支未修改。

- 源码提交：`f99f2b8fe7f0194fc685ca1bdbb59a3cabd52c33`。
- 构建记录：https://github.com/caozekun-hl/Codex_work/actions/runs/38993673561
- 使用 JDK 17、Gradle 8.10.2、Android SDK 35、Build Tools 35.0.0。
- `:app:assembleDebug :app:verifyCore`：`BUILD SUCCESSFUL`，60 项核心检查通过。
- `apksigner verify --verbose`：`Verifies`，APK Signature Scheme v2 验证通过。
- `aapt dump badging`：包名 `cn.juyan.ai`，版本 `1.0.0`，versionCode `1`，minSdk `26`，targetSdk `35`。
- 安装包：`JuYanAI-1.0.0.apk`，附 SHA-256 校验文件。
- 产物 ID：`12086286686`；ZIP 大小 51,245 字节。
- ZIP SHA-256：`2fd2f6c5c2e8f581134b0a961e1e79500916d9b1e6d2fb9a9f87876003238214`。

## 尚未执行

- 安卓模拟器或真机 UI、键盘、文件选择器和隐私屏幕检查。
- Android Keystore 密文读写及进程恢复验证。
- 真实 OpenAI、DeepSeek、GLM 或自定义服务商联调。

核心 HTTPS 测试使用内存模拟连接，没有用户密钥，也没有消耗 API 额度。
