# CLI 接入核对（0.5.22，2026-10-12）

手机始终使用聊天界面；CLI 通过 SSH 在电脑后台运行，不需要手机输入终端命令。

| Agent | CLI 结论 | 手机接入与当前实测 |
|---|---|---|
| DeepSeek Harness | Windows、macOS 已接入 `dsh acp` | 独立 PATH 中的 dsh 优先；内置 CLI 沿用桌面模型与现有凭据。0.5.21 Windows 使用与手机相同的 Java SSH/SFTP 实现取得真实回复与 `end_turn`；macOS 已有 APK → SSH → ACP 实测 |
| WorkBuddy | Windows、macOS 内置 CLI 已接入桌面托管启动与 ACP | Windows 0.5.21 已整合 WBIPC broker 和启动脚本，Java SSH/SFTP 真实回复通过；macOS 已有生产 PocketEngine → SSH 实测。需要同账号 WorkBuddy 桌面保持登录、运行；独立 codebuddy/cbc 另列为 CodeBuddy |
| TeleAgent | Windows 已接入本项目的 HTTP 后台 CLI 包装，真实本地与生产 SSH 对话通过 | 0.5.22 自建独立后台，通过标准输入输出回传回复，不使用 GUI；原生后台不提供 ACP。PocketEngine → JSch SSH/SFTP 真实回复通过，权限与问答已有离线回归，Android 实机尚未验收；macOS 保留桌面接入 |
| 豆包 | 未找到并验证官方桌面 CLI | 保留桌面聊天、确认、图片和文件操作。豆包模型 API 不等同于控制豆包桌面账号和会话 |

本项目使用 ACP 接入 DSH 与 WorkBuddy。TeleAgent 的 Windows 接入是本项目包装的标准输入输出协议，内部调用其 HTTP 后台；不是原生 `teleagent chat` 命令或 ACP。当前 Windows 0.5.21 基线详见 [Windows CLI 检查记录](AGENT-CLI-AUDIT.md)，TeleAgent 新验证见下节。

## 使用

在 Agent 面板选择默认（CLI 优先，确认未执行任务的启动失败才转桌面）、直接桌面、或新增的仅 CLI。仅 CLI 不会回退，未检测到 CLI 的豆包会在发送前明确报错；自动调度仅 CLI 时也会排除桌面应用。

DSH CLI 需要在电脑按所用 DSH profile 配置模型凭据，并打开手机设置中的“DeepSeek 使用电脑已有权限”。Windows、macOS WorkBuddy 内置 CLI 通过同账号已运行的桌面托管入口处理认证，手机通过 SSH 标准输入输出收发 ACP。仅完成网页登录不能使绕过托管入口的直接启动方式可用。

Windows TeleAgent 需要**当前 SSH 登录的 Windows 用户已经登录 TeleAgent**，且仍有有效的当前账号登录状态。它读取该用户受 Windows 保护的登录状态，解密结果仅用于初始化自建后台，不输出或保存明文。桌面窗口不参与收发消息，也不必为了这条路径保持打开；注销、切换账号、安装代次变化或登录状态失效时会停止，不自动重新提交任务。手机在 Windows Agent 列表选择 TeleAgent 的“仅 CLI”或默认 CLI 优先模式；macOS TeleAgent 仍选择桌面模式。

## TeleAgent Windows HTTP 后台 CLI（0.5.22）

本机验证的桌面版本为 **2.7.0**，Go 后台版本为 **1.2.27**。原生程序无参数时启动后台服务，只有 `export` 导出子命令；`server`、`serve` 探测均返回 `unknown command`，没有独立 `run`、`chat` 或 ACP 对话入口。本项目的 `teleagent-headless-cli.js` 无参数启动一个自建后台，再通过 HTTP 创建会话、提交消息、读取回复以及处理权限与问答。

