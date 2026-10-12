# Windows Agent CLI 检查与接入

本机检查日期：2026-10-12；客户端版本：0.5.21。检测到六个 Agent，其中四个有对话 CLI，两个继续使用桌面适配。安装路径来自本机检查，程序通过 PATH 或同一用户桥接公布的应用路径发现入口，不把这些路径硬编码在客户端。

| Agent | 本机入口与版本 | 接入与实测 |
|---|---|---|
| WorkBuddy | 5.7.7；应用 `resources/app.asar.unpacked/cli/bin/codebuddy`，通过 Node 启动 | Windows 托管 CLI + ACP。使用桌面 WBIPC broker 处理账户认证；真实 CLI 已回复 `WINDOWS_CLI_OK` 并正常结束。桌面应用需要保持已登录、运行。 |
| DeepSeek Harness | 应用 `resources/runtime/cli/bin/dsh.cmd`，通过随应用 Electron 的 Node 模式运行 ASAR 内 CLI | ACP。显式 ACP 模型配置优先，否则复用桌面选择的 provider/model 和现有凭据服务。真实 CLI 已回复 `WINDOWS_CLI_OK` 并正常结束。需开启客户端“使用电脑现有权限”选项。 |
| Codex | 本机 OpenAI Codex `bin/<版本目录>/codex.exe`，0.162.0-alpha.17.2 | 已有 `exec` CLI 接入。只读测试实际收到 `CODEX_CLI_OK`；测试出现连接重试，随后 HTTPS 回退成功。 |
| Claude Code | npm 平台包 `@anthropic-ai/claude-code-win32-x64/claude.exe`，实际报告 2.1.190 | 已有 CLI 接入。修正 npm 启动器指向 Bun 占位程序的问题，优先平台包原生入口。帮助/版本检查通过；本机对话测试返回 API `ConnectionRefused`，尚未取得模型回复，需修复其现有服务地址或网络。 |
| TeleAgent | 桌面 2.7.0；另有 `~/.local/share/TeleAgent/runtimes/super-agent-code/bin/TeleAgent.exe` | 检查桌面包、运行时和命令参数。运行时是桌面启动的 Go 后端服务器，`--help`、`help`、`--version`、`run --help`、`acp` 均返回未知命令；未发现可独立进行对话的 CLI，继续桌面接入。 |
| 豆包 | `%LocalAppData%/Doubao/Application/app/Doubao.exe` | 检查安装目录和运行程序，未发现独立对话 CLI，继续桌面接入。 |

独立 ChatGPT 桌面应用未在本机检测到。Codex 安装包内的 `ChatGPT.exe` 不作为 ChatGPT 对话 CLI。

## Windows 托管运行和回复

手机通过 SFTP 上传 PowerShell 启动脚本，脚本生成临时 JS 文件再启动 CLI，避免 Windows 命令行长度限制。stdin/stdout 保留 UTF-8 ACP 双向协议；回复、权限确认、结束状态进入已有任务事件与手机消息流程。启动失败且没有创建聊天事件时，手机也显示失败原因，不再只留下用户消息。修正电脑持久任务脚本重复 UTF-8 BOM 导致检测提前失败的问题。

WorkBuddy 新适配连接其本地命名管道，验证双方 HMAC 身份后获取 HTTP 请求通道。桌面账户 token 留在 WorkBuddy 内；每次启动的 loopback 代理仅使用随机临时密钥，子进程凭据仅存内存。适配不会解密、复制桌面账户密码或 token，也不自动重复不确定的请求。ACP 权限确认继续由手机用户选择。

当前 WorkBuddy broker 会缓冲上游响应，手机可能等请求返回后才收到回复片段；请求和响应受该托管接口的 640KiB 限制。过大或超时的请求会失败，不自动重发。此适配基于本机已安装版本的托管协议；WorkBuddy 升级后需要重新验证。

TeleAgent、豆包只有桌面入口时，“仅 CLI”模式会提示更换接入方式并保留草稿，不再提交注定无法启动的任务。CLI 发现可以使用桥接缓存的安装路径；桌面接入仍要求实时桥接状态。

## 验证

- Windows 本地电脑服务：实际资源脚本检测、已有/缺失 BOM、Node 与 Electron GUI 程序的上传脚本双向 UTF-8 输入输出及子进程清理。
- 使用手机相同的 Java SSH/SFTP 传输实现，WorkBuddy 和 DeepSeek Harness 均实际返回 `WINDOWS_CLI_OK` 与 ACP `end_turn`。修复了 PowerShell 脱离 GUI 子系统 Electron 程序导致交互通道提前结束的问题。
- WorkBuddy Windows fixture：命名管道身份验证、拒绝未认证代理请求、gzip 请求解压、ACP 回复、手机权限返回及正常完成。
- DSH fixture：Windows Electron/ASAR 入口、模型选择优先级和临时配置清理。
- 手机聊天 UI：未产生聊天事件的失败任务显示原因且不重复；桌面 Agent 在仅 CLI 模式下不提交任务、保留草稿。
- APK：资源路径和逐文件 SHA-256、zipalign、v2/v3 签名校验。

实际 Android 手机未通过 adb 连接。电脑上的真实 SSH/SFTP 和 ACP 测试使用与手机相同的 Java 传输实现；手机安装后仍需验证真实设备的连接和显示。

官方入口背景：[WorkBuddy Windows 安装](https://www.workbuddy.cn/docs/workbuddy/From-Beginner-to-Expert-Guide/Installation-Win-Guide)、[CodeBuddy CLI](https://www.codebuddy.cn/docs/cli/quickstart)、[DeepSeek Harness CLI](https://github.com/deepseek-ai/deepseek-harness/blob/master/apps/cli/README.md)。
