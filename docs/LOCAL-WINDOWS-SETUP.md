# 本机 Android 与 Windows 接入

本次产物：`dist/Agent-Control-0.5.22.apk`，Android 8.0 及以上。该修复版延用上一版本机签名，可覆盖本次提供的旧版并保留手机配置。若手机已装其他签名的同包名应用，需要先备份手机应用内配置，再自行卸载旧版后安装。本地签名材料位于 `android-app/.signing`，不提交或分享。

0.5.22 新增 Windows TeleAgent 的 HTTP 后台 CLI 包装。本机 TeleAgent 2.7.0 / 后台 1.2.27 已取得真实模型回复 **47 × 89 = 4183**，生产 PocketEngine → JSch SSH/SFTP 也已取得 **53 × 97 = 5141**，标准输入输出返回回复与完成事件，无 GUI 操作或桌面回退。需当前 SSH 登录的 Windows 用户已经登录 TeleAgent；每次使用独立临时数据库与后台，不接管桌面会话。权限问答已有离线测试，实体 Android 尚未验收。详见 [当前 CLI 支持与验证范围](CLI-SUPPORT.md)。

0.5.21 补上 Windows WorkBuddy、DeepSeek Harness 的托管 CLI 启动和 ACP 回复回传，修正 Claude npm 入口；详见 [CLI 检查与验证](AGENT-CLI-AUDIT.md)。修复电脑任务脚本重复 BOM 和手机失败任务不显示原因的问题。安装此版本后断开并重连以刷新 Agent 列表。WorkBuddy CLI 仍需电脑桌面应用保持已登录和运行。

0.5.20 将 Windows 桥接安装与队列迁至 `%ProgramData%\AgentControl\<当前用户 SID>`，目录 ACL 仅允许该用户和 SYSTEM，确保本机桌面和 SSH 会话能读取同一队列；客户端保留旧目录回退。通过真实 SSH 和 App 使用的 SFTP 脚本传输代码确认检测到 Claude、Codex CLI，以及 WorkBuddy、豆包、TeleAgent、DeepSeek Harness 四个桌面应用。Codex CLI 不再作为桌面应用通告。

连接设置显示从 Android 包信息读取的 APP 版本与构建号。开启“记住登录密码并自动连接”后，成功登录的凭据保存在 Android Keystore 支持的 AES-GCM 加密存储中；下次启动自动连接，手动重连留空密码时仅对相同主机、端口、用户名复用保存密码。关闭开关清除保存凭据。密码不写入普通聊天状态或源码；本次无法直接修改手机上的开关，需用户首次在手机输入并开启保存。

0.5.19 修复 Windows 文件上传、下载和下载列表的 SFTP 盘符路径：使用 `/C:/...`，避免 JSch 把 `C:/...` 拼接在远程用户目录后造成 `No such file`。持久任务服务优先查找进程、用户和系统的 JAVA_HOME，再查 PATH；显式 Java 路径不存在时给出具体提示。

0.5.18 修复 Windows aapt2 将图标和许可证等嵌套资源写成反斜杠 ZIP 路径、导致手机 WebView 图标全部加载失败的问题。签名前统一 APK 资源分隔符，并在签名后检查所有资源路径及逐文件 SHA-256。

## 已完成与验证范围

- 安装 JDK 17、Android SDK Platform 35、Build Tools 35.0.0，并校验下载文件摘要。
- 安装浏览器 worker 的 Node 依赖并通过 TypeScript 检查。可选 Chromium 下载和 worker 启动没有执行。
- APK 完成 v2/v3 签名验证、zipalign 验证；Windows 桥接逻辑、Java 交互与会话测试、指定 Node 回归测试通过。
- 0.5.21 Windows WorkBuddy 托管 ACP 与 DSH ACP 已整合；使用与手机相同的 Java SSH/SFTP 实现均取得真实 `WINDOWS_CLI_OK` 和 `end_turn`。WorkBuddy 桌面控件接入当时未验证，但当前 CLI 路径不依赖聊天控件。
- 0.5.22 TeleAgent Windows 后台 CLI 已通过本机真实文本对话和退出清理，18 项离线 HTTP / CLI 生命周期回归通过；生产 PocketEngine → JSch SSH/SFTP 真实回复通过，无桌面回退，Java 测试退出码 0，engine busy 释放。证据见 [SSH 验证记录](../android-app/test-output/teleagent-cli-ssh-verified.json)；手机权限与问答没有实体 Android 验收。
- 2026-10-12 早期 TeleAgent 2.7.0 Windows 桌面控件检查识别了新任务、输入框、运行、完成标题和复制工具栏；回复边界、时间戳排除、生成中拒绝完成等测试通过。该桌面路径真实点击遇到 `GetCursorPos failed: 拒绝访问 (0x80070005)`，当时未发送模型消息，仍需单独验收；不影响本轮无 GUI 的后台 CLI 验证结论。
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