每次运行使用新的临时目录、数据库、随机端口和会话，账号数据只用于读取受保护登录与模型目录。不会接管桌面后台、修改原有会话或点击桌面聊天窗口。消息 ID 由后台生成，随后绑定到此次提交的用户消息；回复按会话 ID 和父消息 ID 过滤。取消、EOF 或 SSH 断开时只停止自建会话与子进程，退出后清理临时数据。请求提交后状态未知时不会自动重发。

桥接发出的全部 HTTP 请求都访问 `127.0.0.1`。后台程序本身监听 wildcard 地址，不能把它描述为仅绑定 loopback；会话和消息等业务接口强制使用本次运行随机生成的 HMAC-SHA256 签名及 Basic 认证，密钥和密码只在进程内存及子进程 stdin 初始化载荷中传递。`/ready` 是公开就绪检查，不承载会话内容。手机只经 SSH 接入包装进程，不直接连接后台端口。

读取权限默认允许，未开启手机“允许写入”时拒绝写入与命令；开启后，写入和 PowerShell 命令通过手机确认返回一次允许或拒绝。Bash 和工作目录外操作仍拒绝。问答支持选项、多选、自由文本和拒绝回答。协议与手机返回值已有离线测试，真实模型权限交互及实体 Android 的显示、点击仍需验收。临时后台不继承桌面会话历史、自定义 Agent、技能库或全局记忆；本轮验收的是文本对话，图片与文件协议尚未完成端到端验证。

2026-10-12 已通过本机真实模型计算 **47 × 89 = 4183**，CLI 输出 `teleagent.snapshot` 与 `teleagent.done`，退出码 0；原有桌面任务未被操作。[事件 fixture](../android-app/tests/teleagent-headless-events.json) 只保留 assistant 测试文本，并使用脱敏会话 ID。

同日生产 **PocketEngine → JSch SSH/SFTP → NDJSON 包装 → 自建 HTTP 后台** 已取得 **53 × 97 = 5141** 和随机测试标记。测试使用仅 CLI，`desktopFallback=false`、`protocol=teleagent-http`；Java 测试退出码 0，任务完成后 engine busy 已释放。证据见 [SSH 验证记录](../android-app/test-output/teleagent-cli-ssh-verified.json)。这项验证使用生产 SSH 代码，实体 Android 尚未连接验收。

## 历史整合说明（0.3.6–0.4.2，2026-10-09）

0.3.6 已验证 DSH 内置 CLI 使用桌面 deepseek-account / deepseek-flash 完成模型回复，不新增或复制凭据。0.4.1 整合包包含此前 0.3.7 WorkBuddy managed 预览包的 macOS 托管 CLI 适配；生产后台经 SSH 的真实回复已通过，当时尚未重新验收此托管路径的 Android 原生界面、托管 CLI 图片和权限交互。已有协议确认和附件回归测试通过，真实桌面回退测试见 RICH-INTERACTIONS.md。当时 Windows 只验证了托管 CLI 原型；该限制已在 0.5.21 的 Windows 启动与 Java SSH/SFTP 实测中更新。

## 验证

- 本地已安装的 DSH 与 WorkBuddy CLI：ACP initialize、session/new、真实 session/prompt；结果见 android-app/test-output/cli-031-live.txt。
- shell 检测：PATH 独立 DSH 不被内置版本覆盖；无独立 DSH 时内置版本可发现。
- Java 回归：协议交互、严格启动回退、CLI-only 选择与拒绝桌面应用。
- 手机 UI 回归：仅 CLI 选择保留并随聊天请求传给后台。
- Windows 0.5.21：WorkBuddy、DSH 均通过与手机相同的 Java SSH/SFTP 传输取得 `WINDOWS_CLI_OK` 和 ACP `end_turn`；详见 [检查记录](AGENT-CLI-AUDIT.md)。
- TeleAgent 0.5.22：真实本地后台 CLI 文本回复、退出清理通过；`node android-app/tests/teleagent-headless.test.cjs` 的 18 项离线 HTTP 与 CLI 生命周期回归通过，包括会话隔离、工具中间轮、UTF-8、权限问答、重复回复、认证失效、EOF、取消竞争和 Windows 子进程清理。
- TeleAgent 生产 PocketEngine → JSch SSH/SFTP 真实文本对话通过，无桌面回退，退出码 0，engine busy 释放；实体 Android 尚未连接验收。

