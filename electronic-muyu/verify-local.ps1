param(
    [ValidateRange(1, 65534)][int]$Port = 18443,
    [ValidateRange(3, 1000)][int]$MaxTotalConnections = 12,
    [ValidateRange(100, 300000)][int]$HeartbeatIntervalMs = 200,
    [ValidateRange(1, 1000)][int]$MaxMessagesPerWindow = 60,
    [string]$RelayToken = 'verify-local-token',
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverRoot = Join-Path $projectRoot 'server'
$verificationStartedAtUtc = [DateTime]::UtcNow
$relayLogs = [System.Collections.Generic.List[object]]::new()

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$FilePath exited with code $exitCode"
    }
}

function Assert-CleanWorkingTree {
    $status = @(& git -C $projectRoot status --porcelain=v1 --untracked-files=all)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "git status exited with code $exitCode"
    }
    if ($status.Count -gt 0) {
        throw "working tree is not clean:`n$($status -join [Environment]::NewLine)"
    }
}

function Test-TcpPortOpen {
    param([Parameter(Mandatory = $true)][int]$TargetPort)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $TargetPort)
        if (-not $task.Wait(250)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-TcpPortClosed {
    param([Parameter(Mandatory = $true)][int]$TargetPort)

    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        if (-not (Test-TcpPortOpen -TargetPort $TargetPort)) {
            return
        }
        Start-Sleep -Milliseconds 100
    }
    throw "relay port $TargetPort remained open after process exit"
}

function Wait-RelayHealthy {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)][int]$TargetPort
    )

    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($Process.HasExited) {
            throw "relay exited before health check (code $($Process.ExitCode))"
        }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$TargetPort/health" -TimeoutSec 1
            if ($health.ok -eq $true -and $health.service -eq 'electronic-muyu-relay') {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    throw "relay on port $TargetPort did not become healthy"
}

function Show-RelayLogTail {
    param([Parameter(Mandatory = $true)]$LogRecord)

    Write-Output "--- relay $($LogRecord.Phase) stdout ---"
    if (Test-Path $LogRecord.Out) {
        Get-Content $LogRecord.Out | Select-Object -Last 100
    }
    Write-Output "--- relay $($LogRecord.Phase) stderr ---"
    if (Test-Path $LogRecord.Err) {
        Get-Content $LogRecord.Err | Select-Object -Last 100
    }
}

