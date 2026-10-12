# 本机 Android 与 Windows 接入

本次产物：`dist/Agent-Control-0.5.19.apk`，Android 8.0 及以上。该修复版延用上一版本机签名，可覆盖本次提供的 0.5.17 / 0.5.18 并保留手机配置。若手机已装其他签名的同包名应用，需要先备份手机应用内配置，再自行卸载旧版后安装。本地签名材料位于 `android-app/.signing`，不提交或分享。

0.5.19 修复 Windows 文件上传、下载和下载列表的 SFTP 盘符路径：使用 `/C:/...`，避免 JSch 把 `C:/...` 拼接在远程用户目录后造成 `No such file`。持久任务服务优先查找进程、用户和系统的 JAVA_HOME，再查 PATH；显式 Java 路径不存在时给出具体提示。

0.5.18 修复 Windows aapt2 将图标和许可证等嵌套资源写成反斜杠 ZIP 路径、导致手机 WebView 图标全部加载失败的问题。签名前统一 APK 资源分隔符，并在签名后检查所有资源路径及逐文件 SHA-256。

## 已完成与验证范围

- 安装 JDK 17、Android SDK Platform 35、Build Tools 35.0.0，并校验下载文件摘要。
- 安装浏览器 worker 的 Node 依赖并通过 TypeScript 检查。可选 Chromium 下载和 worker 启动没有执行。
- APK 完成 v2/v3 签名验证、zipalign 验证；Windows 桥接逻辑、Java 交互与会话测试、指定 Node 回归测试通过。
- 原项目包含 WorkBuddy、豆包 Windows 桌面适配。本机 WorkBuddy 当前没有暴露聊天控件，尚未验证实际收发。
- 新增 TeleAgent 2.7.0 Windows 控件配置，依据本机只读控件树识别新任务、输入框、运行、完成标题和复制工具栏。回复边界、时间戳排除、生成中拒绝完成等测试通过。真实点击遇到 `GetCursorPos failed: 拒绝访问 (0x80070005)`，未发送模型测试消息，因此该适配仍需端到端验收。
- 修复 Windows 请求未传递手机 conversationId 的问题，保留同一手机对话的电脑会话绑定。
- ChatGPT 桌面应用未接入，不能宣称可控制。已发现的 `ChatGPT.exe` 属于 OpenAI Codex 安装包，不应当识别为独立 ChatGPT 应用。Codex CLI 是项目已有的另一条路径。
- 未连接 Android 实机，未验证手机到电脑的真实收发。2026-10-12 后续安装已完成：Windows 可选组件安装报错 `0x800f0950` 后，改用微软 Win32-OpenSSH 10.0.0.0p2-Preview；安装包 SHA-256 与微软签名均验证通过。sshd 已运行并设置自动启动，22 端口监听及本机 SSH 握手已验证，入站防火墙仅允许 `192.168.43.0/24`。SFTP 子系统已配置为服务安装目录下的绝对路径。

## 启用电脑端

SSH 已通过管理员提权完成安装。以下命令用于重新配置或安装其他电脑；在**管理员 Windows PowerShell**中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\dev\agent-control\windows\Setup-Laptop.ps1 -AllowedAddresses '192.168.43.0/24'
```

若系统可选组件安装失败，脚本会使用已准备好的微软 ZIP 包。也可以加 `-UseWin32OpenSsh` 直接使用该路径。备用安装器保留已有 sshd 服务，校验固定 SHA-256 与微软代码签名，并将服务文件安装到受保护的 `C:\Program Files\OpenSSH-AgentControl`。官方 ZIP 安装器会同时安装 ssh-agent，并更新系统 PATH。安装依据：[微软 Win32-OpenSSH 安装指南](https://github.com/PowerShell/Win32-OpenSSH/wiki/Install-Win32-OpenSSH)。

本机当前 WLAN 地址为 `192.168.43.5/24`。上面的网段仅用于手机也位于该可信局域网的情况；使用 VPN 或更换网络时改为实际可信来源地址。安装脚本会显示 SSH 主机指纹，请在手机连接时核对。不要在聊天中发送登录密码或私钥。

本机桥接已在普通权限的用户会话中启动，状态 `ready=true`、`busy=false`，已发现 WorkBuddy、豆包、TeleAgent。当前以隐藏窗口在后台运行，无需重复启动。重启或注销后，从**普通权限、已登录且已解锁的桌面**运行 `windows/Start-Bridge.cmd`，并保持桥接和桌面会话运行。不要在管理员会话启动桌面桥接，避免与普通权限应用不匹配。安装目录为 `%LOCALAPPDATA%\OrcaPocket\bin`。

本机 SSH 主机指纹（安装生成，2026-10-12）：

| 算法 | 指纹 |
|---|---|
| ED25519 | `SHA256:mASezh51bq/Z/fbsancvon4Le7CViwdT6D3Vx48Mojw` |
| ECDSA | `SHA256:IFxSniYyJMq9nbAkZHTXm7ui2Uc5Efsr1a/S/HQM0iM` |
| RSA | `SHA256:xpxvYyCfQV6e/uskj2nMIsUh5jzvW4643LvwweL1qLo` |

本次已将桥接文件安装到本机用户目录。也提供 `dist/Agent-Control-Windows-Bridge.zip`；解压后可在其目录运行同名脚本。

WorkBuddy/豆包需要 Electron 辅助功能支持。如果应用正在工作，先等任务完成并保存草稿，再由用户退出并使用以下命令重新启动：

```powershell
Start-Process -FilePath 'D:\应用\WorkBuddy\WorkBuddy.exe' -ArgumentList '--force-renderer-accessibility=complete','--enable-features=UiaProvider'
Start-Process -FilePath 'C:\Users\steam\AppData\Local\Doubao\Application\app\Doubao.exe' -ArgumentList '--force-renderer-accessibility=complete','--enable-features=UiaProvider'
```

不要关闭其他任务或清空应用数据来完成测试。

## 手机设置

1. 安装 APK，手机与电脑连接同一个可信局域网或可达 VPN。
2. 电脑地址：当前 `192.168.43.5`；端口：`22`；用户：`steam`；工作目录：`D:\dev\agent-control`。
3. 使用该 Windows 账号的已有 SSH 私钥或真实账号密码登录。Windows Hello PIN 不等于 SSH 密码。
4. 核对 SSH 主机指纹，接入方式选择“直接桌面”，连接后选择目标 Agent。
5. 分别发送无副作用的短回复测试，检查电脑收到完整消息、手机收到真实回复并显示完成。先分别测试各应用，再测试第二条消息继续同一电脑对话。

Windows 桌面适配目前只承诺其实现的文本协议；macOS 的图片、附件、确认卡片能力不能直接视为 Windows 已支持。遇到应用需要批准权限或回答问题时，先在电脑完成，并核对状态。

## 本机重新构建

```powershell
pwsh -NoProfile -File windows/Setup-BuildTools.ps1
pwsh -NoProfile -File windows/Build-Android.ps1 -Test
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android-app/tests/windows/Test-Logic.ps1
```

Windows 构建脚本使用 PowerShell 7。CoreTest 包含硬编码 `/bin/zsh` 探测，在 Windows 上未运行；`-Test` 运行可移植的 InteractionTest、DesktopContinuityTest。