## DSH 桌面账号与 CLI 模型配置

旧版桌面 profile 选 deepseek-account，而 ACP 默认选 deepseek-official，导致错误要求 API Key。macOS 内置 dsh 的手机启动器从 desktop/cordis.patch.yml 读取 provider/model，使用公开 --patch 为 ACP 临时设置相同模型；凭据仍由 DSH 自身服务读取，退出删除临时补丁。显式 ACP profile 的模型设置优先；独立 PATH CLI 不被覆盖。0.5.21 已整合 Windows Electron/ASAR 启动与相同模型选择规则，并取得 Java SSH/SFTP 真实模型回复。

## WorkBuddy 内置 CLI 认证入口与历史验证

当前 macOS WorkBuddy 包只包含 headless / lite-wb bundle，没有 dist/codebuddy 终端界面。直接执行内置 bin/codebuddy 会报 MODULE_NOT_FOUND；不能照搬独立 CodeBuddy 的交互 /login 步骤。非交互的 --help、--print、--acp 可以启动。

使用同一 SSH 账号运行本项目的 ACP 登录助手：

```bash
python3 tools/workbuddy-cli-login.py --reauth
```

助手使用内置 CLI 的 initialize、authenticate 和官方 authUrl 通知打开浏览器；账号认证由用户在官方网页完成。它不读取或复制桌面凭据，不记录认证 URL 或账号信息。--reauth 会重新认证已有但失效的 CLI 账号记录；未加该参数时，已有记录会停止自动登录，但该记录不代表凭据有效。

2026-10-09 已实测官方网页登录和 ACP authenticate 返回账号确认。但重新启动内置 CLI 后，纯 CLI 模型请求仍为 Authentication required；独立进程日志显示 auth/workbuddy-desktop.info 的 credential-fields 读取缺少密钥（missing-key）。因此账号认证完成不等于独立 CLI 已可用，不能宣称一次 /login 即可解决。该安装包使用桌面管理的凭据保护，纯 CLI 还需要匹配的受管启动/凭据初始化，不能通过禁用加密或复制桌面密钥解决。

2026-10-09 还观察到重新认证时，注销旧文件的延迟监听事件会取消刚发起的登录（CanceledError）；助手先等待初始化稳定，并在已有失效记录时处理这个状态过渡。登录助手不依赖缺失的 TUI，不修改 WorkBuddy 安装包。当时这条独立认证路径尚未取得模型回复；随后验证成功的是下述桌面托管路径。

后续已通过桌面 sidecar v6 的 session.create 创建独立 CLI，port=0 由受管子进程报告本机随机端口，并由桌面向子进程提供凭据 bootstrap。新增 workbuddy-acp-launch.js 将手机 ACP 标准输入输出代理到这个仅监听 127.0.0.1 的 HTTP 服务；每个进程启用临时随机网关密码，仅保存在进程内，不改全局设置，不读取桌面密钥。原有桌面会话不被接管，退出或 SSH 断开时仅按自建 sessionId / runtimeId 清理自己的子进程。SSH 缺少 TMPDIR 时通过系统 getconf 查找同用户桌面运行目录。

实测证据：

