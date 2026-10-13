# Read-only, request-bound recovery of the original WorkBuddy reply formatting.
function Get-WorkBuddyStoredReply {
    param(
        [Parameter(Mandatory=$true)][string]$Prompt,
        [string[]]$Roots,
        [DateTimeOffset]$Now=[DateTimeOffset]::UtcNow
    )
    $markerPattern='\[Agent Control request [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\]'
    $markers=[regex]::Matches($Prompt,$markerPattern)
    if ($markers.Count -ne 1) { return $null }
    $token=$markers[0].Value
    $keyPattern='[\s\uFEFF\u200B\u200C\u200D\u2060]'
    $wanted=[regex]::Replace($Prompt,$keyPattern,'')
    if (!$Roots) {
        $profileDirectory=[Environment]::GetFolderPath('UserProfile')
        $Roots=@((Join-Path $profileDirectory '.workbuddy/projects'),(Join-Path $profileDirectory '.codebuddy/projects'))
    }
    # A damaged, growing, or ambiguous log is never authority for a different reply.
    try {
        $files=@(); $seenFiles=@{}; $byteBudget=20000000L; $enumeratedCount=0
        foreach ($root in $Roots) {
            if (!(Test-Path -LiteralPath $root -PathType Container)) { continue }
            # Bound old metadata too; Select stops the provider before it builds an unlimited array.
            $candidates=@(Get-ChildItem -LiteralPath $root -Filter '*.jsonl' -Recurse -File -ErrorAction Stop | Select-Object -First (6001-$enumeratedCount))
            $enumeratedCount+=$candidates.Count
            if ($enumeratedCount -gt 6000) { return $null }
            foreach ($file in $candidates) {
                if ($file.LastWriteTimeUtc -lt $Now.UtcDateTime.AddMinutes(-20)) { continue }
                if ($seenFiles.ContainsKey($file.FullName)) { continue }
                $seenFiles[$file.FullName]=$true; $files+=,$file
                if ($files.Count -gt 6000) { return $null }
                $byteBudget-=$file.Length
                if ($file.Length -gt 20000000 -or $byteBudget -lt 0) { return $null }
            }
        }
        $rows=New-Object 'Collections.Generic.List[object]'
        $readBytes=0L
        foreach ($file in $files) {
            $stream=$null; $memory=$null
            try {
                $stream=[IO.File]::Open($file.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
                $memory=New-Object IO.MemoryStream
                $buffer=New-Object byte[] 8192
                while (($count=$stream.Read($buffer,0,$buffer.Length)) -gt 0) {
                    $readBytes+=$count
                    if ($readBytes -gt 20000000) { return $null }
                    $memory.Write($buffer,0,$count)
                }
                $encoding=New-Object Text.UTF8Encoding($false,$true)
                $source=$encoding.GetString($memory.ToArray()).TrimStart([char]0xFEFF)
            } finally {
                if ($null -ne $stream) { $stream.Dispose() }
                if ($null -ne $memory) { $memory.Dispose() }
            }
            $order=0
            foreach ($line in ($source -split "`n")) {
                $order++
                if (!$line.Trim()) { continue }
                $record=$line | ConvertFrom-Json -ErrorAction Stop
                if ($record.type -ne 'message' -and $record.type -ne 'function_call' -and $record.type -ne 'function_call_result') { continue }
                $parts=@($record.content)
                $textParts=@($parts | Where-Object { $_.type -eq 'text' -or $_.type -eq 'input_text' -or $_.type -eq 'output_text' } | ForEach-Object { [string]$_.text })
                $rows.Add([pscustomobject]@{
                    file=$file.FullName; session=[string]$record.sessionId; id=[string]$record.id; order=$order
                    role=$(if ($record.type -eq 'message') { [string]$record.role } elseif ($record.type -eq 'function_call') { 'tool_call' } else { 'tool_result' })
                    parent=[string]$record.parentId; request=[string]$record.providerData.conversationRequestId
                    callId=[string]$record.callId; complete=($record.status -eq 'completed')
                    toolComplete=($record.type -eq 'function_call_result' -and $record.status -in @('completed','failed','cancelled'))
                    inputTexts=@($parts | Where-Object { $_.type -eq 'input_text' } | ForEach-Object { [string]$_.text })
                    imageNames=@($parts | Where-Object { $_.type -eq 'image_blob_ref' } | ForEach-Object { [string]$_.original_filename })
                    text=($textParts -join "`n`n")
                })
            }
        }
        # Count every occurrence across files before selecting any completed response.
        $markerUsers=@($rows | Where-Object { $_.role -eq 'user' -and $_.text.Contains($token) })
        if ($markerUsers.Count -ne 1) { return $null }
        $users=@($markerUsers | Where-Object {
            $candidateMarkers=[regex]::Matches($_.text,$markerPattern)
            if ($candidateMarkers.Count -ne 1 -or $candidateMarkers[0].Value -cne $token) { return $false }
            $value=[regex]::Replace($_.text,$keyPattern,'')
            if ($value -ceq $wanted) { return $true }
            # WorkBuddy embeds the task in input_text; system reminders are never task authority.
            $queryParts=@($_.inputTexts | Where-Object { $_ -cmatch '</?user_query\b' })
            if ($queryParts.Count -ne 1) { return $false }
            $container=[string]$queryParts[0]
            if ([regex]::Matches($container,'</?user_query\b').Count -ne 2) { return $false }
            $queryMatch=[regex]::Match($container,'<user_query>([\s\S]*?)</user_query>')
            if (!$queryMatch.Success) { return $false }
            $query=[regex]::Replace($queryMatch.Groups[1].Value,$keyPattern,'')
            if (!$query.StartsWith($wanted,[StringComparison]::Ordinal)) { return $false }
            $suffix=$query.Substring($wanted.Length)
            if (!$suffix) { return $true }
            # Only the observed image references, bound to this record's actual blobs, may follow it.
            if (!$_.imageNames.Count) { return $false }
            $expectedSuffix=''; $imageIndex=0
            foreach ($imageName in $_.imageNames) {
                $imageIndex++
                if (!$imageName -or $imageName -match '[\x00-\x1F<>]') { return $false }
                $expectedSuffix+='@image#'+$imageIndex+':'+[regex]::Replace($imageName,$keyPattern,'')
            }
            return $suffix -ceq $expectedSuffix
        })
        if ($users.Count -ne 1) { return $null }
        $user=$users[0]
        if (!$user.session -or !$user.id) { return $null }
        $sessionRows=@($rows | Where-Object { $_.file -ceq $user.file -and $_.session -ceq $user.session -and $_.order -gt $user.order })
        if (@($sessionRows | Where-Object { $_.role -eq 'user' }).Count -gt 0) { return $null }
        $answersById=@{}; $unboundAnswerIds=@{}
        foreach ($row in $sessionRows | Where-Object { $_.role -eq 'assistant' }) {
            if ($row.parent -cne $user.id -and !($user.request -and $row.request -ceq $user.request)) {
                if ($row.id -and $answersById.ContainsKey($row.id)) { return $null }
                if ($row.id) { $unboundAnswerIds[$row.id]=$true }
                continue
            }
            if (!$row.id -or $row.id -ceq $user.id) { return $null }
            if ($unboundAnswerIds.ContainsKey($row.id)) { return $null }
            # If both identities exist, a contradictory request identity is unsafe.
            if ($user.request -and $row.request -and $row.request -cne $user.request) { return $null }
            $answersById[$row.id]=$row
        }
        $answers=@($answersById.Values | Sort-Object order)
        if (!$answers.Count -or @($answers | Where-Object { !$_.complete -or !$_.text }).Count -gt 0) { return $null }
        $calls=@{}; $results=@{}; $lastToolOrder=-1
        foreach ($row in $sessionRows | Where-Object { $_.role -in @('tool_call','tool_result') }) {
            if (!(($user.request -and $row.request -ceq $user.request) -or $answersById.ContainsKey($row.parent))) { continue }
            if (!$row.callId) { return $null }
            if ($user.request -and $row.request -and $row.request -cne $user.request) { return $null }
            $lastToolOrder=[Math]::Max($lastToolOrder,$row.order)
            if ($row.role -eq 'tool_call') { $calls[$row.callId]=$row.order }
            elseif ($row.toolComplete) { $results[$row.callId]=$row.order }
        }
        foreach ($callId in $calls.Keys) {
            if (!$results.ContainsKey($callId) -or $results[$callId] -lt $calls[$callId]) { return $null }
        }
        if ($answers[-1].order -le $lastToolOrder) { return $null }
        return [pscustomobject]@{
            text=(@($answers | ForEach-Object { $_.text }) -join "`n`n")
            binding=[pscustomobject]@{session=$user.session;id=$user.id;file=$user.file;request=$user.request;answerIds=@($answers | ForEach-Object { $_.id })}
        }
    } catch { return $null }
}
