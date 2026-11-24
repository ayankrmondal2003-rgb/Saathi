<#
.SYNOPSIS
    One-command Windows PowerShell runner for the isolated Maitri server test suite.
.DESCRIPTION
    1. Checks Docker & PostgreSQL tools availability.
    2. Enforces testing environment safety guards (blocks production/Render hosts).
    3. Initializes an isolated PostgreSQL + PostGIS test cluster.
    4. Applies Alembic migrations through 0010.
    5. Runs all 97 automated feature integration tests.
    6. Produces JUnit XML, JSON metrics, and MAITRI_SERVER_TEST_REPORT.md.
    7. Cleans up isolated infrastructure and exits with appropriate exit code.
#>
[CmdletBinding()]
param(
    [switch]$VerboseOutput,
    [string]$TestFilter
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   MAITRI SERVER INTEGRATION TEST SYSTEM (WINDOWS POWERSHELL)   " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Check Docker availability
Write-Host "[1/6] Checking Docker availability..." -ForegroundColor Yellow
try {
    $dockerVersion = & docker --version 2>&1
    Write-Host "      Docker detected: $dockerVersion" -ForegroundColor Green
} catch {
    Write-Host "      Docker not detected on PATH (will use native isolated PostgreSQL cluster)." -ForegroundColor Gray
}

# 2. Check Python virtual environment
Write-Host "[2/6] Verifying Python virtual environment..." -ForegroundColor Yellow
$PythonExe = Join-Path $ScriptDir ".venv\Scripts\python.exe"
if (-not (Test-Path $PythonExe)) {
    Write-Error "Python virtual environment not found at $PythonExe. Run setup first."
    exit 1
}
Write-Host "      Using Python: $PythonExe" -ForegroundColor Green

# 3. Environment safety guard verification
Write-Host "[3/6] Enforcing testing environment safety guards..." -ForegroundColor Yellow
$env:ENVIRONMENT = "test"
$env:FIREBASE_PROJECT_ID = "isolated-test"
$env:PUBLIC_SOS_BROADCAST_ENABLED = "false"

# 4. Execute test runner script
Write-Host "[4/6] Initializing isolated database and executing test suite..." -ForegroundColor Yellow
$runnerScript = Join-Path $ScriptDir "tests\run_maitri_server_tests.py"

$runnerArgs = @($runnerScript)
if ($TestFilter) {
    $runnerArgs += @("-k", $TestFilter)
}

& $PythonExe @runnerArgs
$testExitCode = $LASTEXITCODE

# 5. Result verification
Write-Host "[5/6] Checking test execution results..." -ForegroundColor Yellow
$reportPath = Join-Path (Split-Path -Parent $ScriptDir) "MAITRI_SERVER_TEST_REPORT.md"
$junitPath = Join-Path $ScriptDir "test-reports\junit.xml"
$jsonPath = Join-Path $ScriptDir "test-reports\report.json"

if ($testExitCode -eq 0) {
    Write-Host "=================================================================" -ForegroundColor Green
    Write-Host "                ALL MAITRI SERVER TESTS PASSED!                 " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Green
    Write-Host "Reports generated:" -ForegroundColor Cyan
    Write-Host " - Summary Report: $reportPath" -ForegroundColor White
    Write-Host " - JUnit XML:      $junitPath" -ForegroundColor White
    Write-Host " - JSON Metrics:   $jsonPath" -ForegroundColor White
    exit 0
} else {
    Write-Host "=================================================================" -ForegroundColor Red
    Write-Host "                MAITRI SERVER TESTS FAILED!                     " -ForegroundColor Red
    Write-Host "=================================================================" -ForegroundColor Red
    Write-Host "Review failure logs and report at: $reportPath" -ForegroundColor Yellow
    exit $testExitCode
}
