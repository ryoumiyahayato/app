$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    $python = Get-Command py -ErrorAction SilentlyContinue
}
if (-not $python) {
    throw "Python 3 is required. No credentials are needed by this probe."
}

if ($python.Name -eq "py.exe" -or $python.Name -eq "py") {
    & $python.Source -3 "$ScriptDir/probe.py" live --output-dir "$ScriptDir"
} else {
    & $python.Source "$ScriptDir/probe.py" live --output-dir "$ScriptDir"
}
exit $LASTEXITCODE
