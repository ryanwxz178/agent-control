# Agent Control

Android 聊天客户端，通过 SSH 连接电脑上的 AI Agent，支持确认交互、附件、桌面会话连续性及可选电脑持久任务服务。

当前源码版本：**0.5.19**（Android versionCode 45）。最低 Android 8.0 / API 26，目标 API 35。

## 项目结构

- `android-app/app/src/`：原生 Java、WebView 聊天界面、资源与 macOS/Windows 适配脚本。
- `desktop-service/`：Java 17 持久任务服务，以及可选 Node.js 浏览器 worker。
- `windows/`：Windows 用户会话桌面桥接、安装和诊断脚本。
- `android-app/tests/`、`desktop-service/tests/`：逻辑测试和需要自行配置环境的实机测试。
- `docs/`：架构、连接、确认附件、Windows 与任务服务说明。文档包含历史版本信息，当前行为以代码为准。

## 构建 Android

准备 JDK 17、Android SDK platform 35、build-tools 35.0.0，以及 bash、Python 3、curl、zip、openssl、shasum。

```bash
export JAVA_HOME=/absolute/path/to/jdk17
export ANDROID_HOME=/absolute/path/to/android-sdk
bash android-app/fetch-dependencies.sh
bash android-app/build.sh
```

产物：`dist/Agent-Control-0.5.19.apk`。依赖下载脚本校验 SHA-256；桌面服务 JAR 由构建脚本生成。无需 Gradle、Android Studio 或开发服务器。

Windows 本机构建（PowerShell 7）：

```powershell
pwsh -NoProfile -File windows/Setup-BuildTools.ps1
pwsh -NoProfile -File windows/Build-Android.ps1 -Test
```

本机接入步骤、支持范围与实机验证限制见 [本机 Windows 接入](docs/LOCAL-WINDOWS-SETUP.md)。

首次构建会生成本地签名密钥；该签名不能覆盖安装原开发者签名的版本。保持已有安装的升级需要使用原签名，密钥通过独立安全方式交接。

## 测试

```bash
bash android-app/tests/run.sh
(cd android-app && node --test tests/desktop-session.test.cjs tests/desktop-continuity.test.cjs tests/teleagent-confirmation.test.cjs)
```

UI 测试需要 Playwright 和 Chrome；实机、SSH、Windows 桌面测试需要自行配置设备、Agent 登录、授权和测试目录。部分历史实机脚本含原开发环境路径，运行前须替换。逻辑测试通过不等于物理手机收发验收。

## 可选组件

Windows 桌面自动化需要同用户已登录、解锁的会话桥接；SSH/CLI 接入不依赖该桥接。见 [Windows 指南](docs/WINDOWS.md)。

可选浏览器 worker（Node.js 22.6+；建议使用 Node.js 24）：

```bash
cd desktop-service/browser
npm ci
node setup.mjs
```

详见 [任务服务](docs/TASK-SERVICE.md)、[确认与附件](docs/RICH-INTERACTIONS.md)、[CLI 接入](docs/CLI-SUPPORT.md)。

## 源码范围

本仓库仅包含运行、构建、维护所需的代码、资源、测试、依赖锁文件与许可证。SDK/JDK、node_modules、下载 JAR、APK、生成文件、签名私钥、凭据、运行日志与测试输出均不提交。

第三方组件见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 与各组件附带许可证。本项目是独立实现，与官方 Orca 无隶属关系；Android 包名沿用 `dev.orca.pocket`。
