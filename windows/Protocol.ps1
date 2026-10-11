function Assert-FreshRequest($Request,[string]$Id,[string]$BrokerId,[DateTime]$Now=[DateTime]::UtcNow) {
    if ($Id -notmatch '^[0-9a-f]{32}$' -or $Request.protocol -ne 1 -or $Request.id -ne $Id -or $Request.brokerId -ne $BrokerId) { throw '请求身份无效或桥接已重启，不执行旧任务' }
    $age=($Now-[DateTime]::Parse($Request.createdUtc).ToUniversalTime()).TotalSeconds
    if ($age -lt -5 -or $age -gt 15) { throw '请求已过期，不执行旧任务' }
    if ($Request.prompt -isnot [string] -or [string]::IsNullOrWhiteSpace($Request.prompt) -or $Request.prompt.Length -gt 85000) { throw '任务内容为空或超过限制' }
}