桌面模式从**普通权限、已登录且已解锁的桌面**运行 `windows/Start-Bridge.cmd`，并保持桥接和桌面会话运行。不要在管理员会话启动桌面桥接，避免与普通权限应用不匹配。安装目录为 `%ProgramData%\AgentControl\<当前用户 SID>\bin`。此前修改登录启动项的组合命令被自动审批拒绝，因此新共享目录的自动启动尚未配置。

TeleAgent 的“仅 CLI”通过 SSH 启动临时后台，不依赖桌面辅助功能或鼠标控件。当前 SSH Windows 用户必须拥有有效的 TeleAgent 登录；受 Windows 保护的登录状态仅在进程内解密并经 stdin 初始化后台，明文不落盘。客户端请求全部访问 `127.0.0.1`，但后台监听 wildcard 地址；会话、消息等业务接口使用强制随机 HMAC 签名和 Basic 认证，不向手机提供后台端口或密钥。退出只终止自己的会话和后台并清理临时数据库。WorkBuddy CLI 仍需桌面保持登录、运行。

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
4. 核对 SSH 主机指纹，WorkBuddy、DeepSeek Harness、Codex、Claude、Windows TeleAgent 可选择“仅 CLI”；豆包选择“直接桌面”。TeleAgent 需该 SSH Windows 用户已登录桌面账号，WorkBuddy 桌面需保持运行。安装后断开并重连以刷新 Agent 列表，再选择目标 Agent。
5. 分别发送无副作用的短回复测试，检查电脑收到完整消息、手机收到真实回复并显示完成。先分别测试各应用，再测试第二条消息继续同一电脑对话。

Windows 桌面适配的附件入口按各 APP 实现，具体支持与验收范围见下方版本记录；macOS 的图片、附件、确认卡片能力不能直接视为 Windows 已支持。桌面模式遇到应用需要批准权限或回答问题时，先在电脑完成并核对状态。

TeleAgent 后台 CLI 已实现手机权限与问答返回：未开启“允许写入”时拒绝写入和命令，开启后对写入及 PowerShell 命令由手机选择一次允许或拒绝；Bash 与工作目录外操作仍拒绝。问答可选择选项、多选或输入自由文本。这些协议有离线回归覆盖，实体 Android 的确认卡片与实际点击尚未验收。该临时后台不继承原桌面会话历史、技能和全局记忆；图片、文件以及真实模型权限交互仍需端到端验证。

## 本机重新构建

```powershell
pwsh -NoProfile -File windows/Setup-BuildTools.ps1
pwsh -NoProfile -File windows/Build-Android.ps1 -Test
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android-app/tests/windows/Test-Logic.ps1
```

Windows 构建脚本使用 PowerShell 7。CoreTest 包含硬编码 `/bin/zsh` 探测，在 Windows 上未运行；`-Test` 运行 WindowsTransportTest、InteractionTest、DesktopContinuityTest 和 TeleAgentSessionTest，并回放真实 TeleAgent 回复的脱敏 fixture。

## 0.5.23 本机 Android 链路验收（2026-10-12）

已安装官方 Android SDK Emulator 与 API 35 x86_64 系统镜像，AVD 为 `AgentControlTest`。在正式签名 APK 内通过真实 SSH 私钥连接 `10.0.2.2:22`，没有替换网络或 Agent 返回值。

- TeleAgent CLI：手机收到中文答案 `53 × 97 = 5141` 并完成。
- WorkBuddy CLI：手机收到中文答案 `47 × 89 = 4183` 并完成。
- WorkBuddy 桌面：实际点击原生输入、发送并读取答案，手机收到 `43 × 79 = 3397` 与唯一测试标记并完成。
- 运行中的 WorkBuddy 桌面任务：手机点新对话，电脑停止原任务、保存 `ended`，随后手机创建新会话。
- 运行中的 TeleAgent CLI：手机点新对话，原任务取消并确认本地进程退出，随后创建新会话。
- 消息与回复均显示秒级时间；中文标签没有乱码。截图保存于 `android-app/test-output/emulator/`。

本机桥接代码已更新；其他电脑也需要更新 Windows 桥接并重启。安装脚本为既有 profiles 添加缺失配置，保留已有自定义值。上述验收限于三个列出的文本接入路径，其他 Agent、图片及真实审批场景仍须分别验收。

## 0.5.25 本机桥接附件能力更新（2026-10-12）

已部署新版 Windows Bridge，安装文件摘要与源码一致。经用户确认 Windows 权限提示后，仅结束已核对身份且空闲的旧管理员权限桥接，再从普通权限桌面启动新版。新实例发布 `ready=true`、`busy=false`、`fileUploads=true`，WorkBuddy 的应用能力也为 `fileUploads=true`。未重启 WorkBuddy，也未重放手机失败的消息。

