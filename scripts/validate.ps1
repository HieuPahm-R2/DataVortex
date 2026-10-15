param([switch]$Integration)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $pipelinePython = Join-Path (Get-Location).Path '.venv/Scripts/python.exe'
    if (!(Test-Path -LiteralPath $pipelinePython)) {
        throw 'Create .venv and install requirements.txt first (README.md).'
    }
    & rtk proxy $pipelinePython -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Pipeline tests failed' }
    & rtk proxy $pipelinePython -m scripts.validate_dataform
    if ($LASTEXITCODE -ne 0) { throw 'Dataform validation failed' }
    if ($Integration) {
        & rtk proxy ./mvnw.cmd -B -ntp verify
    } else {
        & rtk proxy ./mvnw.cmd -B -ntp package
    }
    if ($LASTEXITCODE -ne 0) { throw 'Backend validation failed' }
    & rtk proxy git diff --check
    if ($LASTEXITCODE -ne 0) { throw 'Whitespace validation failed' }
} finally {
    Pop-Location
}
