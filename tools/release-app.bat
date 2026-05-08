@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul 2>&1

rem ============================================================================
rem Interactive app release. Walks through:
rem   1. Show current version
rem   2. Prompt for new version
rem   3. Edit release notes in Notepad
rem   4. Confirm summary
rem   5. Tag + push -> CI builds and publishes
rem ============================================================================

pushd "%~dp0\.."
set "REPO_ROOT=%CD%"

echo.
echo ============================================================
echo            HUMAN RADAR - APP RELEASE
echo ============================================================
echo.

rem ---------- Pre-checks ----------
where git >nul 2>&1
if errorlevel 1 (
    echo [ERROR] git not found in PATH
    goto :fail
)

for /f "delims=" %%b in ('git branch --show-current') do set "BRANCH=%%b"
echo  Branch:  !BRANCH!

git remote get-url origin >nul 2>&1
if errorlevel 1 (
    echo [ERROR] No git remote 'origin' configured
    goto :fail
)
for /f "delims=" %%r in ('git remote get-url origin') do set "REMOTE_URL=%%r"
echo  Remote:  !REMOTE_URL!
echo.

rem ---------- Working tree check + auto-commit ----------
git update-index --refresh >nul 2>&1
set "HAS_DIRTY="
git diff --quiet HEAD 2>nul
if errorlevel 1 set "HAS_DIRTY=1"
rem Also detect untracked files
for /f "delims=" %%u in ('git ls-files --others --exclude-standard 2^>nul') do set "HAS_DIRTY=1"

if defined HAS_DIRTY (
    echo  Uncommitted changes detected:
    echo.
    git status --short
    echo.
    set /p AUTO_COMMIT="Commit and push everything for me? (Y/n): "
    if /I "!AUTO_COMMIT!"=="n" (
        echo.
        echo  Skipping auto-commit. The release will only include what is
        echo  already committed and pushed.
        echo.
        set /p PROCEED_DIRTY="Continue anyway? (y/N): "
        if /I not "!PROCEED_DIRTY!"=="y" goto :abort
    ) else (
        echo.
        set "DEFAULT_MSG=chore: prep release"
        set /p COMMIT_MSG="Commit message [!DEFAULT_MSG!]: "
        if not defined COMMIT_MSG set "COMMIT_MSG=!DEFAULT_MSG!"
        echo.
        echo Staging all changes...
        git add -A
        if errorlevel 1 (
            echo [ERROR] git add failed
            goto :fail
        )
        echo Committing...
        git commit -m "!COMMIT_MSG!"
        if errorlevel 1 (
            echo [ERROR] git commit failed
            goto :fail
        )
        echo Pushing to origin/!BRANCH!...
        git push origin !BRANCH!
        if errorlevel 1 (
            echo [ERROR] git push failed. Check your network or branch protection.
            goto :fail
        )
        echo Done. Working tree is clean.
    )
    echo.
)

rem ---------- Unpushed commits check ----------
set "UNPUSHED=0"
for /f %%n in ('git rev-list "@{u}..HEAD" --count 2^>nul') do set "UNPUSHED=%%n"
if not "!UNPUSHED!"=="0" (
    echo  You have !UNPUSHED! local commit(s) not pushed to origin/!BRANCH!
    echo.
    set /p PUSH_FIRST="Push them now? (Y/n): "
    if /I not "!PUSH_FIRST!"=="n" (
        git push origin !BRANCH!
        if errorlevel 1 (
            echo [ERROR] git push failed
            goto :fail
        )
        echo Pushed.
        echo.
    )
)

rem ============================================================
rem Step 1: Detect current version
rem ============================================================
echo [Step 1/4] Detecting current version...
echo.

set "CURRENT_VERSION="
for /f "delims=" %%t in ('git tag -l "v*.*.*" --sort^=-v:refname 2^>nul') do (
    if not defined CURRENT_VERSION set "CURRENT_VERSION=%%t"
)
if not defined CURRENT_VERSION (
    set "CURRENT_VERSION=(no previous release)"
    set "SUGGESTED=1.0.0"
) else (
    rem Suggest next patch (vMAJOR.MINOR.PATCH+1)
    set "VER_NUM=!CURRENT_VERSION:v=!"
    for /f "tokens=1,2,3 delims=." %%a in ("!VER_NUM!") do (
        set /a NEXT_PATCH=%%c+1
        set "SUGGESTED=%%a.%%b.!NEXT_PATCH!"
    )
)

echo  Current version : !CURRENT_VERSION!
echo  Suggested next  : v!SUGGESTED!
echo.

rem ============================================================
rem Step 2: Ask for new version
rem ============================================================
echo [Step 2/4] New version
echo  Format: X.Y.Z   (e.g. 1.0.1, 1.1.0, 2.0.0)
echo  Press ENTER to use suggested: !SUGGESTED!
echo.

set "NEW_VERSION="
set /p NEW_VERSION="  New version: v"
if not defined NEW_VERSION set "NEW_VERSION=!SUGGESTED!"

rem Strip leading 'v' if user typed it
if /I "!NEW_VERSION:~0,1!"=="v" set "NEW_VERSION=!NEW_VERSION:~1!"

rem Validate X.Y.Z
echo !NEW_VERSION!| findstr /R "^[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo [ERROR] Invalid format. Must be X.Y.Z (digits only)
    goto :fail
)

rem Check tag doesn't already exist locally
git rev-parse "v!NEW_VERSION!" >nul 2>&1
if not errorlevel 1 (
    echo [ERROR] Tag v!NEW_VERSION! already exists locally
    goto :fail
)

