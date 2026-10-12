function Assert-FreshRequest($Request,[string]$Id,[string]$BrokerId,[DateTime]$Now=[DateTime]::UtcNow) {
    if ($Id -notmatch '^[0-9a-f]{32}$' -or $Request.protocol -ne 1 -or $Request.id -ne $Id -or $Request.brokerId -ne $BrokerId) { throw '请求身份无效或桥接已重启，不执行旧任务' }
    $age=($Now-[DateTime]::Parse($Request.createdUtc).ToUniversalTime()).TotalSeconds
    if ($age -lt -5 -or $age -gt 15) { throw '请求已过期，不执行旧任务' }
    if ($Request.prompt -isnot [string] -or [string]::IsNullOrWhiteSpace($Request.prompt) -or $Request.prompt.Length -gt 85000) { throw '任务内容为空或超过限制' }
}
function Resolve-DesktopFiles($Files,$Adapter) {
    if ($null -eq $Files) { return @() }
    if ($Files -isnot [Array]) { throw '附件列表格式无效，未操作电脑' }
    if ($Files.Count -eq 0) { return @() }
    if ($Adapter.fileUploads -ne $true) { throw '此桌面 APP 暂不支持手机文件附件，请切换 CLI 或在电脑添加附件；未发送' }
    if ($Files.Count -gt 4) { throw '每条消息最多添加 4 个文件，未发送' }
    $result=@();$seen=@{};[long]$total=0
    foreach($file in $Files) {
        $path=[string]$file.path
        if($path -notmatch '^[a-zA-Z]:[\\/]' -or $path -match '[\x00-\x1f\x7f"<>|?*]' -or $path.Substring(2).Contains(':')){throw '附件路径无效，未发送'}
        $full=[IO.Path]::GetFullPath($path)
        if($full -cne ($path -replace '/','\') -or [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($full)) -cne '.agent-control-input' -or [IO.Path]::GetFileName($full) -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-.+') { throw '附件不属于手机上传目录，未发送' }
        if($seen.ContainsKey($full)){throw '附件路径重复，未发送'}
        $seen[$full]=$true
        $info=Get-Item -LiteralPath $full -Force -ErrorAction Stop
        if($info.PSIsContainer -or $info.Attributes -band [IO.FileAttributes]::ReparsePoint){throw '附件必须是普通本地文件，未发送'}
        $parent=$info.Directory
        while($null -ne $parent) {
            if($parent.Attributes -band [IO.FileAttributes]::ReparsePoint){throw '附件目录不能是链接或联接，未发送'}
            $parent=$parent.Parent
        }
        [long]$size=0
        if(![long]::TryParse([string]$file.size,[ref]$size) -or $size -lt 0 -or $size -gt 20MB -or $info.Length -ne $size){throw '附件大小无效或上传后已改变，未发送'}
        $total+=$size;if($total -gt 40MB){throw '附件总大小超过 40 MB，未发送'}
        $extension=[IO.Path]::GetExtension($full).TrimStart('.').ToLowerInvariant()
        if($Adapter.fileExtensions -and $extension -notin $Adapter.fileExtensions){throw '此桌面 APP 不支持该附件类型，请使用 CLI；未发送'}
        $result+= [pscustomobject]@{path=$full;name=[IO.Path]::GetFileName($full);size=$size;mime=[string]$file.mime}
    }
    return $result
}
