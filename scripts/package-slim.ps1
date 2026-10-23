# -*- mode: powershell -*-
# R-? — Build a "slim" dist that ships with the AI agent
# class files still on the classpath but forces
# `ai.enabled=false` by default. The static analyzer
# runs unchanged; --ai-dedup and --ai-scan do nothing
# because the AI gate is closed.
#
# Why this approach (vs. literally deleting plumb-ai
# classes):
#   plumb-main references org.plumb.ai.AIConfig in
#   field types (ScanConfig.aiConfig). Removing the
#   classes would break the static analysis class
#   loading. The "slim" cut is therefore a config-level
#   change: ship the same jars but lock the AI gate.
#
#   Users who want a true size-slimmed binary can
#   remove the plumb-ai jar from lib/ themselves; the
#   ScanRunner reflection guard logs a clear message
#   and falls back to the static-only path.
#
# Usage:
#   pwsh scripts/package-slim.ps1
#
# Output:
#   dist/plumb-0.1.0-SNAPSHOT-slim/   (mirrors the
#   regular dist layout; default-config.yaml rewritten
#   to set ai.enabled=false so the AI gate is closed
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

# 2) Rewrite default-config.yaml to set ai.enabled=false.
#    The runner will short-circuit all AI passes.
$yaml = Get-Content $conf -Raw
# Strip any leading "aggressiveness:" line so the
# config matches a "static-only" mental model.
$yaml = $yaml -replace '(?m)^aggressiveness:.*\r?\n', ''
# Force ai.enabled: false. If the line is missing,
# inject one under the ai: block.
if ($yaml -notmatch '(?m)^ai:') {
    $yaml = "ai:`n  enabled: false`n" + $yaml
} else {
    $yaml = $yaml -replace '(?m)^ai:\s*$', "ai:`n  enabled: false"
}
# Drop the enabled: true if it was set explicitly.
$yaml = $yaml -replace '(?m)^(\s+)enabled: true', '$1enabled: false'
[System.IO.File]::WriteAllText("$out/conf/default-config.yaml", $yaml, [System.Text.Encoding]::UTF8)
Write-Output "==> wrote $out/conf/default-config.yaml with ai.enabled=false"

# 3) Copy the all-jar unchanged.
Copy-Item -Force $jar "$out/lib/plumb-main-$version-all.jar"
$size = (Get-Item "$out/lib/plumb-main-$version-all.jar").Length
Write-Output "==> jar copied: $([Math]::Round($size / 1MB, 1)) MB"

# 4) README explaining the cut.
$readme = @"
PLUMB SLIM DIST (AI gate closed)
=================================

This is a build of Plumb with the AI augmentation layer
*disabled by default*. The static analyzer runs unchanged
(all 73 rules, all 4 call-graph strategies, all 4 cache
modes). The differences vs. the full dist:

  * conf/default-config.yaml has ai.enabled: false
  * --ai-dedup and --ai-scan become no-ops
  * The plumb-ai module is still on the classpath
    (the all-jar contains its class files), so the
    runner logs 'ai-disabled' instead of 'plumb-ai
    not in classpath' when you pass --ai-dedup

USE THIS DIST FOR
  * CI / production scans where LLM round-trips are
    not worth the latency / cost
  * Air-gapped environments

TO RE-ENABLE AI:  set `ai.enabled: true` in conf or
                   pass --ai-dedup / --ai-scan on the CLI
                   (the agent classes are still present).

FOR A TIGHTER FOOTPRINT: delete lib/plumb-main-...-all.jar
                        and rebuild plumb-main WITHOUT
                        shading the plumb-ai jar. The
                        runner's reflection guard logs
                        'plumb-ai not in classpath; AI
                        pass skipped' and continues
                        with the static-only output.
"@
[System.IO.File]::WriteAllText("$out/README-SLIM.txt", $readme, [System.Text.Encoding]::UTF8)

Write-Output ""
Write-Output "Done. To verify:"
Write-Output "  & '$env:JAVA_HOME\bin\java.exe' -jar '$out\lib\plumb-main-$version-all.jar' --list-rules"
Write-Output "  & '$env:JAVA_HOME\bin\java.exe' -jar '$out\lib\plumb-main-$version-all.jar' -c '$out\conf\default-config.yaml' -i 'examples\rNNN'"
Write-Output ""
Write-Output "(The slim config sets ai.enabled: false; you can"
Write-Output " override with --ai-dedup on the CLI or by editing"
Write-Output " conf/default-config.yaml.)"
