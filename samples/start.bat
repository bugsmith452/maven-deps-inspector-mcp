@echo off
REM Starts the Maven Deps Inspector MCP server.
REM Run from the project root: samples\start.bat
REM Or with a custom config: samples\start.bat --config C:\path\to\config.json

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..

for /f "delims=" %%f in ('dir /b /s "%PROJECT_DIR%\target\maven-deps-inspector-mcp-*.jar" 2^>nul') do set JAR=%%f

if not defined JAR (
    echo ERROR: No JAR found in target\. Build first with: mvn package -DskipTests
    exit /b 1
)

if "%~1"=="" (
    REM Auto-select config: prefer test1-config.json if present (gitignored),
    REM fall back to the generic config.json.
    if exist "%SCRIPT_DIR%test1-config.json" (
        java -jar "%JAR%" --config "%SCRIPT_DIR%test1-config.json"
    ) else (
        java -jar "%JAR%" --config "%SCRIPT_DIR%config.json"
    )
) else (
    java -jar "%JAR%" %*
)
