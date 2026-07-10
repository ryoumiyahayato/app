param(
    [int]$Port = 18443,
    [int]$MaxTotalConnections = 12,
    [int]$HeartbeatIntervalMs = 200,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverRoot = Join-Path $projectRoot 'server'

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath exited with code $LASTEXITCODE"
    }
}

function Invoke-GradleVerification {
    $gradleOutput = & .\gradlew.bat `
        clean `
        lintDebug `
        testDebugUnitTest `
        assembleDebug `
        assembleRelease 2>&1 | Tee-Object -Variable capturedOutput

    $gradleOutput | Write-Output
    if (
        $LASTEXITCODE -ne 0 -or
        -not ($capturedOutput -match 'BUILD SUCCESSFUL')
    ) {
        throw 'Gradle verification did not complete successfully.'
    }
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
    'ROOM_ID'
)
$previousEnvironment = @{}
foreach ($key in $environmentKeys) {
    $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}

$relayOut = Join-Path $env:TEMP 'electronic-muyu-verify-relay-out.log'
$relayErr = Join-Path $env:TEMP 'electronic-muyu-verify-relay-err.log'
$relayProcess = $null

try {
    Push-Location $serverRoot
    try {
        Invoke-Native npm.cmd ci
        Invoke-Native npm.cmd audit --omit=dev --audit-level=low

        $env:PORT = $Port.ToString()
        $env:WS_URL = "ws://127.0.0.1:$Port"
        $env:MAX_TOTAL_CONNECTIONS = $MaxTotalConnections.ToString()
        $env:HEARTBEAT_INTERVAL_MS = $HeartbeatIntervalMs.ToString()

        Remove-Item -LiteralPath $relayOut, $relayErr -Force -ErrorAction SilentlyContinue
        $relayProcess = Start-Process `
            -FilePath (Get-Command node).Source `
            -ArgumentList 'index.js' `
            -WorkingDirectory $serverRoot `
            -RedirectStandardOutput $relayOut `
            -RedirectStandardError $relayErr `
            -WindowStyle Hidden `
            -PassThru

        $healthy = $false
        for ($attempt = 0; $attempt -lt 50; $attempt++) {
            if ($relayProcess.HasExited) {
                throw "relay exited before health check (code $($relayProcess.ExitCode))"
            }
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 1
                if ($health.ok -eq $true) {
                    $healthy = $true
                    break
                }
            } catch {
                Start-Sleep -Milliseconds 100
            }
        }
        if (-not $healthy) {
            throw 'relay did not become healthy'
        }

        Invoke-Native npm.cmd test
        $env:ROOM_ID = 'verify-send'
        Invoke-Native npm.cmd run send
    } finally {
        if ($relayProcess -and -not $relayProcess.HasExited) {
            Stop-Process -Id $relayProcess.Id -Force
            $relayProcess.WaitForExit()
        }
        Pop-Location
    }

    Push-Location $projectRoot
    try {
        & .\gradlew.bat --stop | Out-Null
        Start-Sleep -Milliseconds 500
        Invoke-GradleVerification

        $requiredOutputs = @(
            'app\build\outputs\apk\debug\app-debug.apk',
            'app\build\outputs\apk\release\app-release-unsigned.apk',
            'app\build\outputs\mapping\release\mapping.txt'
        )
        foreach ($output in $requiredOutputs) {
            if (-not (Test-Path (Join-Path $projectRoot $output))) {
                throw "missing expected output: $output"
            }
        }
    } finally {
        Pop-Location
    }

    Write-Output 'Local verification completed successfully.'
} catch {
    if (Test-Path $relayOut) {
        Write-Output '--- relay stdout ---'
        Get-Content $relayOut | Select-Object -Last 100
    }
    if (Test-Path $relayErr) {
        Write-Output '--- relay stderr ---'
        Get-Content $relayErr | Select-Object -Last 100
    }
    throw
} finally {
    foreach ($key in $environmentKeys) {
        [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process')
    }
}