- android-app/test-output/workbuddy-managed-cli-probe.json：桌面托管子进程真实计算 137 × 29 = 3973，end_turn。
- android-app/test-output/workbuddy-managed-stdio-verified.json：适配器标准输入输出真实计算 248 × 37 = 9176，end_turn；无桌面回退，退出码 0。
- android-app/test-output/workbuddy-managed-ssh-verified.json：生产 PocketEngine → JSch SSH → 适配器 → 托管 CLI，真实计算 326 × 41 = 13366，仅 CLI 模式完成。
- android-app/test-output/workbuddy-managed-cleanup.txt：SIGTERM 后自建 CLI 被清理，原有桌面会话保留。
- android-app/tests/WorkBuddyManagedLiveTest.java 为显式运行的真实集成测试，不由常规离线测试自动调用。
- android-app/test-output/workbuddy-managed-windows-probe.txt：历史 Windows 原型记录，桌面 sidecar v6 → 独立内置 CLI → ACP HTTP，真实计算 157 × 43 = 6751，end_turn；测试前后桌面托管会话数均为 0。该记录本身没有验收 Android 链路；0.5.21 的 WBIPC 托管接入与 Java SSH/SFTP 真实测试见 [Windows 检查记录](AGENT-CLI-AUDIT.md)。

macOS 托管入口属于桌面内部接口，适配器明确要求 v6；应用更新后的接口变化需要重新验证。Windows 0.5.21 已单独适配 WBIPC 命名管道、运行时发现和 SSH 启动，并使用桌面账户认证。两端都是桌面管理的无界面 CLI，WorkBuddy 桌面仍需保持登录、运行。

## TeleAgent historical installation detection (0.3.7, 2026-10-09)

At that time, macOS detection included `/Applications/TeleAgent.app` and `~/Applications/TeleAgent.app`. The observed installed version was 2.7.0-rc.2, bundle identifier `com.teleai.superagent`. Its bundled `super-agent-code` package described a Go backend server; this did not establish ACP or a supported CLI chat interface. The 0.3.7 inventory listed TeleAgent as installed with chat unavailable, disabled selection, and excluded it from automatic routing. This historical release did not claim TeleAgent conversation support; macOS desktop support was added later, and the Windows HTTP wrapper is new in 0.5.22.

### TeleAgent 2.7.0-rc.2 capability probe (2026-10-09)

The installed runtime is `~/.local/share/TeleAgent/runtimes/super-agent-code/bin/TeleAgent`. Read-only, time-bounded probes of `--help`, `help`, `run --help`, `acp --help`, `serve --help`, `server --help`, and `--version` each exited 1 with `unknown command`. No supported CLI conversation entry was found. The Electron main runtime contains no `session/new`, `session/prompt`, or `Agent Client Protocol` implementation; the Go runtime strings also lack those ACP markers. This is evidence for the installed build, not proof about all TeleAgent releases.

The desktop's bundled SDK contained HTTP session creation, message submission, and reply retrieval (`POST /session`, `POST /session/{id}/message`, `GET /session/{id}/message`), and the desktop launched its backend with account-specific configuration and authentication. This was a candidate private HTTP integration, not ACP. Guessed `/global/health` and `/doc` routes on port 19876 returned 404; an authenticated API was not verified in that probe. No conversation was submitted, no credentials were exported, and no running task was interrupted. That historical inventory remained installed/unavailable pending adapter validation.

### TeleAgent historical local HTTP verification (2026-10-09)

The desktop-persisted IM bridge state confirms backend URL `http://127.0.0.1:4397`. GET `/ready` returned 200 with ready status; GET `/health` returned 200 with ok status. GET `/session` and `/event` each returned 401 `local_auth_missing`. The earlier port 19876 probe was not the session backend.

Desktop code signed local requests using `X-SA-Sign-Version: local-v1`, timestamp, nonce, and HMAC-SHA256 signature, with a random per-process session key. It also generated a random Basic credential in main-process memory. The backend received bootstrap configuration through stdin, not an ordinary reusable on-disk credential file. SSH alone did not supply this application authentication. That check performed GETs only and did not verify an authenticated conversation round trip. The Windows 0.5.22 result above uses a separately initialized backend with the current Windows user's protected login; it does not extract the running desktop backend's random session key.
