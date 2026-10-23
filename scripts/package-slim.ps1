# -*- mode: powershell -*-
# Build a "slim" dist that ships with the AI agent
# class files still on the classpath but forces
# `ai.dedup=false` by default. The static analyzer
# runs unchanged; the AI FP-reducer pass does not run
# because the AI gate is closed.
#
# Why this approach (vs. literally deleting plumb-ai
# classes):
#   plumb-main references org.plumb.ai.agent.LlmConnection
#   in field types (ScanConfig.aiConn). Removing the
#   classes would break the static analysis class
#   loading. The "slim" cut is therefore a config-level
#   change: ship the same jars but lock the AI gate.
#
#   NOTE: the gate is `ai.dedup`, NOT `ai.enabled`.
#   `ai.enabled` was removed from the config schema and
#   ConfigLoader now warns and ignores it, so writing it
#   would leave the AI pass enabled.
#
# Usage:
#   pwsh scripts/package-slim.ps1
#
# Output:
#   dist/plumb-0.1.0-SNAPSHOT-slim/   (mirrors the
#   regular dist layout; default-config.yaml rewritten
#   to set ai.dedup=false so the AI gate is closed
#   out of the box).

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$version = '0.1.0-SNAPSHOT'
$out  = Join-Path $root "dist/plumb-$version-slim"
$jar  = Join-Path $root "plumb-main/target/plumb-main-$version-all.jar"
$conf = Join-Path $root "plumb-main/src/main/resources/default-config.yaml"

if (-not (Test-Path $jar)) {
    Write-Error "Cannot find $jar. Build it first: mvn -pl plumb-main package -DskipTests=true"
    exit 1
}
if (-not (Test-Path $conf)) {
    Write-Error "Cannot find $conf."
    exit 1
}

Write-Output "==> Building slim dist at $out"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path "$out/bin"   | Out-Null
New-Item -ItemType Directory -Force -Path "$out/lib"   | Out-Null
New-Item -ItemType Directory -Force -Path "$out/conf"  | Out-Null

# 1) Copy the same layout as the full dist.
Copy-Item -Force "$root/dist/plumb-$version/bin/*"      "$out/bin/"   -Recurse
Copy-Item -Force "$root/dist/plumb-$version/conf/*"     "$out/conf/"  -Recurse
Copy-Item -Force "$root/dist/plumb-$version/README.txt" "$out/"

# 2) Rewrite default-config.yaml to set ai.dedup=false.
#    The runner will short-circuit the AI dedup pass.
$yaml = Get-Content $conf -Raw
# Strip any leading "aggressiveness:" line so the
# config matches a "static-only" mental model.
$yaml = $yaml -replace '(?m)^aggressiveness:.*\r?\n', ''
# Force ai.dedup: false. If the line is missing,
# inject one under the ai: block.
if ($yaml -notmatch '(?m)^ai:') {
    $yaml = "ai:`n  dedup: false`n" + $yaml
} else {
    $yaml = $yaml -replace '(?m)^ai:\s*$', "ai:`n  dedup: false"
}
# Drop the dedup: true if it was set explicitly.
$yaml = $yaml -replace '(?m)^(\s+)dedup:\s*true', '$1dedup: false'
[System.IO.File]::WriteAllText("$out/conf/default-config.yaml", $yaml, [System.Text.Encoding]::UTF8)
Write-Output "==> wrote $out/conf/default-config.yaml with ai.dedup=false"

# 3) Copy the all-jar unchanged.
Copy-Item -Force $jar "$out/lib/plumb-main-$version-all.jar"
$size = (Get-Item "$out/lib/plumb-main-$version-all.jar").Length
Write-Output "==> jar copied: $([Math]::Round($size / 1MB, 1)) MB"

# 4) README explaining the cut.
$readme = @"
PLUMB SLIM DIST (AI gate closed)
=================================

This is a build of Plumb with the AI augmentation layer
*disabled by default*. The static analyzer runs unchanged.
The differences vs. the full dist:

  * conf/default-config.yaml has ai.dedup: false
  * the AI FP-reducer pass does not run
  * The plumb-ai module is still on the classpath
    (the all-jar contains its class files), so the
    classes load fine - it is only the gate that is
    closed

USE THIS DIST FOR
  * CI / production scans where LLM round-trips are
    not worth the latency / cost
  * Air-gapped environments

TO RE-ENABLE AI:  set `ai.dedup: true` in conf or
                   pass --ai-dedup on the CLI
                   (the agent classes are still present).

FOR A TIGHTER FOOTPRINT: rebuild plumb-main WITHOUT
                        shading the plumb-ai jar and
                        delete lib/plumb-main-...-all.jar
                        from lib/. plumb-ai is a hard
                        dependency of plumb-main, so the
                        jar must be rebuilt without it -
                        just deleting the file from an
                        existing uber-jar will not work.
"@
[System.IO.File]::WriteAllText("$out/README-SLIM.txt", $readme, [System.Text.Encoding]::UTF8)

Write-Output ""
Write-Output "Done. To verify:"
Write-Output "  & '$env:JAVA_HOME\bin\java.exe' -jar '$out\lib\plumb-main-$version-all.jar' --list-rules"
Write-Output "  & '$env:JAVA_HOME\bin\java.exe' -jar '$out\lib\plumb-main-$version-all.jar' -c '$out\conf\default-config.yaml' -i 'examples\rNNN'"
Write-Output ""
Write-Output "(The slim config sets ai.dedup: false; you can"
Write-Output " override with --ai-dedup on the CLI or by editing"
Write-Output " conf/default-config.yaml.)"
