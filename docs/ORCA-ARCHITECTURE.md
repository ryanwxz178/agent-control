# Orca 实现分析与 Android 方案

分析基于 `/Users/ryan/workspace/orca/orca`，commit `8167ddaa7fcf011e4f3aff32d9c61336783f986e`。官网为 https://www.onorca.dev/ 。本次官网/官方文档网络请求部分超时或被拒绝，以下具体实现结论来自本地仓库和已安装工具的帮助输出，不声称核验了无法获取的在线文档。

## 1. 原项目不是“手机直接控制任意 APP”

Orca 的核心是 Electron 桌面运行时，统一组织项目、worktree、Agent 会话、终端和远程执行。仓库已有 React Native / Expo 移动端，但它是 Orca runtime 的客户端。`mobile/README.md` 明确要求启动桌面 Orca，由其在 `6768` 端口提供 WebSocket RPC，手机扫码配对。

移动端的 `mobile/src/transport/rpc-client.ts` 定义 request、subscribe、连接状态、终端流、关闭和能力协商接口。`direct-rpc-client.ts` 把传输拆成 socket session、请求追踪、订阅注册、连接活性检查、认证重试和重连。连接参数包括 endpoint、deviceToken 和 serverPublicKey；手机并不直接启动电脑程序。

`mobile-e2ee-v2-key-schedule.ts` 用 HKDF-SHA256，根据共享密钥、双方 nonce 和握手 transcript 派生两个方向的密钥以及 session ID。配对身份和消息加密不是简单依赖裸 WebSocket。更换成 SSH 后不能继续沿用这些 token，也不能把“能连端口”当成认证成功。

## 2. Agent 检测与执行端归属

`src/main/preflight/agent-detection.ts` 在实际执行主机上检测工具。它先恢复登录 shell 的 PATH，再从统一 Agent 配置生成探测命令；本地、WSL 和 SSH 目标走不同路径。`agent-command-detection.ts` 对远端调用 `preflight.detectAgents`，本地补查已知安装目录。

这些实现说明：手机不能通过自己文件系统推断电脑安装了什么；仅看电脑桌面的图标也不能证明存在可调用的会话接口。新 APP 把 `detect.sh` 通过 SSH 送到执行主机，检查 PATH 和 macOS 安装路径，返回实际发现的 Agent。它专门检查 WorkBuddy、DeepSeek 包内的后台入口，而不是因为存在 `.app` 就伪装 CLI 可用。

## 3. 原生聊天不是终端文本伪装

`src/main/runtime/runtime-rpc/runtime-rpc-mobile-agent-session-methods.ts` 提供 create、send、cancel、history、subscribe、respondToApproval、respondToQuestion、setOption 等结构化会话操作。移动 UI 可以直接使用消息与状态，不需要让用户看到终端。

Codex 路径在 `src/main/codex/codex-structured-launch-resolution.ts` 选择 `app-server` 并维护原生 thread / rollout 的恢复身份。Claude 的 `claude-stream-json-connection.ts` 解析逐行 JSON，同时处理控制请求、退出、取消和子进程状态。两者的具体协议不同，通过适配器归一到会话模型。

Orca 对“已发送但结果不确定”与“执行确实结束”做区分。`rpc-client.ts` 明确要求已进入 wire 的请求在断线时保留 delivery-unknown 语义；不能用自动重连顺手重放写操作。仓库的 Agent 状态规则也强调由执行主机提供状态，而不是由每个客户端猜测。

## 4. 为什么没有直接打包已有 mobile/

直接构建上游客户端能获得最多功能，但用户必须运行 Orca 电脑端，与“尽量免额外桌面 APP”不一致。去掉 Electron 后，原有 RPC runtime、PTY 守护进程、文件和会话服务仍需运行，换成另一个本地服务并没有消除依赖。

本实现选择系统 SSH 作为执行和认证层；Android 原生 Java 管理 SSH、Keystore 和生命周期，APK 内本地 WebView 负责聊天 UI。WebView 没有远程站点、没有任意 URL 导航、没有终端组件；只有固定的连接、发送、取消、保存和密钥导入桥接方法。无需安装 Expo 或在电脑启动 Metro。

对 Codex，本版本选择更小的 `exec --json` 协议，而没有移植完整 app-server。对 Claude 和 WorkBuddy，采用其 `stream-json` 后台模式；对 DeepSeek，采用已验证存在的 `headless` profile。它们仍在电脑执行，沿用各自账号和模型配置，Android 不保存模型 API Key。

代价是没有移植 Orca 的完整审批、持久化 thread/rollout、附件、背景任务与恢复语义。当前通过聊天历史重建上下文，是一个明确的第一版实现边界，不等同于上游的原生会话恢复。

## 5. 无公开后台接口的桌面 APP