安装器已修复既有私有目录的普通权限更新，并在旧实例持锁时提示重启，避免启动重复实例；Windows PowerShell 5.1 的 109 项检查通过。`dist/Agent-Control-Windows-Bridge.zip` 已同步最新桥接、安装器和说明，核心文件摘要校验通过。

手机使用 0.5.25 即可，断开重连后选择 WorkBuddy「直接桌面」，重新选择图片并发送。上述结果确认了运行中的桥接已发布附件能力；手机图片进入 WorkBuddy 并取得模型回复仍需实际发送验收。

随后修复前台激活的异步误判：在目标输入队列延迟的独立窗口测试中，旧实现提前返回失败，新实现等待激活确认后成功。8 项原生激活检查和 109 项逻辑检查通过。修复后的驱动已安装并以普通权限重启，运行实例 `ready=true`、`busy=false`，WorkBuddy 附件能力保持启用；没有自动重发此前失败的手机任务。

## 0.5.26 文件输入与聊天滚动修复（2026-10-12）

Windows 两处 `Point::new()` 已改为显式坐标构造，修复点击回退和文件菜单悬停在 PowerShell 5.1 的无参构造错误。文件选择器支持经过身份验证的 WorkBuddy utility 直接子进程，附件就绪检查支持文件名只出现在直接包装控件可访问名称的实际结构。失败请求保存本机错误堆栈与前台激活调用结果，保留 24 小时。140 项逻辑检查、17 项独立 utility 文件窗口检查通过；原生激活夹具在初次构造修复时通过 8 项检查，最终复跑因测试窗口未取得前台而未执行断言。

经用户同意，原失败草稿已保存到本机 `test-output/workbuddy-failed-draft.txt` 后清空。新版桥接已安装并从普通权限桌面重启，源码与安装文件摘要一致，发布 `ready=true`、`fileUploads=true`。用户自行将 WorkBuddy 模型选择切换到「极致」后，995 字节红圆/蓝方块图经生产 Bridge 队列、原生文件选择器、编辑区附件核对、一次发送和完成识别取得真实回复“左边是一个红色圆形，右边是一个蓝色正方形。”。此前失败的手机私有图片未被重放。该验收从本机队列提交，未覆盖实体手机或 SSH；「Space-Bunny」时发送按钮禁用的运行时原因尚未确定。

WorkBuddy CLI 已按 ACP 协商能力传入图片内容和 UTF-8 文档资源，其他文件保留本地资源链接。较大的图片生成受传输预算约束的 JPEG 预览，原上传文件保留；预览总量最多 256KiB，完整内嵌内容最多 512KiB，不修改 WorkBuddy 的第三方桥接限制。历史图片按实际注入预览大小计入预算，单文件读取仍最多 20MiB。43 项附件协议检查和 128MiB 堆内的图片预览测试通过。995 字节红圆/蓝方块测试图经生产 `InputFiles → PocketEngine → LocalTransport → WorkBuddy WBIPC → ACP` 取得真实回复“左：红色圆形；右：蓝色正方形。”，没有桌面回退。该验收未使用 SSH 或实体手机；大图预览目前有离线图形保真与预算检查。

聊天列表按可见消息保持历史阅读位置，轮询快照、流式更新和确认卡不再强制跳到最后。主动发送、切换会话或点返回最新恢复底部跟随；浏览器回归已在旧版本复现跳动并在修复版通过。实体手机的滚动手势仍需安装 0.5.26 验证。

## 0.5.27 上传中断修复（2026-10-12）

手机大图上传与连接检查原来可并发，8 秒心跳失败会关闭正在上传的同一 SSH 会话。新增传输租约与连接世代检查，上传期间暂停心跳和连接切换；已开始的连接检查完成后再上传。旧代码在确定性并发回归中失败，新代码通过。传输失败释放租约并触发连接恢复，不重发 Agent 消息。

SFTP 原来的固定 60 秒期限改为连续 60 秒无数据进展或总计 5 分钟上限，完整长度校验及私有权限设置后才原子发布。17 项离线协议回归覆盖持续 180 秒进展、超时、截断、权限错误、断线及迟到提交回复。Windows 任务服务遇到短暂读共享锁时重试同一次原子替换最多 1 秒；原生句柄测试在旧代码稳定失败，新代码通过，持续锁和只读目标仍明确失败且保留旧内容。

