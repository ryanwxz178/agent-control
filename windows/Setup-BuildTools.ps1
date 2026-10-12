param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot
$tools = Join-Path $repo '.toolchain'
New-Item -ItemType Directory -Force -Path $tools | Out-Null
function Download-Verified($Url, $Target, $Hash, $Algorithm) {
    if (!(Test-Path -LiteralPath $Target)) {
        & curl.exe -fL --retry 5 --retry-all-errors --connect-timeout 30 --max-time 900 -C - -o ($Target+'.download') $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
        Move-Item -LiteralPath ($Target+'.download') -Destination $Target
    }
    if ((Get-FileHash -LiteralPath $Target -Algorithm $Algorithm).Hash -ine $Hash) { throw "Checksum mismatch: $Target" }
}
$jdk = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows'
$package = $jdk[0].binary.package
Download-Verified $package.link "$tools/jdk17.zip" $package.checksum SHA256
if (!(Test-Path "$tools/jdk-17")) {
    Expand-Archive -LiteralPath "$tools/jdk17.zip" -DestinationPath "$tools/jdk-unpack" -Force
    $directory = Get-ChildItem "$tools/jdk-unpack" -Directory | Select-Object -First 1
    Move-Item -LiteralPath $directory.FullName -Destination "$tools/jdk-17"
}
Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-1.xml' -OutFile "$tools/android-repository.xml"
[xml]$repository = Get-Content "$tools/android-repository.xml"
$packages = $repository.SelectNodes('//*[local-name()="remotePackage"]')
foreach ($spec in @(@('build-tools;35.0.0','build-tools/35.0.0'),@('platforms;android-35','platforms/android-35'))) {
    $entry = $packages | Where-Object { $_.path -eq $spec[0] -and (!$_.SelectSingleNode('*[local-name()="type-details"]/*[local-name()="extension-level"]')) } | Sort-Object { [int]$_.revision.major } -Descending | Select-Object -First 1
    $archive = $entry.archives.archive | Where-Object { !$_.'host-os' -or $_.'host-os' -eq 'windows' } | Select-Object -First 1
    $zipPath = Join-Path $tools ([string]$archive.complete.url)
    Download-Verified ('https://dl.google.com/android/repository/'+$archive.complete.url) $zipPath ([string]$archive.complete.checksum) SHA1
    $destination = Join-Path "$tools/android-sdk" $spec[1]
    if (!(Test-Path $destination)) {
        $unpack = Join-Path $tools ($spec[1].Replace('/','-')+'-unpack')
        Expand-Archive -LiteralPath $zipPath -DestinationPath $unpack -Force
        $directory = Get-ChildItem $unpack -Directory | Select-Object -First 1
        New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
        Move-Item -LiteralPath $directory.FullName -Destination $destination
    }
}
$libs = Join-Path $repo 'android-app/libs'
New-Item -ItemType Directory -Force -Path $libs | Out-Null
$dependencyPaths = @{
    'jsch-0.2.26.jar'='com/github/mwiede/jsch/0.2.26/jsch-0.2.26.jar'
    'bcprov-jdk18on-1.77.jar'='org/bouncycastle/bcprov-jdk18on/1.77/bcprov-jdk18on-1.77.jar'
    'json-20240303.jar'='org/json/json/20240303/json-20240303.jar'
}
foreach ($line in Get-Content "$repo/android-app/dependencies.sha256") {
    if ($line -match '^([a-f0-9]{64})\s+libs/(.+)$') {
        Download-Verified ('https://repo.maven.apache.org/maven2/'+$dependencyPaths[$Matches[2]]) "$libs/$($Matches[2])" $Matches[1] SHA256
    }
}
Write-Output "Build tools and verified Java dependencies installed in $tools"
