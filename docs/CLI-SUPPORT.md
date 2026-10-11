# CLI 接入核对（0.4.2）

手机始终使用聊天界面；CLI 通过 SSH 在电脑后台运行，不需要手机输入终端命令。

| Agent | CLI 结论 | 手机接入与当前实测 |
|---|---|---|
| DeepSeek Harness | 支持，已接入 `dsh acp` | 独立 PATH 中的 dsh 优先，找不到时使用 macOS 桌面包内 dsh；macOS 内置 CLI 现在沿用桌面模型；已实测桌面账号模型经 APK → SSH → ACP 完成对话 |
| WorkBuddy | macOS 内置 CLI 已接入桌面托管启动及 ACP 标准输入输出桥接 | 真实模型回复和生产 PocketEngine → SSH 已通过，仅 CLI 无桌面点击回退；需要同账号 WorkBuddy 桌面运行。Windows 云电脑原型已返回真实模型回复，手机端 Windows 托管启动尚未接入。独立 codebuddy/cbc 另列为 CodeBuddy |
| TeleAgent | 暂无已验证 CLI | 0.4.2 新增 macOS 桌面接入，默认 / 桌面模式支持手机收发和完成读取；仅 CLI 明确拒绝 |
| 豆包 | 未找到并验证官方桌面 CLI | 保留桌面聊天、确认、图片和文件操作。豆包模型 API 不等同于控制豆包桌面账号和会话 |

Orca 源码的 DSH 启动识别与状态钩子证实其 CLI 支持；原项目能承载终端应用，不代表每个应用会自动提供结构化手机聊天协议。本项目使用真实 ACP 接入 DSH 与 WorkBuddy。

## 使用

在 Agent 面板选择默认（CLI 优先，确认未执行任务的启动失败才转桌面）、直接桌面、或新增的仅 CLI。仅 CLI 不会回退，未检测到 CLI 的豆包会在发送前明确报错；自动调度仅 CLI 时也会排除桌面应用。

DSH CLI 需要在电脑按所用 DSH profile 的要求配置模型凭据，并打开手机设置中的“DeepSeek 使用电脑已有权限”。macOS WorkBuddy 内置 CLI 通过同账号已运行的桌面 sidecar 创建独立子进程，凭据初始化仍由桌面完成；手机通过 SSH 标准输入输出收发 ACP，不复制或显示桌面凭据。仅完成网页登录不能使绕过托管入口的直接启动方式可用。请勿把 API Key 发到聊天中。

0.3.6 已验证 DSH 内置 CLI 使用桌面 deepseek-account / deepseek-flash 完成模型回复，不新增或复制凭据。0.4.1 整合包包含此前 0.3.7 WorkBuddy managed 预览包的 macOS 托管 CLI 适配；生产后台经 SSH 的真实回复已通过，尚未重新验收此托管路径的 Android 原生界面、托管 CLI 图片和权限交互。已有协议确认和附件回归测试通过，真实桌面回退测试见 RICH-INTERACTIONS.md。本轮另用 Windows 原型验证桌面托管内置 CLI 返回真实模型回复，但未接入手机 Windows 启动链路。

## 验证

- 本地已安装的 DSH 与 WorkBuddy CLI：ACP initialize、session/new、真实 session/prompt；结果见 android-app/test-output/cli-031-live.txt。
- shell 检测：PATH 独立 DSH 不被内置版本覆盖；无独立 DSH 时内置版本可发现。
- Java 回归：协议交互、严格启动回退、CLI-only 选择与拒绝桌面应用。
- 手机 UI 回归：仅 CLI 选择保留并随聊天请求传给后台。

## DSH 桌面账号与 CLI 模型配置

桌面 profile 当前选 deepseek-account，而 ACP 默认选 deepseek-official，导致旧版错误要求 API Key。macOS 内置 dsh 的手机启动器从 desktop/cordis.patch.yml 读取 provider/model，使用公开 --patch 为 ACP 临时设置相同模型；凭据仍由 DSH 自身服务读取，退出删除临时补丁。显式 ACP profile 的模型设置优先；独立 PATH CLI 不被覆盖。Windows 该模型继承路径尚未实现或实测。

## WorkBuddy 内置 CLI 认证入口

当前 macOS WorkBuddy 包只包含 headless / lite-wb bundle，没有 dist/codebuddy 终端界面。直接执行内置 bin/codebuddy 会报 MODULE_NOT_FOUND；不能照搬独立 CodeBuddy 的交互 /login 步骤。非交互的 --help、--print、--acp 可以启动。

使用同一 SSH 账号运行本项目的 ACP 登录助手：

```bash
python3 tools/workbuddy-cli-login.py --reauth
```

助手使用内置 CLI 的 initialize、authenticate 和官方 authUrl 通知打开浏览器；账号认证由用户在官方网页完成。它不读取或复制桌面凭据，不记录认证 URL 或账号信息。--reauth 会重新认证已有但失效的 CLI 账号记录；未加该参数时，已有记录会停止自动登录，但该记录不代表凭据有效。

2026-10-09 已实测官方网页登录和 ACP authenticate 返回账号确认。但重新启动内置 CLI 后，纯 CLI 模型请求仍为 Authentication required；独立进程日志显示 auth/workbuddy-desktop.info 的 credential-fields 读取缺少密钥（missing-key）。因此账号认证完成不等于独立 CLI 已可用，不能宣称一次 /login 即可解决。该安装包使用桌面管理的凭据保护，纯 CLI 还需要匹配的受管启动/凭据初始化，不能通过禁用加密或复制桌面密钥解决。

