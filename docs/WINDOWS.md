# Windows 桌面与 Windows 云电脑接入

Windows 路径使用原生 OpenSSH / SFTP、PowerShell 5.1 和 UI Automation 桌面桥接。手机使用同一个 APK。当前整合包为 0.4.4，保留 0.4.0 的 Windows 桌面修复，同时包含 0.3.7 及 macOS WorkBuddy 托管 CLI 更新；新版桥接安装包与源码包单独命名，旧包已清理。2026-10-09 已在天翼云 Windows Server 2022（10.0.20348）跑通 Android → 豆包 → 精确回复 → 完成状态 → 重开应用后读回。Windows 10/11 尚未独立验收；应用版本之间的控件差异仍需要在目标电脑上验收 profiles.json。当前结果以 VALIDATION.md 为准。

## 两条执行路径

```text
Android → SSH → Windows PowerShell → Agent CLI → JSON 事件 → 手机
Android → SSH → 当前用户私有请求目录 → 交互桌面 Bridge
        → Windows UI Automation / 键盘粘贴 → WorkBuddy、豆包等 → JSON 事件 → 手机
```

桌面自动化使用 UI Automation（UIA）：读取控件树、定位新对话/输入/发送控件，使用真实键盘粘贴触发输入事件，再读取当前回复和生成状态。这是系统辅助功能自动化，不是截图模型驱动的 computer-use，也不依赖 CDP。macOS 对应使用 Accessibility API；两者共用手机协议，桌面驱动分别实现。

Windows OpenSSH 的 exec 标准输入在验收主机上出现 16KB 截断，因此手机通过 SFTP 上传唯一的 UTF-8 BOM 脚本，然后执行并清理。通用一次性 CLI 输入也通过临时文件重定向到子进程，中文、多行与退出码已实测。CLI 不需要桌面桥接，不依赖 RDP 窗口或解锁状态。桌面控制必须在应用所在的交互用户会话内运行，不能由 sshd 的服务会话直接操作窗口。桥接不监听网络端口，仅从同一 Windows 用户的私有目录接收固定格式任务，不执行请求中传来的脚本。

目标环境：Windows 11、Windows 10 和带 Desktop Experience 的 Windows Server 云主机，Windows PowerShell 5.1、.NET Framework UI Automation、OpenSSH Server。目前尚未逐系统验证。Server Core 和没有交互桌面的云环境仅适合 CLI。WSL 继续作为 Linux 执行主机，不提供 Windows 桌面控制。

## 安装

