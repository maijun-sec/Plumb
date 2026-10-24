<#
.SYNOPSIS
    Format the Java sources with the pinned google-java-format.

.DESCRIPTION
    Thin wrapper around scripts/format.py, which holds the single
    implementation of the formatting rule (the file set, the exclusion
    list, the pinned formatter version). Keeping the logic in one place
    means Windows, Linux and macOS enforce exactly the same thing.

.PARAMETER Check
    Report unformatted files instead of rewriting them. Exits 1 when
    anything is unformatted.

.EXAMPLE
    scripts/format.ps1
    scripts/format.ps1 -Check
#>
[CmdletBinding()]
param(
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    Write-Error 'python not found on PATH'
    exit 2
}

if ($Check) {
    & python 'scripts/format.py' '--check'
} else {
    & python 'scripts/format.py'
}
exit $LASTEXITCODE
