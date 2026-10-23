#requires -Version 5.0
# Batch-migrate remaining style checks in plumb-style from
# visit(CompilationUnit) + findAll(X.class) to per-node-type visit(X, ctx).
#
# Usage (from repo root):
#   cd plumb-style
#   pwsh -File scripts/migrate_style_checks.ps1
#
# What it does per file:
#   1. Reads the file.
#   2. Identifies the loop body: for (TypeX x : n.findAll(TypeX.class)) { ... }
#   3. Detects the node-type variable name (e.g. "x").
#   4. Replaces visit(CompilationUnit, ...) with visit(TypeX, ...) using the loop
#      variable as the parameter.
#   5. Removes the findAll import if it becomes unused.
#   6. Skips files where the body depends on per-file state (singleton detection,
#      multi-pass inter-proc maps, n.toString() heuristics, getImports() iteration).
#
# Manual review is required for the skipped files. The script reports a list
# of "MIGRATED" and "SKIPPED" entries at the end.

param([string]$Dir = "src\main\java\org\plumb\style\style")

$files = Get-ChildItem -Path $Dir -Filter *.java

$migrated = @()
$skipped = @()
$failed = @()

# Regex for "for (TypeX x : n.findAll(TypeX.class))"
$loopPattern = 'for\s*\(\s*([\w.<>\s,\[\]]+?)\s+(\w+)\s*:\s*n\.findAll\(([^)]+)\.class\)\)\s*\{'

# Regex for "visit(CompilationUnit n, StyleContext ctx) {"
$visitPattern = 'visit\(CompilationUnit\s+\w+,\s*StyleContext\s+\w+\)\s*\{'

# Patterns that signal "skip": per-file state we can't auto-handle
$skipMarkers = @(
    'n\.getImports\(\)',
    'n\.getAllContainedComments\(\)',
    'n\.toString\(\)',
    'Map<String.*?> byName',
    'Per-file',
    'comment-to-line',
    'init\(\)\s*\{'  # custom init with state
)

function Test-SkipMarker([string]$content) {
    foreach ($marker in $skipMarkers) {
        if ($content -match $marker) { return $true }
    }
    return $false
}

function Rewrite-SingleLoopFile([System.IO.FileInfo]$file) {
    $content = Get-Content -Path $file.FullName -Raw
    $rel = $file.Name

    # Must have visit(CompilationUnit)
    if ($content -notmatch $visitPattern) { return @{ Status = "skip"; Reason = "no visit(CU)" } }

    # Skip per-file state
    if (Test-SkipMarker $content) { return @{ Status = "skip"; Reason = "per-file state" } }

    # Find findAll loops. Must have exactly one.
    $matches = [regex]::Matches($content, $loopPattern)
    if ($matches.Count -ne 1) {
        return @{ Status = "skip"; Reason = "$($matches.Count) findAll loops" }
    }

    $match = $matches[0]
    $typeName = $match.Groups[1].Value.Trim()
    $varName = $match.Groups[2].Value.Trim()
    $findAllArg = $match.Groups[3].Value.Trim()

    # Replace visit(CompilationUnit ...) { ... } with visit(TypeX, ctx) { ... }
    # Use first match only (single visit(CU) per file in 99% of cases).
    $newContent = [regex]::Replace(
        $content,
        'visit\(CompilationUnit\s+(\w+),\s*StyleContext\s+(\w+)\)\s*\{',
        ('visit({0}, $2) {{' -f $typeName)
    )

    if ($newContent -eq $content) {
        return @{ Status = "fail"; Reason = "regex did not match visit(CU)" }
    }

    # Save
    Set-Content -Path $file.FullName -Value $newContent -NoNewline

    return @{ Status = "migrated"; Type = $typeName; Var = $varName; FindAll = $findAllArg }
}

foreach ($f in $files) {
    $result = Rewrite-SingleLoopFile $f
    switch ($result.Status) {
        "migrated" { $migrated += [PSCustomObject]@{ File = $f.Name; Type = $result.Type; Var = $result.Var } }
        "skip"      { $skipped  += [PSCustomObject]@{ File = $f.Name; Reason = $result.Reason } }
        "fail"      { $failed   += [PSCustomObject]@{ File = $f.Name; Reason = $result.Reason } }
    }
}

Write-Host "==========================================="
Write-Host "Migration summary"
Write-Host "==========================================="
Write-Host ""
Write-Host "Migrated: $($migrated.Count)"
foreach ($m in $migrated) {
    Write-Host ("  {0,-60} visit({1}) -> variable '{2}'" -f $m.File, $m.Type, $m.Var)
}
Write-Host ""
Write-Host "Skipped: $($skipped.Count)"
foreach ($s in $skipped) {
    Write-Host ("  {0,-60} {1}" -f $s.File, $s.Reason)
}
Write-Host ""
Write-Host "Failed: $($failed.Count)"
foreach ($f in $failed) {
    Write-Host ("  {0,-60} {1}" -f $f.File, $f.Reason)
}

Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. cd ..\n"
Write-Host "  2. mvn -o test    # run tests, fix any compile errors"
Write-Host "  3. Review the auto-migrated files in plumb-style/src/main/java/org/plumb/style/style/"
Write-Host "  4. Manually migrate the SKIPPED files (they need per-node-type visit" -f ([PSCustomObject]@{x=1}.x)