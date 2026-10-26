# =============================================================
# Plumb "解压即用" 打包 (Windows) — jlink + ZIP
# -------------------------------------------------------------
# 把 shaded uber-jar 加上 jlink 输出的最小 JRE,打进一个
# 自包含的 zip。用户机器无需任何 Java 环境,解压即跑。
#
# Usage:
#   pwsh scripts/dist-jlink-windows.ps1 [-SkipBuild] [-OutputDir DIR] [-Arch x64|arm64]
#
# Output:
#   dist-jlink\plumb-<version>-jre-windows-x64.zip (~180 MB)
# =============================================================
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$NoJlink,
    [string]$OutputDir,
    [string]$Arch = "x64"
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $ProjectRoot

if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot 'dist-jlink'
}

$Version = (Select-String -Path (Join-Path $ProjectRoot 'pom.xml') -Pattern '<version>([^<]+)</version>' | Select-Object -First 1).Matches[0].Groups[1].Value
$JarName = "plumb-main-${Version}-all.jar"
$JarPath = Join-Path $ProjectRoot "plumb-main\target\$JarName"
$Dist    = Join-Path $OutputDir "plumb-${Version}-jre-windows-$Arch"

# ---------- 1. Environment preparation ----------------------
Write-Host '=== [1/5] Environment preparation ===' -ForegroundColor Cyan

