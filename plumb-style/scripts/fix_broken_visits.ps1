#requires -Version 5.0
# Repair checks whose visit signature lost the variable name
# (became "visit(Type, ctx)" — should be "visit(Type var, ctx)").
# Finds the matching "for (Type var : n.findAll(Type.class))" loop below and
# copies the loop variable name into the visit signature.

$files = @(
    'EnumAbstractMethodCheck.java', 'EnumIdiomCheck.java',
    'ExplicitInitializationCheck.java', 'FallThroughCheck.java',
    'IdempotentOperationsCheck.java', 'IncompatibleMaskCheck.java',
    'InstantiationToGetClassCheck.java', 'NoEnumTrailingCommaCheck.java',
    'ObjectsEqualityCheck.java', 'OperatorWrapCheck.java',
    'ParenPadCheck.java', 'PublicProtectedFieldInEnumCheck.java',
    'QuestionableBooleanAssignmentCheck.java',
    'ReachableAssertionCheck.java', 'RelationalOperatorSymmetryCheck.java',
    'RequireThisCheck.java', 'SeparatorWrapCheck.java',
    'SerializableSuperConstructorCheck.java', 'SignOfAdditionCheck.java',
    'SimpleDateFormatWithoutLocaleCheck.java',
    'SingleSpaceSeparatorCheck.java', 'StartWhereGreaterThanEndCheck.java',
    'SuspiciousIntegerDivisionCheck.java',
    'UnclearedSensitiveInfoCheck.java', 'UnnecessaryEnumCaseCheck.java',
    'UpperEllCheck.java', 'WhitespaceAfterCheck.java',
    'EmptyStaticInitializerCheck.java', 'EmptyZipEntryCheck.java',
    'ModifierOrderCheck.java', 'RvRemoveRepeatCallsCheck.java'
)

$dir = 'src\main\java\org\plumb\style\style'
$fixed = 0

foreach ($f in $files) {
    $path = Join-Path $dir $f
    if (-not (Test-Path $path)) { continue }
    $content = Get-Content -Path $path -Raw
    $original = $content

    # Pattern: visit(TypeName, ctx) { ... for (TypeName varName : n.findAll(TypeName.class)) {
    # Extract type name from the broken visit signature
    if ($content -match 'visit\(([\w.<>\s,\[\]]+?),\s*ctx\)\s*\{([^}]*?)for\s*\(\s*([\w.<>\s,\[\]]+?)\s+(\w+)\s*:\s*n\.findAll\(') {
        $typeName = $matches[1].Trim()
        $between = $matches[2]
        $loopType = $matches[3].Trim()
        $varName = $matches[4]
        if ($typeName -ne $loopType) {
            Write-Host ("Type mismatch in {0}: visit({1}) vs loop type {2}" -f $f, $typeName, $loopType)
            continue
        }
        $content = $content -replace ('visit\(\{0},\s*ctx\)' -f [regex]::Escape($typeName)), ('visit({0}, {1})' -f $typeName, $varName)
        if ($content -ne $original) {
            Set-Content -Path $path -Value $content -NoNewline
            Write-Host ("Fixed {0}: visit({1}, {2})" -f $f, $typeName, $varName)
            $fixed++
        }
    }
}

Write-Host ""
Write-Host "Total fixed: $fixed"
Write-Host "Now run: mvn -o test"