rem Check tag doesn't exist on remote
git ls-remote --tags origin "refs/tags/v!NEW_VERSION!" 2>nul | findstr /C:"v!NEW_VERSION!" >nul
if not errorlevel 1 (
    echo [ERROR] Tag v!NEW_VERSION! already exists on remote
    goto :fail
)

echo.
echo  -^> Will release as: v!NEW_VERSION!
echo.

rem ============================================================
rem Step 3: Release notes via Notepad
rem ============================================================
echo [Step 3/4] Release notes
echo.
echo  Notepad will open. Write your notes, save (Ctrl+S), then close.
echo  Lines starting with # are ignored (template hints).
echo.
pause

set "NOTES_FILE=%TEMP%\hr_release_notes.txt"
set "NOTES_CLEAN=%TEMP%\hr_release_notes_clean.txt"
if exist "!NOTES_FILE!" del "!NOTES_FILE!"
if exist "!NOTES_CLEAN!" del "!NOTES_CLEAN!"

(
    echo # Release v!NEW_VERSION!
    echo # ============================================
    echo # Write release notes below this block.
    echo # Lines starting with '#' will be removed.
    echo # Examples:
    echo #   - Added landscape support
    echo #   - Fixed MQTT reconnect bug
    echo #   - Improved radar rendering
    echo # ============================================
    echo.
    echo -
) > "!NOTES_FILE!"

start /wait notepad "!NOTES_FILE!"

rem Strip comment lines into clean file
type nul > "!NOTES_CLEAN!"
for /f "usebackq tokens=* delims=" %%l in ("!NOTES_FILE!") do (
    set "line=%%l"
    if defined line (
        if not "!line:~0,1!"=="#" (
            echo(!line!>>"!NOTES_CLEAN!"
        )
    ) else (
        echo(>>"!NOTES_CLEAN!"
    )
)

rem Check notes have meaningful content
set "HAS_CONTENT="
for /f "usebackq tokens=* delims=" %%l in ("!NOTES_CLEAN!") do (
    set "trim=%%l"
    set "trim=!trim: =!"
    set "trim=!trim:-=!"
    if defined trim set "HAS_CONTENT=1"
)

if not defined HAS_CONTENT (
    echo [ERROR] Release notes are empty.
    goto :fail
)

rem ============================================================
rem Step 4: Confirm summary
rem ============================================================
echo.
echo ============================================================
echo                  RELEASE SUMMARY
echo ============================================================
echo  Branch       : !BRANCH!
echo  Previous     : !CURRENT_VERSION!
echo  New version  : v!NEW_VERSION!
echo.
echo  Release notes:
echo  ------------------------------------------------------------
type "!NOTES_CLEAN!"
echo  ------------------------------------------------------------
echo.
echo  After upload:
echo   - GitHub Actions will build a signed APK (~5-7 minutes)
echo   - APK + update.json published to GitHub Releases
echo   - update.json synced to gh-pages branch
echo   - Existing users will see the update prompt within 6 hours
echo.
echo ============================================================
echo.

set "CONFIRM="
set /p CONFIRM="  Type Y and press ENTER to UPLOAD, anything else to cancel: "
if /I not "!CONFIRM!"=="y" goto :abort

rem ============================================================
rem Upload
rem ============================================================
echo.
echo Creating annotated tag v!NEW_VERSION!...
git tag -a "v!NEW_VERSION!" -F "!NOTES_CLEAN!"
if errorlevel 1 (
    echo [ERROR] Failed to create tag
    goto :fail
)

echo Pushing tag to origin...
git push origin "v!NEW_VERSION!"
if errorlevel 1 (
    echo [ERROR] Failed to push tag. Cleaning up local tag.
    git tag -d "v!NEW_VERSION!" >nul 2>&1
    goto :fail
)

rem Cleanup temp files
del "!NOTES_FILE!" 2>nul
del "!NOTES_CLEAN!" 2>nul

rem Resolve repo URL for the Actions link
set "REPO_PATH="
for /f "tokens=2 delims=:" %%a in ("!REMOTE_URL!") do set "REPO_PATH=%%a"
if "!REPO_PATH!"=="" set "REPO_PATH=!REMOTE_URL:https://github.com/=!"
set "REPO_PATH=!REPO_PATH:.git=!"

echo.
echo ============================================================
echo                 UPLOAD INITIATED
echo ============================================================
echo  Tag v!NEW_VERSION! pushed to origin.
echo  GitHub Actions is now building your release.
echo.
echo  Watch progress:
echo    https://github.com/!REPO_PATH!/actions
echo.
echo  Once the workflow turns green:
echo    Releases : https://github.com/!REPO_PATH!/releases
echo    Manifest : (configured UPDATE_MANIFEST_URL)
echo.
echo  Users will see the update within 6 hours, or instantly
echo  via the "Check for updates" button in the Config tab.
echo ============================================================
echo.

popd
endlocal
exit /b 0

:abort
echo.
echo Cancelled. No tag created, no upload performed.
if exist "%TEMP%\hr_release_notes.txt" del "%TEMP%\hr_release_notes.txt" 2>nul
if exist "%TEMP%\hr_release_notes_clean.txt" del "%TEMP%\hr_release_notes_clean.txt" 2>nul
popd
endlocal
exit /b 0

:fail
echo.
echo ============================================================
echo                  RELEASE FAILED
echo ============================================================
if exist "%TEMP%\hr_release_notes.txt" del "%TEMP%\hr_release_notes.txt" 2>nul
if exist "%TEMP%\hr_release_notes_clean.txt" del "%TEMP%\hr_release_notes_clean.txt" 2>nul
popd
endlocal
exit /b 1
