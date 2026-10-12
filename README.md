# Agent Control

Android 聊天客户端，通过 SSH 连接电脑上的 AI Agent，支持确认交互、附件、桌面会话连续性及可选电脑持久任务服务。

当前源码版本：**0.5.25**（Android versionCode 51）。最低 Android 8.0 / API 26，目标 API 35。

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

产物：`dist/Agent-Control-0.5.25.apk`。依赖下载脚本校验 SHA-256；桌面服务 JAR 由构建脚本生成。无需 Gradle、Android Studio 或开发服务器。

Windows 本机构建（PowerShell 7）：

```powershell
pwsh -NoProfile -File windows/Setup-BuildTools.ps1
pwsh -NoProfile -File windows/Build-Android.ps1 -Test
```

本机接入步骤、支持范围与实机验证限制见 [本机 Windows 接入](docs/LOCAL-WINDOWS-SETUP.md)。

移动端、SSH、电脑持久任务服务及不同 Agent 的通信分支见 [移动端与电脑 Agent 通信架构](docs/MOBILE-AGENT-ARCHITECTURE.md)。

首次构建会生成本地签名密钥；该签名不能覆盖安装原开发者签名的版本。保持已有安装的升级需要使用原签名，密钥通过独立安全方式交接。

## 测试

```bash
bash android-app/tests/run.sh
(cd android-app && node --test tests/desktop-session.test.cjs tests/desktop-continuity.test.cjs tests/teleagent-confirmation.test.cjs)
(cd android-app && node --test tests/teleagent-auth.test.cjs tests/teleagent-headless.test.cjs)
```

UI 测试需要 Playwright 和 Chrome；实机、SSH、Windows 桌面测试需要自行配置设备、Agent 登录、授权和测试目录。部分历史实机脚本含原开发环境路径，运行前须替换。逻辑测试通过不等于物理手机收发验收。

## 可选组件

### 手机上传文件对话

连接电脑后，点击输入区的回形针选择文件。选择后即通过 SSH 上传到当前电脑工作目录的 `.agent-control-input` 文件夹；上传完成后可补充文字发送，也可直接发送文件。每条消息最多 4 个文件，单个不超过 20MB，总大小不超过 40MB。发送前可移除附件，附件也会随待发送消息保存。

CLI 接入会将文件的电脑本地路径交给 Agent；默认自动调度的文件任务使用 CLI，不会自动转到桌面。选择具体 Agent 并设置「直接桌面」后，手机文件会通过桌面 APP 的附件入口添加，确认文件已就绪后才发送消息。后续文字消息沿用同一桌面对话，不重复添加历史文件。桌面文件消息需选择一个具体 Agent，不能使用多 Agent 自动调度。

Windows 目前依据 WorkBuddy 5.7.7 的附件菜单和文件标签实现适配；其他 APP 没有可识别的附件入口时会明确拒绝发送。Windows 用户需更新并重启电脑上的 Bridge；旧桥接不会接收带文件的消息。Windows 系统文件选择框已通过独立夹具验证，WorkBuddy 完整文件收发仍待实机验收。macOS 通过可识别的附件按钮、原生文件选择框及附件卡片校验添加文件，尚未在真实 macOS APP 验收，控件不匹配会停止并提示。实际格式、大小限制还取决于桌面 APP；附件操作失败后请检查电脑上的附件草稿，再决定是否重试。

安装新版 APK 后重新连接电脑；电脑服务若有正在执行的任务，需等任务结束后重连以完成更新。上传记录绑定电脑账号和工作目录，切换后需要重新上传。文件保留在电脑工作目录，移除手机草稿附件不会删除电脑上的文件。

UI 上传测试需在 `android-app` 目录运行 `node tests/input-files.test.cjs`，并配置 Playwright 和 Chrome。原生文件限制、跨电脑校验、Windows/macOS 桌面转发及后续对话测试纳入 `windows/Build-Android.ps1 -Test` 与 `android-app/tests/run.sh`。桌面附件模拟测试分别为 `node tests/desktop-files-mac.test.cjs` 和 `powershell.exe -NoProfile -File android-app/tests/windows/Test-Logic.ps1`（后者在仓库根目录运行）；不等同于真实 APP 文件收发验收。可选的 Windows 原生文件选择框夹具测试为 `powershell.exe -NoProfile -STA -File android-app/tests/windows/Test-FilePicker.ps1`，会短暂打开自己的测试窗口，不操作已安装的聊天 APP。

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
