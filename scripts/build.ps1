# =============================================================
# Plumb build script (Windows / PowerShell)
# =============================================================
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Clean,
    [switch]$NoShade,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir '..')).Path
Set-Location $ProjectRoot

if ($Help) {
    Get-Content "$PSCommandPath" -TotalCount 25 | ForEach-Object { Write-Host $_ }
    exit 0
}

# 1. Environment preparation
Write-Host "=== [1/4] Environment preparation ===" -ForegroundColor Cyan

# 1a. Java
$java = (Get-Command java -ErrorAction SilentlyContinue)
if (-not $java) {
    Write-Error "'java' not found on PATH. Install JDK 17 and retry."
    exit 1
}
$javaVer = (& java -version 2>&1 | Select-Object -First 1)
Write-Host "  java:  $javaVer"

# 1b. Maven
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue)
if (-not $mvn) {
    Write-Error "'mvn' not found on PATH. Install Maven 3.8+ and retry."
    exit 1
}
$mvnVer = (& mvn -v 2>&1 | Select-Object -First 1)
Write-Host "  mvn:   $mvnVer"

# 1c. Disk
$freeGB = [math]::Round((Get-PSDrive (Get-Item $ProjectRoot).PSDrive.Name).Free / 1GB, 1)
Write-Host "  disk:  $freeGB GB free"
if ($freeGB -lt 1.0) {
    Write-Warning "< 1 GB free disk. Maven may fail to download dependencies."
}

# 2. Maven build
$LogPath = Join-Path $ProjectRoot 'build.log'
Write-Host "=== [2/4] Maven build (log: $LogPath) ===" -ForegroundColor Cyan

$mvnArgs = @('-B', '-fae')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
if ($Clean)     { $mvnArgs += 'clean' }
if ($NoShade) {
    $mvnArgs += 'package'
} else {
    $mvnArgs += @('-pl', 'plumb-main', '-am', 'package')
}

& mvn @mvnArgs 2>&1 | Tee-Object -FilePath $LogPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven build failed. See $LogPath"
    exit $LASTEXITCODE
}

# 3. Summary
Write-Host "=== [3/4] Build summary ===" -ForegroundColor Cyan
$Version = (Select-String -Path (Join-Path $ProjectRoot 'pom.xml') -Pattern '<version>([^<]+)</version>' | Select-Object -First 1).Matches[0].Groups[1].Value
$Jar = Join-Path $ProjectRoot "plumb-main\target\plumb-main-${Version}-all.jar"
if (Test-Path $Jar) {
    $size = [math]::Round((Get-Item $Jar).Length / 1MB, 1)
    Write-Host "  shaded jar:  $Jar ($size MB)"
} elseif (-not $NoShade) {
    Write-Warning "shaded jar not produced. Did you pass -NoShade?"
}

if (-not $SkipTests) {
    $pass = (Select-String -Path $LogPath -Pattern 'Tests run.*Failures: 0, Errors: 0' | Measure-Object).Count
    Write-Host "  test files passed: $pass"
}

Write-Host "=== [4/4] Done ===" -ForegroundColor Green
Write-Host "Run:   java -jar $Jar --help"
