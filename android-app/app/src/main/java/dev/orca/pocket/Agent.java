package dev.orca.pocket;

import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public final class Agent {
    public final String id, name, kind;
    public final List<String> command;
    public final boolean installed;
    public Agent(String id, String name, String kind, List<String> command, boolean installed) {
        this.id=id; this.name=name; this.kind=kind; this.command=command; this.installed=installed;
    }
    public boolean supported() { return !kind.equals("detected"); }
    public boolean desktop() { return kind.equals("desktop"); }
    public JSONObject json() throws JSONException {
        return new JSONObject().put("id",id).put("name",name).put("kind",kind).put("installed",installed)
            .put("supported",supported()).put("detail",!supported()?"已安装 · 手机控制尚未适配":desktop()?"桌面会话 · 需解锁及桌面桥接权限":kind.equals("dsh")?"CLI · DSH ACP · 内置 CLI 沿用桌面模型":kind.equals("buddy")?(id.equals("workbuddy")&&command.size()==2&&command.get(1).endsWith("/WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin/codebuddy")?"CLI · 桌面托管 · 需 WorkBuddy 已登录并运行":"CLI · CodeBuddy ACP · 需 CLI 登录"):"CLI · 使用电脑已配置的登录");
    }
    public static String quote(String s) {
        if(s.indexOf('\0')>=0) throw new IllegalArgumentException("不支持 NUL 字符");
        return "'"+s.replace("'","'\"'\"'")+"'";
    }
    public String shell(String directory, boolean write) {
        return shell(directory,write,"Darwin");
    }
    public String shell(String directory, boolean write, String os) {
        List<String> a=new ArrayList<>(command);
        if(kind.equals("codex")) {
            a.addAll(Arrays.asList("-c",os.equals("Windows")?"approval_policy='never'":"approval_policy=\"never\"","exec","--json","--skip-git-repo-check","--sandbox",write?"workspace-write":"read-only","-"));
        } else if(kind.equals("claude") || kind.equals("buddy")) {
            a.addAll(Arrays.asList("-p","--verbose","--output-format","stream-json","--permission-mode",write?"acceptEdits":"dontAsk"));
            if(!write) a.addAll(Arrays.asList("--tools","Read,Glob,Grep"));
        } else if(kind.equals("dsh")) {
            a.addAll(Arrays.asList("headless","-"));
        } else throw new IllegalArgumentException("该 Agent 使用桌面适配器");
        if(os.equals("Windows")) {
            StringBuilder ps=new StringBuilder("$ErrorActionPreference='Stop'; Set-Location -LiteralPath "+psQuote(directory)+"; & ");
            for(String arg:a)ps.append(psQuote(arg)).append(' ');
            ps.append("; if($null -ne $LASTEXITCODE){exit $LASTEXITCODE}");
            return powershell(ps.toString());
        }
        StringBuilder cmd=new StringBuilder("cd -- "+quote(directory)+" && exec ");
        for(String arg:a)cmd.append(quote(arg)).append(' ');
        return login(cmd.toString());
    }
    public static String login(String script) {
        return "exec \"${SHELL:-/bin/sh}\" -lc "+quote("export PATH=\"$HOME/.local/bin:$HOME/.npm-global/bin:/opt/homebrew/bin:/usr/local/bin:$PATH\"; "+script);
    }
    public static String psQuote(String value) {
        if(value.indexOf('\0')>=0)throw new IllegalArgumentException("不支持 NUL 字符");
        return "'"+value.replace("'","''")+"'";
    }
    public static String powershell(String script) {
        String source="$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; "+
            "[Console]::InputEncoding=[Text.UTF8Encoding]::new($false); [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); $OutputEncoding=[Console]::OutputEncoding; "+script;
        String command="powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "+
            Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_16LE));
        if(command.length()>8000)throw new IllegalArgumentException("Windows 命令或路径过长");
        return command;
    }
    public static String windowsInputScript(String command,String input) {
        String prefix="powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ";
        if(!command.startsWith(prefix)||!command.substring(prefix.length()).matches("[A-Za-z0-9+/=]+"))
            throw new IllegalArgumentException("Windows 输入只接受编码后的 PowerShell 命令");
        String encoded=command.substring(prefix.length());
        String data=Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
        return "$inputFile=$PSCommandPath+'.stdin'; try { [IO.File]::WriteAllBytes($inputFile,[Convert]::FromBase64String('"+data+"')); "+
            "$child=Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand','"+encoded+"') "+
            "-RedirectStandardInput $inputFile -NoNewWindow -Wait -PassThru; $code=$child.ExitCode } finally { [IO.File]::Delete($inputFile) }; exit $code";
    }
    public static boolean absoluteDirectory(String directory,String os) {
        if(directory.indexOf('\0')>=0||directory.indexOf('\n')>=0||directory.indexOf('\r')>=0)return false;
        if(!os.equals("Windows"))return directory.startsWith("/");
        return directory.matches("^[A-Za-z]:[\\\\/].*") || directory.matches("^\\\\\\\\[^\\\\/]+[\\\\][^\\\\/]+(?:[\\\\].*)?$");
    }
}