# 1a. JDK 17+ required (jlink needs JDK, not JRE)
$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) {
    Write-Error "'javac' not found. jlink requires JDK 17+, not JRE.`nInstall JDK 17 (e.g. choco install temurin17) and set JAVA_HOME."
    exit 1
}
if (-not $env:JAVA_HOME) {
    Write-Warning 'JAVA_HOME not set. Inferring from javac location...'
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Resolve-Path $javac.Path))
    Write-Host "  Inferred JAVA_HOME=$env:JAVA_HOME"
}
$jmods = Join-Path $env:JAVA_HOME 'jmods'
$hasJmods = Test-Path $jmods -PathType Container
if (-not $hasJmods) {
    if ($NoJlink) {
        Write-Warning "`$env:JAVA_HOME\jmods not found at $jmods. -NoJlink set, will skip bundled JRE."
    } else {
        Write-Error @"
`$env:JAVA_HOME\jmods not found at $jmods.

jlink needs the JDK's `jmods/` directory to build a custom JRE.
Many JDK distributions (Temurin 17+ 'JRE' installers, some Linux
packages) don't ship jmods. Fixes:

  1) Install a JDK that ships jmods:
     - https://jdk.java.net/  (OpenJDK reference builds, includes jmods)
     - choco install temurin17  (note: Temurin splits jmods into
       a separate download starting with JDK 21+)

  2) Or download a jmods archive and place it at:
     $jmods

  3) Or skip the bundled JRE with -NoJlink:
     pwsh scripts\dist-jlink-windows.ps1 -NoJlink
     (the dist will need a system-installed JRE 17+ to run)
"@
        exit 1
    }
}
Write-Host "  JAVA_HOME: $env:JAVA_HOME"
# Use cmd /c to avoid PowerShell native-command error on stderr output.
$javaVer = cmd /c "`"$env:JAVA_HOME\bin\java.exe`" -version 2>&1" 2>&1 | Select-Object -First 1
if ($javaVer) { Write-Host "  $javaVer" }

# 1b. PowerShell Compress-Archive is available on PS 5+; always present
Write-Host "  powershell: $($PSVersionTable.PSVersion)"

# 1c. disk
$freeGB = [math]::Round((Get-PSDrive (Get-Item $ProjectRoot).PSDrive.Name).Free / 1GB, 1)
Write-Host "  disk:    $freeGB GB free"
if ($freeGB -lt 0.5) {
    Write-Warning '< 500 MB free disk. jlink output + zip may not fit.'
}

# ---------- 2. Maven build ---------------------------------
$LogPath = Join-Path $ProjectRoot 'build.log'
if (-not $SkipBuild) {
    Write-Host '=== [2/5] Maven build ===' -ForegroundColor Cyan
    if (-not (Test-Path $JarPath -PathType Leaf)) {
        & mvn -B -fae -pl plumb-main -am package -DskipTests 2>&1 | Tee-Object -FilePath $LogPath | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Error "Maven build failed. See $LogPath"; exit $LASTEXITCODE }
    } else {
        Write-Host "  (cached) $JarPath exists, skipping rebuild"
    }
} else {
    Write-Host '=== [2/5] Maven build (SKIPPED via -SkipBuild) ===' -ForegroundColor Cyan
}

if (-not (Test-Path $JarPath -PathType Leaf)) {
    Write-Error "Missing $JarPath. Build first (drop -SkipBuild)."
    exit 1
}
$jarSize = [math]::Round((Get-Item $JarPath).Length / 1MB, 1)
Write-Host "  shaded jar: $JarPath ($jarSize MB)"

# ---------- 3. jlink minimal JRE ----------------------------
Write-Host '=== [3/5] jlink minimal JRE ===' -ForegroundColor Cyan

# Ensure $Dist exists BEFORE jlink (jlink fails if output dir is missing).
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

# Plumb uses these JDK modules. Verified by
#   mvn -pl plumb-main dependency:tree | grep jmod
# and javaparser-symbol-solver-core's reflection usage.
$Modules = @(
    'java.base'
    'java.logging'
    'java.net.http'
    'java.compiler'
    'java.instrument'
    'java.management'
    'java.naming'
    'java.security.jgss'
    'java.sql'
    'java.xml'
    'jdk.crypto.ec'
    'jdk.httpserver'
    'jdk.unsupported'      # SootUp
    'jdk.zipfs'            # z3 turnkey
)
$AddModules = $Modules -join ','

$JrePath = Join-Path $Dist 'jre'
if ($NoJlink -or -not $hasJmods) {
    if ($NoJlink) {
        Write-Host '  -NoJlink: skipping jlink. Dist will require system JRE 17+ to run.'
    } else {
        Write-Warning '  jmods/ not found; skipping jlink. Dist will require system JRE 17+ to run.'
    }
} elseif (Test-Path $JrePath -PathType Container) {
    Write-Host "  (cached) $JrePath exists, skipping jlink"
} else {
    & "$env:JAVA_HOME\bin\jlink.exe" `
        --module-path $jmods `
        --add-modules $AddModules `
        --strip-debug `
        --compress=zip-9 `
        --no-header-files `
        --no-man-pages `
        --output $JrePath
    if ($LASTEXITCODE -ne 0) { Write-Error "jlink failed with exit $LASTEXITCODE"; exit $LASTEXITCODE }
    $jreSize = [math]::Round((Get-ChildItem $JrePath -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Write-Host "  jre:    $JrePath ($jreSize MB)"
}

# ---------- 4. Dist layout ----------------------------------
Write-Host '=== [4/5] Assemble dist ===' -ForegroundColor Cyan

if (Test-Path $Dist -PathType Container) {
    cmd /c "rmdir /S /Q `"$Dist`"" | Out-Null
}
New-Item -ItemType Directory -Force -Path "$Dist\bin"   | Out-Null
New-Item -ItemType Directory -Force -Path "$Dist\lib"   | Out-Null
New-Item -ItemType Directory -Force -Path "$Dist\conf"  | Out-Null
New-Item -ItemType Directory -Force -Path "$Dist\docs"  | Out-Null

# 4a. shaded jar
Copy-Item -Force $JarPath "$Dist\lib\"

# 4b. configs
Copy-Item -Force (Join-Path $ProjectRoot 'plumb-main\src\main\resources\default-config.yaml') "$Dist\conf\"
Copy-Item -Force (Join-Path $ProjectRoot 'plumb-main\src\main\resources\logback.xml')         "$Dist\conf\"

# 4c. jlink JRE (only if not skipped and jmods are present)
if (-not $NoJlink -and $hasJmods -and -not (Test-Path (Join-Path $Dist 'jre') -PathType Container)) {
    Write-Host "  jlink -> $Dist\jre"
    & "$env:JAVA_HOME\bin\jlink.exe" `
        --module-path $jmods `
        --add-modules $AddModules `
        --strip-debug `
        --compress=zip-9 `
        --no-header-files `
        --no-man-pages `
        --output "$Dist\jre"
    if ($LASTEXITCODE -ne 0) { Write-Error "jlink (dist) failed with exit $LASTEXITCODE"; exit $LASTEXITCODE }
    $jreSize2 = [math]::Round((Get-ChildItem "$Dist\jre" -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Write-Host "  jre:    $Dist\jre ($jreSize2 MB)"
} elseif (Test-Path (Join-Path $Dist 'jre') -PathType Container) {
    $jreSize2 = [math]::Round((Get-ChildItem "$Dist\jre" -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Write-Host "  jre:    $Dist\jre ($jreSize2 MB) (from step 3)"
} else {
    Write-Warning '  no jre/ in dist. User must have JRE 17+ on PATH or set JAVA_HOME.'
}

# 4d. launcher scripts
$PlumbBat = @'
@echo off
rem Plumb launcher (Windows)
rem Uses bundled jre\ if present, else falls back to %%JAVA_HOME%% or PATH.
setlocal
set DIR=%~dp0..
set JRE=%DIR%\jre
if exist "%JRE%\bin\java.exe" (
  set JAVA=%JRE%\bin\java.exe
) else if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" set JAVA=%JAVA_HOME%\bin\java.exe
) else (
  where java >nul 2>nul && (set JAVA=java) || (
    echo ERROR: no java found. Set JAVA_HOME or install JDK 17+.
    exit /b 1
  )
)
if not defined PLUMB_JAVA_OPTS set PLUMB_JAVA_OPTS=-Xmx4g -XX:+UseG1GC
if /I "%PLUMB_DEBUG%"=="true" set PLUMB_JAVA_OPTS=%PLUMB_JAVA_OPTS% -Droot.level=DEBUG
"%JAVA%" %PLUMB_JAVA_OPTS% -Dplumb.home="%DIR%" -Dlogback.configurationFile="%DIR%\conf\logback.xml" -cp "%DIR%\lib\*" org.plumb.main.SastMain %*
endlocal
'@
[System.IO.File]::WriteAllText((Join-Path $Dist 'bin\plumb.bat'), $PlumbBat, [System.Text.Encoding]::ASCII)

$PlumbSh = @'
#!/usr/bin/env bash
# Plumb launcher (POSIX fallback inside Windows-x64 dist)
DIR="$(cd "$(dirname "$0")"/.. && pwd)"
JRE="$DIR/jre"
if   [ -x "$JRE/bin/java" ];     then JAVA="$JRE/bin/java"
elif [ -x "${JAVA_HOME:-}/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"
else JAVA="$(command -v java || true)"
fi
[ -z "$JAVA" ] && { echo "ERROR: no java found"; exit 1; }
JVM_OPTS="${PLUMB_JAVA_OPTS:--Xmx4g -XX:+UseG1GC}"
exec "$JAVA" $JVM_OPTS -Dplumb.home="$DIR" -Dlogback.configurationFile="$DIR/conf/logback.xml" -cp "$DIR/lib/*" org.plumb.main.SastMain "$@"
'@
[System.IO.File]::WriteAllText((Join-Path $Dist 'bin\plumb'), $PlumbSh, [System.Text.Encoding]::UTF8)

# 4e. docs
# Every source file is copied only if it exists: this script runs under
# $ErrorActionPreference = 'Stop', so Copy-Item of a missing file would
# abort the whole packaging run.
$DocsToCopy = @(
    'README.md',
    'docs\plumb-style-rules.md',
    'docs\usage\ENVIRONMENT.md',
    'docs\usage\CLI.md',
    'docs\usage\CLI-REFERENCE.md',
    'docs\usage\CLI-EXAMPLES.md',
    'docs\rules\RULES.md'
)
foreach ($Doc in $DocsToCopy) {
    $DocPath = Join-Path $ProjectRoot $Doc
    if (Test-Path $DocPath -PathType Leaf) {
        Copy-Item -Force $DocPath "$Dist\docs\"
    } else {
        Write-Host "  (skip, not found: $Doc)"
    }
}

# 4f. dist README (use external UTF-8 template to avoid
#     PowerShell 5.1 ANSI parser mangling CJK in here-strings)
$ReadmePath = Join-Path $Dist 'README.md'
$ReadmeTpl  = Join-Path $PSScriptRoot 'templates\README-windows.md.template'
$Readme     = Get-Content -Path $ReadmeTpl -Encoding UTF8 -Raw
$Readme     = $Readme -replace '__VERSION__', $Version -replace '__ARCH__', $Arch
[System.IO.File]::WriteAllText($ReadmePath, $Readme, [System.Text.Encoding]::UTF8)

# 4g. CHANGELOG (same UTF-8 template trick)
$Date         = Get-Date -Format 'yyyy-MM-dd'
$ChangelogPath = Join-Path $Dist 'CHANGELOG.md'
$ChangelogTpl = Join-Path $PSScriptRoot 'templates\CHANGELOG.template'
$Changelog    = Get-Content -Path $ChangelogTpl -Encoding UTF8 -Raw
$Changelog    = $Changelog -replace '__VERSION__', $Version -replace '__ARCH__', $Arch -replace '__DATE__', $Date
[System.IO.File]::WriteAllText($ChangelogPath, $Changelog, [System.Text.Encoding]::UTF8)

# ---------- 5. ZIP ------------------------------------------
Write-Host '=== [5/5] Pack ZIP ===' -ForegroundColor Cyan
$ZipPath = Join-Path $OutputDir "plumb-$Version-jre-windows-$Arch.zip"
if (Test-Path $ZipPath -PathType Leaf) { Remove-Item -Force $ZipPath }
# Compress-Archive on PS 5.1 is single-threaded and slow for 180 MB;
# use [System.IO.Compression.ZipFile] for ~3x speedup.
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($Dist, $ZipPath, [System.IO.Compression.CompressionLevel]::Optimal, $true)
$zipSize = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
Write-Host ''
Write-Host '============================================================' -ForegroundColor Green
Write-Host "  Output:    $ZipPath ($zipSize MB)"
Write-Host "  Layout:    $Dist\"
Write-Host "  Verify:    Expand-Archive '$ZipPath' -DestinationPath C:\Test\"
Write-Host "  Test:      $Dist\bin\plumb.bat --version"
Write-Host '============================================================' -ForegroundColor Green