1. 在 Windows 桌面登录准备供手机使用的账号，打开 WorkBuddy、豆包并完成登录。SSH 也必须使用这个账号。多用户云电脑不可用一个账号连接另一个账号的桌面。
2. 安装并启动 OpenSSH Server。可在管理员 Windows PowerShell 中执行发行包内的 `Enable-Ssh.ps1 -AllowedAddresses '192.168.2.0/24'`，将网段替换成可信手机/Mac/VPN 来源地址。该脚本保留已有 GitHub OpenSSH 安装，校验并修复相对 sftp-server 路径（先备份配置），将 OpenSSH 默认防火墙规则限制到指定地址，并输出主机指纹，不修改 SSH 登录方式和默认 shell。已有其他防火墙规则由管理员自行管理。
3. 在**应用所在的 Windows 桌面会话**中解压 Windows Bridge 安装包，用 Windows PowerShell 运行：

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install.ps1 -StartAtLogon
   ```

   `-StartAtLogon` 可省略，省略后需每次登录手动启动桥接。计划任务以当前用户、Interactive、Limited 方式运行，不保存密码，不注册 SYSTEM 桌面服务。SSH 中执行安装只负责复制文件，不能代替在桌面启动桥接。
4. 安装位置为 `%LOCALAPPDATA%\OrcaPocket\bin`。私有通信目录为 `%LOCALAPPDATA%\OrcaPocket\bridge`。保持 Bridge 窗口运行。
5. 手机填写 Windows 地址、22 端口、账号及已有 SSH 密码或私钥，核对主机指纹。Windows 工作目录可留空，或填写 `C:\Users\你的用户名\project`；支持盘符绝对路径和 UNC 路径，网络共享仍需该 SSH 会话具有访问权限。
6. 连接后选择“直接使用桌面应用”，先用无副作用的短回复验证 WorkBuddy 和豆包。CLI 账号需单独登录；CLI 出现明确未登录错误且尚未开始执行时，才会自动转桌面。

## 应用发现与控件适配

桥接每 10 秒检查当前会话的运行进程和常见安装位置。首次推荐手动打开应用。自定义安装路径可写入安装目录的 `apps.json`，例如：

```json
{"workbuddy":"D:\\Apps\\WorkBuddy\\WorkBuddy.exe","doubao":"D:\\Apps\\Doubao\\Doubao.exe"}
```

路径只接受存在的 exe；窗口还必须匹配 profiles.json 中的进程名称及实际可执行文件路径。若安装后新增应用，在手机重新连接刷新列表。

`profiles.json` 保存每个应用的新对话、空白欢迎界面、发送、停止和完成控件规则，以及可选的 `editorId` / `chatId`。WorkBuddy 5.7.7 和当前豆包规则已经依据 Windows 控件树校准，其他应用规则仍是初始配置，并非对所有应用版本的稳定性承诺。编辑已有配置后重启桥接；安装更新会保留已有 profiles.json，因此有新默认规则时需手工合并。

在目标桌面运行以下只读诊断，可检查会话和 UIA 控件树：

```powershell
powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\OrcaPocket\bin\Diagnose.ps1" -App workbuddy -Snapshot
```

快照可能含当前聊天内容，只应保存在本机，分享前脱敏。如果 Electron 应用未暴露控件，先按该应用支持的方法启用辅助功能；本次 WorkBuddy 和豆包需要同时使用 `--force-renderer-accessibility=complete --enable-features=UiaProvider` 启动，默认 profiles.json 已包含这两个参数。参数只在启动新进程时生效；应用已经运行时需先确认无重要任务，再退出后按参数启动。不要中断已有任务来重启应用。识别失败时适配器停止，不使用猜测坐标或盲目回车。普通权限桥接不能保证操作提升权限的窗口；保持应用与桥接权限等级一致。

## 手机附件与桌面 APP

手机输入区的回形针可在「直接桌面」模式选择文件。请先选择一个具体 Agent；桌面附件消息不会走多 Agent 自动调度。文件先经 SSH 上传到工作目录的 `.agent-control-input`，随后桥接操作该 APP 的附件入口、系统文件选择框，确认附件准备完成后发送。后续文字消息不再次添加历史附件。

当前 Windows 适配依据 WorkBuddy 5.7.7 的「添加文件 → 本地文件」菜单和输入框文件标签实现；缺少相应控件的 APP 会提示尚未发送。文件类型还须满足 WorkBuddy 的限制。如果附件添加失败或中途断开，请在电脑检查输入框草稿，不要自动重复发送。

更新源码后，在电脑空闲时关闭旧 Bridge 进程，重新运行 `windows/Install.ps1` 并启动新版 Bridge，再在手机重新连接。旧版桥接没有附件能力标记，新手机会拒绝向它提交文件消息。安装脚本会为已有 profiles.json 补入新增字段并保留用户配置。

安装脚本会保留已符合要求的私有目录权限，普通权限会话可更新这种既有安装。如果旧 Bridge 仍持有 `broker.lock`，脚本只更新文件并提示关闭旧进程，不再启动重复实例。更新磁盘文件不会替换正在运行的桥接代码；必须重启后才会发布附件能力。若旧桥接以管理员权限启动，需从电脑使用具备权限的任务管理器结束对应 Bridge 进程，再从普通权限桌面运行 `windows/Start-Bridge.cmd`。

## 云电脑与 RDP

- SSH 地址必须从手机可达。可使用同一私有网络、VPN / Tailscale 等已有网络，不要求同一个物理 Wi-Fi，也没有内置公网中继或自动穿透。
- WorkBuddy/豆包、桥接必须位于同一用户的同一活动会话；其他 RDP 会话中的窗口不会被操作。
- 锁屏、注销、UAC 安全桌面或 RDP 断开导致 WTS 会话不再 Active 时，桌面任务会拒绝或停止；CLI 仍可使用。当前不会自动解锁、保存 Windows 登录密码或强制转移 RDP 会话。
- 云电脑厂商的连接保持策略不同，必须验证该产品在关闭客户端、最小化、断线后是否仍提供可交互桌面。不能承诺所有云电脑“断开后桌面无人值守”。
- 分辨率和缩放通过 UIA 控件定位及 DPI 感知的实时坐标处理；多屏/高 DPI 仍需在目标设备验证。

## 任务与故障语义

桥接跨本用户会话使用互斥文件锁，避免两个桌面同时抢任务。每个请求包含唯一 ID、桥接实例 ID、15 秒提交时效及客户端租约。请求领取后不会重放；桥接重启、旧请求、失去租约、桌面锁定都停止操作。输出只在确认用户消息已进入聊天区域，且出现应用完成标记、无生成中控件、回复稳定后标记完成。稳定文本本身不代表完成。

窗口切到前台可能异步完成。桥接会等待目标窗口的激活确认（最多一秒），核对实际前台窗口后才输入；最小化窗口使用异步恢复，已失效的窗口句柄直接拒绝。`android-app/tests/windows/Test-Activation.ps1` 提供独立原生窗口验收，覆盖后台 STA 调用、延迟响应、最小化恢复和输入关联释放；运行时只操作测试自建窗口，需要空闲且解锁的桌面。

停止或 SSH 中断只请求停止桥接交互，不保证已发送给应用的任务被取消。应到电脑核实，不能直接重发修改任务。手机仍需前台运行。Windows 桌面路径本轮验证普通文本任务；macOS 的确认卡片、应用画面操作与图片提取不能直接视为 Windows 已支持。Windows ACP/app-server 的真实模型交互也未验收。

已处理的请求正文立即删除，事件诊断文件保留最多 24 小时（桥接运行时清理）。崩溃遗留的 working 请求不会重新执行，24 小时后清理。文件 ACL 仅允许当前用户和 SYSTEM；同用户程序具有相同访问权，这不是对同用户恶意程序的隔离。

卸载自动启动可运行 `Uninstall.ps1`，然后关闭手工启动的 Bridge 窗口。文件保留，不改动 OpenSSH 配置。

## 实机验收清单

1. 核对主机指纹，手机连接原生 Windows SSH，检测正确系统和工作目录。
2. 用真实 CLI 返回一次唯一确认文本，验证中文、多行、带空格/单引号的目录。
3. WorkBuddy、豆包分别新建测试对话：电脑收到完整消息，手机收到真实回复和完成事件。
4. 确认输入框草稿、侧栏旧标题、未完成输出不会被识别为成功。
5. 锁屏、RDP 断开、桥接重启、SSH 中断时状态明确，恢复后不重发旧任务。
6. 在目标云电脑重复以上检查，尤其验证其远程客户端断开行为。
