<#
.SYNOPSIS
    Builds the Go sidecar.

.DESCRIPTION
    The Java side looks for the executable under bin/, named
    "plumb-go-sidecar" ("plumb-go-sidecar.exe" on Windows). This script
    produces exactly that.

    The Go build cache and the module cache are placed OUTSIDE the
    repository, under the temporary directory. A build cache inside the
    working tree is a large pile of generated files that has no business in
    version control, and .gitignore is only a safety net for the case where
    somebody builds by hand.

    The build needs a Go toolchain. It is taken from, in order:
      - the GO environment variable,
      - the "go" on the PATH,
      - the GOROOT environment variable,
      - the toolchains this machine is known to have.

.EXAMPLE
    .\build.ps1
    .\build.ps1 -GoToolchain "D:\development\go1.26.10\go"
#>
[CmdletBinding()]
param(
    # The Go toolchain to build with. Empty means "find one".
    [string] $GoToolchain = $env:GO,

    # The configuration to build.
    [ValidateSet("release", "debug")]
    [string] $Configuration = "release"
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$output = Join-Path $here "bin"

# $IsWindows only exists in PowerShell 6 and later, so the platform is
# detected from the environment instead: the executable's NAME is what the
# Java side looks for, and on Windows it carries the .exe suffix.
$isWindows = $env:OS -eq "Windows_NT"
$suffix = if ($isWindows) { ".exe" } else { "" }
$executable = Join-Path $output ("plumb-go-sidecar" + $suffix)

# The cache lives outside the repository.
$cacheRoot = Join-Path ([System.IO.Path]::GetTempPath()) "plumb-go-sidecar-build"
$env:GOCACHE = Join-Path $cacheRoot "cache"
$env:GOMODCACHE = Join-Path $cacheRoot "modules"
New-Item -ItemType Directory -Force -Path $env:GOCACHE, $env:GOMODCACHE, $output | Out-Null

function Resolve-GoToolchain {
    param([string] $Candidate)

    if ($Candidate) {
        $exe = Join-Path $Candidate "bin\go.exe"
        if (Test-Path $exe) { return $Candidate }
        if (Test-Path $Candidate) { return $Candidate }
        Write-Error "the Go toolchain '$Candidate' has no bin\go.exe"
    }

    $onPath = Get-Command go -ErrorAction SilentlyContinue
    if ($onPath) {
        $root = Split-Path -Parent (Split-Path -Parent $onPath.Source)
        return $root
    }

    if ($env:GOROOT) { return $env:GOROOT }

    $known = @(
        "D:\development\go1.26.10\go",
        "C:\Program Files\Go",
        "C:\Go",
        "/usr/local/go",
        "/usr/lib/go"
    )
    foreach ($candidate in $known) {
        if (Test-Path (Join-Path $candidate "bin\go.exe")) { return $candidate }
        if (Test-Path (Join-Path $candidate "bin/go")) { return $candidate }
    }

    Write-Error "no Go toolchain was found: set GO, or put go on the PATH"
}

$root = Resolve-GoToolchain -Candidate $GoToolchain
$env:GOROOT = $root
    $go = Join-Path $root "bin\go.exe"
    if (-not (Test-Path $go)) { $go = Join-Path $root "bin/go" }

Write-Host "building with $go (GOROOT=$root)"

Push-Location $here
try {
    & $go version
    if ($LASTEXITCODE -ne 0) { throw "the Go toolchain did not run" }

    # The module has no dependencies outside the standard library, so the
    # build never needs the network.
    $env:GOFLAGS = "-mod=mod"
    $env:GOPROXY = "off"

    & $go build -trimpath -o $executable .
    if ($LASTEXITCODE -ne 0) { throw "the build failed" }

    & $go vet ./...
    if ($LASTEXITCODE -ne 0) { throw "go vet failed" }
}
finally {
    Pop-Location
}

Write-Host "built $executable"