function Invoke-RelayVerificationPhase {
    param(
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][int]$TargetPort,
        [AllowEmptyString()][string]$Token
    )

    if (Test-TcpPortOpen -TargetPort $TargetPort) {
        throw "port $TargetPort is already in use before phase $Phase"
    }

    $logSuffix = "electronic-muyu-$Phase-$PID-$([Guid]::NewGuid().ToString('N'))"
    $relayOut = Join-Path $env:TEMP "$logSuffix-out.log"
    $relayErr = Join-Path $env:TEMP "$logSuffix-err.log"
    $logRecord = [pscustomobject]@{ Phase = $Phase; Out = $relayOut; Err = $relayErr }
    $relayLogs.Add($logRecord)
    $relayProcess = $null

    $env:PORT = $TargetPort.ToString()
    $env:WS_URL = "ws://127.0.0.1:$TargetPort"
    $env:MAX_TOTAL_CONNECTIONS = $MaxTotalConnections.ToString()
    $env:HEARTBEAT_INTERVAL_MS = $HeartbeatIntervalMs.ToString()
    $env:MAX_MESSAGES_PER_WINDOW = $MaxMessagesPerWindow.ToString()
    $env:ROOM_ID = "verify-$Phase-send"
    $env:DEVICE_ID = "verify-$Phase-client"
    if ([string]::IsNullOrEmpty($Token)) {
        Remove-Item Env:RELAY_TOKEN -ErrorAction SilentlyContinue
    } else {
        $env:RELAY_TOKEN = $Token
    }

    try {
        $relayProcess = Start-Process `
            -FilePath (Get-Command node).Source `
            -ArgumentList 'index.js' `
            -WorkingDirectory $serverRoot `
            -RedirectStandardOutput $relayOut `
            -RedirectStandardError $relayErr `
            -WindowStyle Hidden `
            -PassThru

        Wait-RelayHealthy -Process $relayProcess -TargetPort $TargetPort
        Invoke-Native npm.cmd test
        Invoke-Native npm.cmd run send
    } catch {
        Show-RelayLogTail -LogRecord $logRecord
        throw
    } finally {
        if ($relayProcess -and -not $relayProcess.HasExited) {
            Stop-Process -Id $relayProcess.Id -Force
            $relayProcess.WaitForExit()
        }
        Wait-TcpPortClosed -TargetPort $TargetPort
    }
}

function Invoke-GradleVerification {
    $capturedOutput = @(
        & .\gradlew.bat `
            --no-daemon `
            clean `
            lintDebug `
            testDebugUnitTest `
            assembleDebug `
            assembleRelease 2>&1
    )
    $gradleExitCode = $LASTEXITCODE
    $capturedOutput | Write-Output

    if (
        $gradleExitCode -ne 0 -or
        -not ($capturedOutput -match 'BUILD SUCCESSFUL')
    ) {
        throw "Gradle verification failed with code $gradleExitCode"
    }
}

function Assert-UnitTestResults {
    $resultRoot = Join-Path $projectRoot 'app\build\test-results\testDebugUnitTest'
    $resultFiles = @(Get-ChildItem -Path $resultRoot -Filter 'TEST-*.xml' -File -ErrorAction Stop)
    if ($resultFiles.Count -eq 0) {
        throw 'no JVM unit-test XML reports were produced'
    }

    $tests = 0
    $failures = 0
    $errors = 0
    foreach ($file in $resultFiles) {
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        $tests += [int]$report.testsuite.tests
        $failures += [int]$report.testsuite.failures
        $errors += [int]$report.testsuite.errors
    }
    if ($tests -lt 1 -or $failures -ne 0 -or $errors -ne 0) {
        throw "JVM tests=$tests failures=$failures errors=$errors"
    }
    Write-Output "JVM unit tests passed: $tests"
}

function Find-MergedManifest {
    param([Parameter(Mandatory = $true)][ValidateSet('debug', 'release')][string]$Variant)

    $intermediates = Join-Path $projectRoot 'app\build\intermediates'
    $candidates = @(
        Get-ChildItem -Path $intermediates -Recurse -Filter 'AndroidManifest.xml' -File |
            Where-Object {
                $_.FullName -match '[\\/](merged_manifest|merged_manifests)[\\/]' -and
                $_.FullName -match "[\\/]$Variant[\\/]"
            } |
            Sort-Object LastWriteTimeUtc -Descending
    )
    if ($candidates.Count -eq 0) {
        throw "merged $Variant AndroidManifest.xml was not found"
    }
    return $candidates[0]
}

function Assert-CleartextPolicy {
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    foreach ($variant in @('debug', 'release')) {
        $manifestFile = Find-MergedManifest -Variant $variant
        [xml]$manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw
        $actual = $manifest.manifest.application.GetAttribute('usesCleartextTraffic', $androidNamespace)
        $expected = if ($variant -eq 'debug') { 'true' } else { 'false' }
        if ($actual -ne $expected) {
            throw "$variant usesCleartextTraffic=$actual, expected $expected"
        }
        Write-Output "$variant usesCleartextTraffic=$actual"
    }
}

function Assert-BuildOutputs {
    $requiredOutputs = @(
        'app\build\outputs\apk\debug\app-debug.apk',
        'app\build\outputs\apk\release\app-release-unsigned.apk',
        'app\build\outputs\mapping\release\mapping.txt'
    )

    foreach ($relativePath in $requiredOutputs) {
        $fullPath = Join-Path $projectRoot $relativePath
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "missing expected output: $relativePath"
        }
        $item = Get-Item -LiteralPath $fullPath
        if ($item.Length -le 0) {
            throw "empty expected output: $relativePath"
        }
        if ($item.LastWriteTimeUtc -lt $verificationStartedAtUtc.AddSeconds(-2)) {
            throw "stale expected output: $relativePath"
        }
        $hash = Get-FileHash -LiteralPath $fullPath -Algorithm SHA256
        Write-Output "$relativePath SHA256=$($hash.Hash)"
    }
}

function Assert-ReleaseUnsigned {
    $releaseApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
    $sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        throw 'ANDROID_SDK_ROOT or ANDROID_HOME is required to locate apksigner'
    }

    $apksigner = Get-ChildItem -Path (Join-Path $sdkRoot 'build-tools') `
        -Recurse -Filter 'apksigner.bat' -File |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if (-not $apksigner) {
        throw 'apksigner.bat was not found under Android SDK build-tools'
    }

    & $apksigner.FullName verify $releaseApk *> $null
    $signatureExitCode = $LASTEXITCODE
    if ($signatureExitCode -eq 0) {
        throw 'release APK is unexpectedly signed; this project must not silently use a local key'
    }
    Write-Output 'release APK confirmed unsigned'
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $bundledJbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path (Join-Path $bundledJbr 'bin\java.exe')) {
        $JavaHome = $bundledJbr
    } else {
        throw 'JAVA_HOME is not set and Android Studio JBR was not found.'
    }
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"

$environmentKeys = @(
    'PORT',
    'WS_URL',
    'MAX_TOTAL_CONNECTIONS',
    'HEARTBEAT_INTERVAL_MS',
    'MAX_MESSAGES_PER_WINDOW',
    'ROOM_ID',
    'DEVICE_ID',
    'RELAY_TOKEN'
)
$previousEnvironment = @{}
foreach ($key in $environmentKeys) {
    $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}

try {
    Assert-CleanWorkingTree

    $nodeMajorText = (& node -p "process.versions.node.split('.')[0]").Trim()
    if ($LASTEXITCODE -ne 0 -or [int]$nodeMajorText -lt 22) {
        throw "Node.js 22+ is required; detected major=$nodeMajorText"
    }

    Push-Location $serverRoot
    try {
        Invoke-Native npm.cmd ci
        Invoke-Native npm.cmd audit --omit=dev --audit-level=low
        Invoke-RelayVerificationPhase -Phase 'no-auth' -TargetPort $Port -Token ''
        Invoke-RelayVerificationPhase -Phase 'auth' -TargetPort ($Port + 1) -Token $RelayToken
    } finally {
        Pop-Location
    }

    Push-Location $projectRoot
    try {
        & .\gradlew.bat --stop | Out-Null
        Start-Sleep -Milliseconds 750
        Invoke-GradleVerification
        Assert-UnitTestResults
        Assert-CleartextPolicy
        Assert-BuildOutputs
        Assert-ReleaseUnsigned
    } finally {
        Pop-Location
    }

    Assert-CleanWorkingTree
    Write-Output 'Local verification completed successfully.'
} catch {
    foreach ($logRecord in $relayLogs) {
        Show-RelayLogTail -LogRecord $logRecord
    }
    throw
} finally {
    foreach ($key in $environmentKeys) {
        [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process')
    }
}
