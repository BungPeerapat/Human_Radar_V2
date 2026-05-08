@echo off
setlocal
rem ============================================================================
rem Lightweight release: just create and push a git tag, then let GitHub Actions
rem build, sign, and publish. Use this if your keystore lives in CI rather than
rem on your dev machine.
rem
rem Usage:  tools\tag-release.bat 1.2.0
rem ============================================================================

if "%~1"=="" (
    echo ERROR: missing versionName.
    echo Usage: tools\tag-release.bat ^<versionName^>
    exit /b 1
)
set "VERSION_NAME=%~1"

pushd "%~dp0\.."

rem Refuse to tag a dirty tree — release should be reproducible
git diff --quiet HEAD
if errorlevel 1 (
    echo ERROR: working tree has uncommitted changes. Commit or stash first.
    popd & exit /b 1
)

git tag -a "v%VERSION_NAME%" -m "Release v%VERSION_NAME%"
if errorlevel 1 (
    echo ERROR: git tag failed (does v%VERSION_NAME% already exist?^)
    popd & exit /b 1
)

git push origin "v%VERSION_NAME%"
if errorlevel 1 (
    echo ERROR: failed to push tag
    popd & exit /b 1
)

echo.
echo Tag v%VERSION_NAME% pushed. GitHub Actions is now building the release.
echo Watch progress: https://github.com/^<owner^>/^<repo^>/actions

popd
endlocal
