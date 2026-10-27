$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$python = Join-Path $root ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python)) {
    Write-Host "Creating local Python environment..."
    if (Get-Command py -ErrorAction SilentlyContinue) {
        py -m venv .venv
    } else {
        python -m venv .venv
    }
}

& $python -c "import flask, requests, bs4" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Installing local dependencies..."
    & $python -m pip install --disable-pip-version-check -r requirements.txt
}

& $python app.py
