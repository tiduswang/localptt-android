# 贡献指南（Contributing）

感谢你关注 **小伙伴对讲机（LocalPTT）**！本项目欢迎 Issue 与 Pull Request。

## 开发环境

| 工具 | 版本 |
|---|---|
| Android Gradle Plugin | 8.3.2 |
| Gradle | 8.5（仓库已自带 `gradlew` 包装器，无需本地预装） |
| Kotlin | 1.9.24 |
| JDK | 17 |
| 编译 / 目标 SDK | 34（minSdk 24，Android 7.0+） |
| Android SDK | 需安装 `build-tools` 与 `platforms;android-34` |

## 本地构建

```bash
# 克隆后首次构建（wrapper 会自动下载 Gradle 8.5）
./gradlew assembleDebug        # Linux / macOS
gradlew.bat assembleDebug      # Windows

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 仓库根目录的 `local.properties` 不会被提交（已在 `.gitignore`），请在本机自行配置
> `sdk.dir=/path/to/Android/Sdk`，或在环境变量中设置 `ANDROID_HOME`。

## 代码规范

- 全仓 Kotlin，遵循官方 `kotlin.code.style=official`（已写入 `gradle.properties`）。
- 提交信息请用中文或英文清晰描述「改了什么 / 为什么」。
- 涉及网络、音频、UI 的改动请在真机（≥2 台）上自测，并在 PR 中说明测试机型与结果。

## 提交流程

1. Fork 本仓库并创建特性分支：`git checkout -b feat/your-feature`。
2. 提交改动：`git commit -m "feat: 一句话说明"`。
3. 推送分支并发起 Pull Request，描述改动与自测情况。

## 报告 Bug

请使用 Issue 模板，尽量包含：机型 / Android 版本、是否同一 Wi-Fi、复现步骤、
日志（`logcat` 过滤 `LocalPTT` 标签）。
