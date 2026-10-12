param([switch]$Test)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot
$java = Join-Path $repo '.toolchain/jdk-17'
$sdk = Join-Path $repo '.toolchain/android-sdk'
$bt = Join-Path $sdk 'build-tools/35.0.0'
$androidJar = Join-Path $sdk 'platforms/android-35/android.jar'
$env:JAVA_HOME = $java
$env:Path = "$java/bin;" + $env:Path
function Run($Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable exited with $LASTEXITCODE" }
}
function Sources($Directory) { @(Get-ChildItem -LiteralPath $Directory -Recurse -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }) }
$app = Join-Path $repo 'android-app'
$source = Join-Path $app 'app/src/main/java/dev/orca/pocket'
$libs = @('json-20240303.jar','jsch-0.2.26.jar','bcprov-jdk18on-1.77.jar') | ForEach-Object { Join-Path "$app/libs" $_ }
$cp = $libs -join ';'
$engineNames = @('Agent','Inventory','EventDecoder','SshTransport','TaskRouter','Interactions','Attachments','RemoteImages','CodexSession','AcpSession','PocketEngine','PersistentClient')
$engineSources = $engineNames | ForEach-Object { '"'+(Join-Path $source ($_.ToString()+'.java')).Replace('\','/')+'"' }
$serviceBuild = Join-Path $repo 'desktop-service/build'
New-Item -ItemType Directory -Force -Path "$serviceBuild/classes/assets" | Out-Null
@($engineSources) + @(Sources "$repo/desktop-service/src") | Set-Content "$serviceBuild/sources.txt" -Encoding utf8
Run "$java/bin/javac.exe" @('--release','17','-encoding','UTF-8','-cp',$cp,'-d',"$serviceBuild/classes","@$serviceBuild/sources.txt")
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($library in $libs) {
    $archive = [IO.Compression.ZipFile]::OpenRead($library)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.StartsWith('META-INF/') -or !$entry.Name) { continue }
            $target = Join-Path "$serviceBuild/classes" $entry.FullName
            New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
        }
    } finally { $archive.Dispose() }
}
Get-ChildItem "$app/app/src/main/assets" -File | Where-Object { $_.Extension -in @('.sh','.ps1','.js') -and $_.Name -notin @('app.js','muse-chat.js') } | Copy-Item -Destination "$serviceBuild/classes/assets" -Force
New-Item -ItemType Directory -Force -Path "$serviceBuild/classes/browser" | Out-Null
foreach ($name in @('src','package.json','package-lock.json','setup.mjs','LICENSE-OpenMuse')) { Copy-Item -LiteralPath "$repo/desktop-service/browser/$name" -Destination "$serviceBuild/classes/browser" -Recurse -Force }
Run "$java/bin/jar.exe" @('--create','--file',"$app/app/src/main/assets/agent-control-service.jar",'-C',"$serviceBuild/classes",'.')
if ($Test) {
    New-Item -ItemType Directory -Force -Path "$serviceBuild/test-classes" | Out-Null
    Run "$java/bin/javac.exe" @('-encoding','UTF-8','-cp',"$serviceBuild/classes",'-d',"$serviceBuild/test-classes","$repo/desktop-service/tests/WindowsTransportTest.java")
    Run "$java/bin/java.exe" @('-ea','-cp',"$serviceBuild/test-classes;$serviceBuild/classes",'dev.orca.pocket.WindowsTransportTest')
    New-Item -ItemType Directory -Force -Path "$app/build/test-classes" | Out-Null
    @($engineSources) + @('CoreTest','InteractionTest','DesktopContinuityTest' | ForEach-Object { '"'+(Join-Path "$app/tests" ($_.ToString()+'.java')).Replace('\','/')+'"' }) | Set-Content "$app/build/test-sources.txt" -Encoding utf8
    Run "$java/bin/javac.exe" @('-encoding','UTF-8','-cp',$cp,'-d',"$app/build/test-classes","@$app/build/test-sources.txt")
    # CoreTest requires macOS /bin/zsh even for its offline suite.
    foreach ($name in @('InteractionTest','DesktopContinuityTest')) { Run "$java/bin/java.exe" @('-ea','-cp',"$app/build/test-classes;$cp","dev.orca.pocket.$name",$repo) }
}
$build = Join-Path $app 'build'
New-Item -ItemType Directory -Force -Path "$build/classes","$build/dex","$build/generated","$app/.signing","$repo/dist" | Out-Null
Run "$bt/aapt2.exe" @('compile','--dir',"$app/app/src/main/res",'-o',"$build/resources.zip")
Run "$bt/aapt2.exe" @('link','-o',"$build/base.apk",'-I',$androidJar,'--manifest',"$app/app/src/main/AndroidManifest.xml",'--java',"$build/generated",'-A',"$app/app/src/main/assets","$build/resources.zip")
# Windows aapt2 emits nested asset names with backslashes. Android AssetManager
# and WebView resolve forward-slash ZIP paths, so normalize before signing.
$archive = [IO.Compression.ZipFile]::Open("$build/base.apk",[IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($entry in @($archive.Entries | Where-Object { $_.FullName.Contains('\') })) {
        $normalized = $entry.FullName.Replace('\','/')
        if ($archive.GetEntry($normalized)) { throw "Duplicate normalized APK entry: $normalized" }
        $buffer = [IO.MemoryStream]::new()
        $input = $entry.Open()
        try { $input.CopyTo($buffer) } finally { $input.Dispose() }
        $entry.Delete()
        $replacement = $archive.CreateEntry($normalized)
        $output = $replacement.Open()
        try { $buffer.Position=0; $buffer.CopyTo($output) } finally { $output.Dispose(); $buffer.Dispose() }
    }
} finally { $archive.Dispose() }
@(Sources "$app/app/src/main/java") + @(Sources "$build/generated") | Set-Content "$build/sources.txt" -Encoding utf8
Run "$java/bin/javac.exe" @('-encoding','UTF-8','--release','8','-cp',"$androidJar;$cp",'-d',"$build/classes","@$build/sources.txt")
Run "$java/bin/jar.exe" @('cf',"$build/classes.jar",'-C',"$build/classes",'.')
Run "$bt/d8.bat" @('--release','--min-api','26','--lib',$androidJar,'--output',"$build/dex","$build/classes.jar",$libs[1],$libs[2])
Copy-Item "$build/base.apk" "$build/unsigned.apk" -Force
$archive = [IO.Compression.ZipFile]::Open("$build/unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try { foreach ($dex in Get-ChildItem "$build/dex" -Filter 'classes*.dex') { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$dex.FullName,$dex.Name) | Out-Null } } finally { $archive.Dispose() }
Run "$bt/zipalign.exe" @('-f','-p','4',"$build/unsigned.apk","$build/aligned.apk")
$signing = Join-Path $app '.signing'
if (!(Test-Path "$signing/local.p12")) {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [IO.File]::WriteAllText("$signing/password",[Convert]::ToBase64String($bytes))
    & icacls.exe $signing /inheritance:r /grant:r "${env:USERNAME}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot protect signing directory' }
    Run "$java/bin/keytool.exe" @('-genkeypair','-keystore',"$signing/local.p12",'-storepass:file',"$signing/password",'-alias','pocket','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Agent Control Local Build')
}
[xml]$manifest = Get-Content "$app/app/src/main/AndroidManifest.xml"
$version = $manifest.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
$apk = Join-Path $repo "dist/Agent-Control-$version.apk"
Run "$bt/apksigner.bat" @('sign','--ks',"$signing/local.p12",'--ks-pass',"file:$signing/password",'--out',$apk,"$build/aligned.apk")
Run "$bt/apksigner.bat" @('verify','--verbose',$apk)
Run "$bt/zipalign.exe" @('-c','4',$apk)
$archive = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    if (@($archive.Entries | Where-Object { $_.FullName.Contains('\') }).Count) { throw 'APK contains invalid backslash paths' }
    $assetDirectory = (Resolve-Path "$app/app/src/main/assets").Path
    $verifiedAssets = 0
    foreach ($file in Get-ChildItem -LiteralPath $assetDirectory -Recurse -File) {
        $relative = $file.FullName.Substring($assetDirectory.Length+1).Replace('\','/')
        $entry = $archive.GetEntry('assets/'+$relative)
        if (!$entry) { throw "APK asset is missing: $relative" }
        $input = $entry.Open()
        $digest = [Security.Cryptography.SHA256]::Create()
        try { $actual = [BitConverter]::ToString($digest.ComputeHash($input)).Replace('-','') } finally { $input.Dispose(); $digest.Dispose() }
        if ($actual -ine (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) { throw "APK asset differs from source: $relative" }
        $verifiedAssets++
    }
    Write-Output "Verified $verifiedAssets packaged assets, including all SVG icons, against source hashes"
} finally { $archive.Dispose() }
[IO.File]::WriteAllText("$repo/dist/SHA256SUMS-$version.txt",(Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()+"  Agent-Control-$version.apk`n")
Write-Output "APK: $apk"
