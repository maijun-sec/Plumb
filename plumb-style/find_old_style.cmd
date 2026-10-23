@echo off
for /f "delims=" %%f in ('dir /b "src\main\java\org\plumb\style\style\*.java"') do (
    findstr /c:"^  public void visit(CompilationUnit n, StyleContext ctx)" "src\main\java\org\plumb\style\style\%%f" >nul 2>&1
    if not errorlevel 1 echo %%f
)