还观察到重新认证时，注销旧文件的延迟监听事件会取消刚发起的登录（CanceledError）；助手先等待初始化稳定，并在已有失效记录时处理这个状态过渡。登录助手不依赖缺失的 TUI，不修改 WorkBuddy 安装包。网页认证和真实模型回复分开判断，本轮后者尚未通过。

后续已通过桌面 sidecar v6 的 session.create 创建独立 CLI，port=0 由受管子进程报告本机随机端口，并由桌面向子进程提供凭据 bootstrap。新增 workbuddy-acp-launch.js 将手机 ACP 标准输入输出代理到这个仅监听 127.0.0.1 的 HTTP 服务；每个进程启用临时随机网关密码，仅保存在进程内，不改全局设置，不读取桌面密钥。原有桌面会话不被接管，退出或 SSH 断开时仅按自建 sessionId / runtimeId 清理自己的子进程。SSH 缺少 TMPDIR 时通过系统 getconf 查找同用户桌面运行目录。

实测证据：

- android-app/test-output/workbuddy-managed-cli-probe.json：桌面托管子进程真实计算 137 × 29 = 3973，end_turn。
- android-app/test-output/workbuddy-managed-stdio-verified.json：适配器标准输入输出真实计算 248 × 37 = 9176，end_turn；无桌面回退，退出码 0。
- android-app/test-output/workbuddy-managed-ssh-verified.json：生产 PocketEngine → JSch SSH → 适配器 → 托管 CLI，真实计算 326 × 41 = 13366，仅 CLI 模式完成。
- android-app/test-output/workbuddy-managed-cleanup.txt：SIGTERM 后自建 CLI 被清理，原有桌面会话保留。
- android-app/tests/WorkBuddyManagedLiveTest.java 为显式运行的真实集成测试，不由常规离线测试自动调用。
- android-app/test-output/workbuddy-managed-windows-probe.txt：天翼云 Windows 桌面 sidecar v6 → 独立内置 CLI → ACP HTTP，真实计算 157 × 43 = 6751，end_turn；测试前后桌面托管会话数均为 0。仅证明 Windows 原型，未证明 Android 到 Windows 托管 CLI 的完整链路。复现脚本 tools/workbuddy-managed-windows-probe.cjs 使用 Electron 的 ELECTRON_RUN_AS_NODE=1，无凭据提取。

该入口属于桌面内部接口，适配器明确要求 v6；应用更新后的接口变化需要重新验证。它是桌面管理的无界面 CLI，不是关闭桌面应用后仍可独立认证的 CLI。Windows 需要单独适配命名管道、运行时发现和 SSH 启动，不能沿用 macOS 的验收结论。

## TeleAgent installation detection (0.3.7)

macOS detection includes `/Applications/TeleAgent.app` and `~/Applications/TeleAgent.app`. The observed installed version is 2.7.0-rc.2, bundle identifier `com.teleai.superagent`. Its bundled `super-agent-code` package describes a Go backend server; this does not establish ACP or a supported CLI chat interface. Inventory lists TeleAgent as installed with chat unavailable, disables selection, and excludes it from automatic routing. This release does not claim TeleAgent conversation support.

### TeleAgent 2.7.0-rc.2 capability probe (2026-10-09)

The installed runtime is `~/.local/share/TeleAgent/runtimes/super-agent-code/bin/TeleAgent`. Read-only, time-bounded probes of `--help`, `help`, `run --help`, `acp --help`, `serve --help`, `server --help`, and `--version` each exited 1 with `unknown command`. No supported CLI conversation entry was found. The Electron main runtime contains no `session/new`, `session/prompt`, or `Agent Client Protocol` implementation; the Go runtime strings also lack those ACP markers. This is evidence for the installed build, not proof about all TeleAgent releases.

The desktop's bundled SDK contains HTTP session creation, message submission, and reply retrieval (`POST /session`, `POST /session/{id}/message`, `GET /session/{id}/message`), and the desktop launches its backend with account-specific configuration and authentication. This is a candidate private HTTP integration, not ACP. The active process listens locally; guessed `/global/health` and `/doc` routes on port 19876 returned 404, so a usable authenticated API has not yet been verified. No conversation was submitted, no credentials were exported, and no running task was interrupted. Inventory remains installed/unavailable pending adapter validation.

### TeleAgent local HTTP verification

The desktop-persisted IM bridge state confirms backend URL `http://127.0.0.1:4397`. GET `/ready` returned 200 with ready status; GET `/health` returned 200 with ok status. GET `/session` and `/event` each returned 401 `local_auth_missing`. The earlier port 19876 probe was not the session backend.

Desktop code signs local requests using `X-SA-Sign-Version: local-v1`, timestamp, nonce, and HMAC-SHA256 signature, with a random per-process session key. It also generates a random Basic credential in main-process memory. The backend receives bootstrap configuration through stdin, not an ordinary reusable on-disk credential file. SSH provides access to loopback transport but does not supply this application authentication. A supported authentication handoff or application-authorized bridge remains necessary before declaring mobile integration functional. No authenticated session access or conversation round trip has been verified; this check performed GETs only.
