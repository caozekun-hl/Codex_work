# 聚言 AI · 安卓个人版

原生 Android 多模型 AI 客户端，直接连接自己配置的 GPT、DeepSeek、GLM 等 OpenAI Chat Completions 兼容接口。仅个人使用，无需账号或自建服务器。

**交付状态：APK 1.0.0 已在 GitHub Actions 构建成功，Android SDK 编译、资源打包、APK v2 签名验证和 60 项核心检查均通过。支持 Android 8.0 及以上。尚未进行真机、Keystore 或真实服务商联调。**

## 下载安装包

[下载 JuYanAI-personal-debug 安装包产物](https://github.com/caozekun-hl/Codex_work/actions/runs/38993673561/artifacts/12086286686)（约 50 KB，需登录 GitHub）。

下载后解压 ZIP，点击其中的 `JuYanAI-1.0.0.apk` 安装。压缩包还包含 SHA-256 校验文件。首次安装时允许文件管理器安装此应用。源码 ZIP 用于开发，不能直接安装。

[查看成功构建记录](https://github.com/caozekun-hl/Codex_work/actions/runs/38993673561)。该构建产物计划保留到 2027-01-15，过期后需重新构建。

## 功能

- 中文原生界面，支持 Android 8.0 及以上。
- OpenAI、DeepSeek、智谱 GLM 模板及自定义 HTTPS 接口；手动配置或 JSON 批量导入。
- 每个接口支持多个模型，可在对话中切换。
- 流式文本回复、推理过程显示、停止、重新生成、历史对话、文本导出。
- 自定义系统提示词；每次携带最近 20 条完整上下文消息。
- 密钥和聊天记录通过 Android Keystore 的 AES-256-GCM 加密保存，不使用远程数据库。
- 配置导出不包含 API Key；可选隐私屏幕及本地数据清理。

## 生成 APK

### Android Studio

1. 解压工程，在 Android Studio 中打开本目录。
2. 使用 JDK 17 或兼容的 JDK 21；安装 Android SDK Platform 35、Build Tools 35.0.0。
3. 等待 Gradle 同步。首次需要联网下载 Gradle 8.10.2、Android Gradle Plugin 8.7.3 和构建依赖。
4. 通过 **Build → Build App Bundle(s) / APK(s) → Build APK(s)** 生成调试安装包。
5. 输出：`app/build/outputs/apk/debug/app-debug.apk`。传到手机，允许文件管理器安装此应用。

命令行等价操作（先将 `ANDROID_HOME` 指向本机 SDK，或在 `local.properties` 中填写 `sdk.dir`）：

```sh
sh gradlew :app:assembleDebug :app:verifyCore
```

Windows：`gradlew.bat :app:assembleDebug :app:verifyCore`。

个人安装可使用调试 APK，但换电脑或再次云构建时调试签名可能变化，导致不能覆盖安装。长期使用建议通过 Android Studio 的 **Generate Signed Bundle / APK** 创建自己的签名密钥并妥善保管，后续使用同一密钥签名。卸载旧版本会删除本机数据。

### GitHub Actions

工程含 `.github/workflows/build-android.yml`。将解压后的文件作为 GitHub 仓库内容提交后，打开 **Actions → Build Android APK → Run workflow**。成功后下载 `JuYanAI-personal-debug` 产物，解压得到 APK。

该构建无需个人 API Key。不要把密钥提交到仓库。当前工程已在 `apk-build/juyan-personal-20261017` 分支完成云端构建，主分支保留原状。后续向 `apk-build/` 开头的分支推送也会触发构建。

## 手机上使用

1. 打开「接口」，选择服务商模板，填写自己的 API Key。
2. 检查 Base URL 和模型 ID，点击「测试连接」，再保存。
3. 回到「对话」，点顶部模型名称选择模型，输入问题并发送。
4. 点击停止图标可中断生成；AI 回复下方可复制或重新生成。
5. 右上角时钟查看历史，`＋` 新建对话，`···` 导出、改名或删除当前对话。

地址可填 Base URL 或完整 `/chat/completions` 地址，应用会自动补齐路径。仅支持 HTTPS，不接受 URL 中的账号、密码、查询参数或片段，也不跟随接口重定向。

模板模型是可编辑示例，实际以服务商当前文档、账户权限和余额为准。产品订阅会员通常与 API 账户额度分开。连接测试会发送简短请求，可能产生少量 API 费用。

## JSON 导入

使用 `examples/providers.json` 模板，通过「接口 → 导入 JSON」添加。空密钥允许导入和保存，聊天前需补填。最多 30 个接口，每个接口 1–30 个模型；导入是新增，不覆盖现有配置。

```json
{
  "version": 1,
  "providers": [
    {
      "name": "我的 DeepSeek",
      "baseUrl": "https://api.deepseek.com",
      "apiKey": "",
      "models": ["deepseek-chat", "deepseek-reasoner"]
    }
  ]
}
```

文件上限 256 KB，导入前显示地址和模型数量供确认。文件允许包含 `apiKey`，导入后在本机加密；原始 JSON 仍是明文，请妥善处理。配置导出始终不包含密钥，不能用于恢复密钥。

## 数据与行为

- 没有聚言中转服务器，请求发送到用户配置的服务商；添加前确认地址可信。
- 切换模型沿用当前对话上下文；需要独立上下文请新建对话。
- 每次发送最近 20 条完整消息与当前系统提示词；失败、中断的 AI 回复不进入后续上下文。
- 最多保留 100 个对话，每个最多 200 条消息，达到上限提示整理，不会自动删除。
- 密文保存在应用私有 `no_backup` 目录，关闭系统备份。卸载或清除应用数据会删除密钥与历史，当前没有完整备份恢复功能。
- 对话导出含聊天和思考过程；剪贴板与纯文本文件中的副本由用户管理。
- 没有后台服务，退出或进程被结束可能中断生成，已保存的部分回复标为中断。
- 本版支持文本对话；不含语音、图片、联网搜索、工具调用、附件或厂商专有协议。

## 验证

核心离线检查只需 JDK 17+，无需 Android SDK 或网络：

```sh
sh tools/check-core.sh
```

脚本直接编译运行生产类 `AppState`、`AiProtocol`、`AiClient`、`ConfigImport` 和 `CoreChecks`。`tools/lib/json-20240303.jar` 由 JSON-java 20240303 的核心源码编译，仅用于测试，**不打入安卓应用**。安卓使用系统 `org.json`；Gradle 检查从 Maven 获取同版本 JSON-java。

60 项检查覆盖地址验证、上下文筛选、SSE 中文碎片与多行事件、连接中断、JSON 回退、认证错误、重定向保护、导入校验、无密钥导出、数据恢复与取消。传输测试使用内存模拟 HTTPS，不使用真实密钥。

已完成全部生产 Java 文件的 Android SDK 编译、XML 资源链接、DEX 打包和 APK 签名验证。仍需真机验证键盘适配、文件选择器、Keystore 加密、后台中断及各服务商真实接口。

## 代码

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 原生界面、对话和配置操作 |
| `AiClient.java` | HTTPS、流式解析、取消与错误提示 |
| `AiProtocol.java` | 地址验证、上下文组装、SSE 分帧 |
| `SecureStore.java` | Keystore、AES-GCM、原子密文保存 |
| `AppState.java` | 接口、对话、消息数据模型 |
| `ConfigImport.java` | JSON 导入校验 |
| `CoreChecks.java` | 核心回归检查 |
