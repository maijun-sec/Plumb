@echo off
for %%f in (CollectionInappropriateCheck EcBadArrayStoreCheck FloatingPointLoopsCheck LoopsWithSameConditionCheck MethodReturnCheck RangeArrayIndexCheck RangeArrayLengthCheck StringBufferFieldInSerialClassCheck AvoidArrayLoopsCheck) do (
    findstr /c:"  public void visit(CompilationUnit" "src\main\java\org\plumb\style\performance\%%f.java" >nul
    if errorlevel 1 (echo %%f : migrated) else (echo %%f : NEEDS_MIGRATION)
)