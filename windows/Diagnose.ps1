param([ValidateSet('workbuddy','doubao','deepseek','claude','codex')][string]$App='workbuddy',[switch]$Snapshot)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Desktop.ps1"
$profiles=Get-Content -LiteralPath "$PSScriptRoot\profiles.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$session=[Diagnostics.Process]::GetCurrentProcess().SessionId
Write-Output ("Session: $session; Desktop: " + (Get-DesktopHealth))
$processes=@(Get-Process | Where-Object { $_.SessionId -eq $session -and $_.ProcessName -in $profiles.$App.processNames -and $_.MainWindowHandle -ne 0 })
$processes | Select-Object ProcessName,Id,SessionId,Path
if ($Snapshot) {
    if ($processes.Count -ne 1) { throw 'Open exactly one main window in this desktop session.' }
    $window=[Windows.Automation.AutomationElement]::FromHandle([IntPtr]$processes[0].MainWindowHandle)
    # Read only. May contain conversation text: keep this local and redact before sharing.
    @(Get-UiSnapshot $window) | Select-Object index,parent,role,name,automationId,ariaRole,enabled,offscreen,value | ConvertTo-Json -Depth 4
}
