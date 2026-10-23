@echo off
setlocal enabledelayedexpansion
set "DIR=%~1"
if "%DIR%"=="" set "DIR=src\main\java\org\plumb\style"
for /r "%DIR%" %%f in (*Check.java) do (
    findstr /c:"  public void visit(CompilationUnit" "%%f" >nul 2>&1
    if not errorlevel 1 echo %%f
)