上传中显示居中 loading、文件名和等待提示，应用发送、新对话、Agent、设置、返回等操作被锁定；上传失败保留文字草稿及已成功附件，解除 loading 并显示明确恢复入口。失败文件的 URI 仅保存在当前 APP 实例，可恢复连接后点击「重试上传」；未重试或取消前不切换会话，不会自动重新上传或发送消息，重启 APP 后需重新选择未完成文件。相关测试纳入 Windows 构建检查，浏览器上传恢复回归同时确认旧实现存在 busy 残留。

独立本机 SSH/SFTP 服务验证使用生产 JSch 与上传路径写入实际 Windows 中文及 emoji 目录，2MiB、20MiB随机文件的长度与 SHA-256 一致；限速 2MiB 传输持续 64,097ms 后成功。强制断线后没有最终文件或自动重传，手动建立新连接后成功。测试监听仅限 loopback，进程与监听已关闭，未改变系统 SSH 配置。此验收覆盖真实加密协议和 Windows 文件系统，未覆盖系统 OpenSSH 或实体手机。

WorkBuddy CLI 大图片与通知双向管道回压已修复。原生 Windows 夹具在 `session/new` 后输出 512KiB 通知，同时手机侧发送 256KiB 图片；旧实现小图成功、大图卡到超时报「管道已结束」，新实现两种大小都收到完整图片并返回完成事件。SSH 交互及电脑服务采用独立、有界的输入写线程，stdout 继续读取；12 项队列检查涵盖顺序、复制、异步失败、上限及清理。Windows 构建再次通过该夹具，大图约 364ms。

用户已授权的红圆/蓝方块合成图扩大到 1,193,880 字节，生产 `TaskService.previewImage` 生成 219,061 字节预览，实际 ACP 请求超过 292KiB。经生产 `InputFiles → PocketEngine → LocalTransport → WorkBuddy WBIPC → ACP` CLI 链路取得真实回复「左边是红色圆形，右边是蓝色正方形。」，无桌面回退且执行状态已释放。该模型验收没有使用用户私有图片，没有重放先前失败消息，也未覆盖实体手机或系统 OpenSSH。

本次修复在 Android 客户端及随 APK 打包的电脑任务服务中，已安装的 Windows 桌面桥接继续使用。手机需安装 `dist/Agent-Control-0.5.27.apk`，仅重新安装桌面桥接无法更新旧手机客户端。实体手机当前未通过 ADB 连接，本机测试不代表该手机实测。

本机电脑任务服务已通过 `shutdownIfIdle` 确认空闲后退出旧实例，再使用与新 APK 相同的哈希版 JAR 启动。快照返回的运行时路径与部署文件一致，新 PID 心跳持续刷新，6 个 Agent 检测正常，任务状态记录保持完整；没有终止进行中的任务或自动重发失败消息。新版 APK 已通过 v2/v3 签名、对齐及全部 46 个打包资源摘要检查。

0.5.28 为 WorkBuddy CLI 生成的完整模型请求预留预算：一轮图片预览合计最多 128KiB，完整 ACP 请求最多 256KiB（含 UTF-8、JSON 转义与协议封装），原图保留。Windows 转发器继续按真实 HTTP 请求的 640KiB 上限校验，普通和 gzip 超限均不会转发或自动重试。真实模型验收：1,193,880 字节合成红圆/蓝方块图片，预览 126,054 字节，完整模型请求 206,959 字节，正确回复；未使用或重发用户私有照片。
手机安装 dist/Agent-Control-0.5.28.apk 后使用新版预算；电脑任务服务同步部署相同哈希的 JAR。已失败的任务不自动重放。

Windows WorkBuddy 桌面附件发送核对修复：Chromium 插入附件后可能省略空白段落对应的 UIA Text 叶子。先核对文字叶子，只有换行存在差异时才用完整输入框值恢复换行，并逐个排除唯一、已确认的附件标签；文字空格、真实换行改变、缺失或重复文件名仍拒绝发送。实机现有失败草稿只读验收：文字和附件均通过核对；145 项 Windows 逻辑检查通过。
此项仅需更新并重启 Windows 桌面桥接，手机 APK 无需再次更新。

Windows 桥接在后台 SetForegroundWindow 被拒绝时，增加目标窗口自身 UI Automation SetFocus 恢复，最多等待 1 秒并核对目标 HWND；不向其他应用发送键鼠输入。仍未获得前台则停止，错误明确提示点击目标窗口，保证未输入或发送。150 项 Windows 逻辑检查通过。

2026-10-13：WorkBuddy 前台恢复新增精确任务栏 AppID 回退，实机从 Codex HWND 789792 切换至 WorkBuddy HWND 134114，通过生产 Set-TaskbarWindowForeground 路径（463ms）；无匹配 AppID 与无效 Shell 句柄均被拒绝且焦点不变。150 项现有 Windows 检查通过。只激活窗口，未读取、清除或补发用户草稿。此项更新 Windows 桥接，不修改手机 APK。