豆包的已安装桌面版暴露可访问聊天输入框和回复区。本次实际 UI 验证得到：新对话入口、`doc_editor` 聊天区域、用户文本、回复文本，以及回复后的“朗读”按钮。该证据用来编写版本对应的桌面适配器，不能泛化为所有版本都稳定支持。

适配器作为 APK 资源，通过 SSH stdin 临时送给 macOS 自带 osascript。JXA 绑定 ApplicationServices 的原生 AX API，一次构造界面快照，避免逐控件 System Events Apple Event 往返；System Events 仅用于必要的键盘输入。它先检查应用是否忙、唯一新对话入口与唯一输入框，然后写入并读回完整消息，确认后才发送。读取豆包回复时限定聊天区域，并截在“朗读”控件之前，过滤时间戳与推荐问题。输入焦点争用通过手机调度器的桌面互斥锁减少；它不能锁住用户或其他设备，所以仍要求电脑不要同时操作该窗口。

WorkBuddy 的内置 CLI 虽与桌面同包，但真实测试返回需要登录，不能假定共享桌面账号。因此提供“默认 CLI 优先、明确启动失败转桌面”和“直接桌面”两种接入方式。DeepSeek 的 headless profile 同样独立于桌面配置；0.1.3 已识别其明确的 MISSING_CREDENTIAL 启动错误，未输出任务内容时可回退桌面。

桌面适配器不绕过系统权限，首次需要电脑用户完成辅助功能/自动化授权。本次先用 CUA 确认控件，再在用户明确允许 AppleScript 实测后，通过真实 SSH 运行 APK 内同一脚本，豆包返回“测试成功。”和 `desktop.done`。后续 WorkBuddy 已验证真实收发。0.1.3 为 DeepSeek 增加“新建会话”入口、空白会话验证与按轮次提取完成回复，真实适配器已收到“测试成功”和 desktop.done。

## 6. 自动调度

`TaskRouter` 先把实际可用 Agent 名单交给一个协调者，请它只输出 JSON 分工。APP 检查 ID 必须在清单里、最多 3 项、不重复、任务非空。不是无条件向每个 Agent 广播同一提示词。

简单任务可选一个执行者；复杂任务并行收集分析，再由一个执行者统一处理。后续节点不会在任务已取消时启动；部分分析失败会清楚显示，剩余分析可以继续汇总。明确未登录等尚未执行的失败允许换人；网络中断等执行结果不确定的失败不重试，以防重复修改。

桌面 Agent 和 DeepSeek 的权限由电脑配置控制，不能声称受 Codex 沙箱保护。UI 将这一点作为接入设置的一部分。多 Agent“只分析”对这些路径是任务约束，不是安全隔离。

## 7. 代码结构

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 安全 WebView、主机信任对话框、私钥导入、平台生命周期 |
| `SecureStore.java` | Android Keystore + AES-GCM 本地加密 |
| `SshTransport.java` | 固定主机指纹、独立 exec 通道、stdin、超时和取消 |
| `Inventory.java` / `detect.sh` | 执行主机的安装检测 |
| `Agent.java` | 各 CLI 参数及 shell 参数转义 |
| `EventDecoder.java` | 不同结构化事件转成消息/状态 |
| `PocketEngine.java` | 连接、检测、任务生命周期、桌面互斥与事件桥 |
| `TaskRouter.java` | 分工验证、并行分析、汇总、有限回退 |
| `desktop-chat.js` | macOS 桌面聊天适配器 |
| `index.html` / `app.js` / `style.css` | 手机聊天、连接与 Agent 选择界面 |

源仓库没有改动。本项目借鉴协议分层与执行端归属原则，而不是复制一套不能独立运行的 Electron IPC 层。


## 8. Windows 原生与云电脑路径（0.1.5 预览）

POSIX 检测不能完成时，再通过 Windows PowerShell 编码启动器读取 stdin 检测脚本。Windows CLI 使用 UTF-16LE EncodedCommand，工作目录通过 Set-Location -LiteralPath 传递，任务文本仍走 UTF-8 stdin。心跳按主机系统选择命令，WSL 维持 Linux 路径语义。

Windows sshd 服务与交互桌面隔离，因此 UI Automation 由当前用户会话中的 Bridge 执行。SSH 客户端提交具有唯一 ID、实例 ID、时效和租约的固定结构请求，桥接在受 ACL 保护的本地目录串行处理，不开放额外网络端口。新实例不恢复旧任务；锁屏、RDP 会话失活或取消时停止操作。执行中的桌面模型任务不保证可取消。安装与限制见 WINDOWS.md。

目前只有本机可运行的 PowerShell 逻辑/语法、原生进程输入输出和 Android 核心回归通过，Windows UIA 及目标云电脑尚无真实收发验收结果。
