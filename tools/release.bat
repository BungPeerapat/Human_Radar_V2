@echo off
setlocal EnableDelayedExpansion
rem ============================================================================
rem Local end-to-end Android release.
rem
rem Usage:
rem   tools\release.bat <versionName>            (e.g. tools\release.bat 1.2.0)
rem   tools\release.bat <versionName> --dry-run  (build + manifest, no upload)
rem
rem Prerequisites (one-time):
rem   1. Copy release.config.bat.example -> release.config.bat and fill in.
rem   2. gh CLI installed and authenticated:  gh auth login
rem   3. Keystore generated and KEYSTORE_PATH points at it.
rem
rem What it does:
rem   1. Loads release.config.bat
rem   2. Builds a signed release APK with version metadata baked in
rem   3. Renames APK, computes SHA-256 + size
rem   4. Generates update.json
rem   5. (optional) Creates git tag vX.Y.Z and pushes
rem   6. (optional) Creates GitHub Release and uploads APK + update.json
rem   7. (optional) Publishes update.json to gh-pages branch
rem ============================================================================

pushd "%~dp0\.."
set "REPO_ROOT=%CD%"

rem ---------- 1. parse args ----------
if "%~1"=="" (
    echo ERROR: missing versionName argument.
    echo Usage: tools\release.bat ^<versionName^> [--dry-run]
    goto :fail
)
set "VERSION_NAME=%~1"
set "DRY_RUN="
if /I "%~2"=="--dry-run" set "DRY_RUN=1"

rem ---------- 2. load config ----------
set "CONFIG_FILE=%~dp0release.config.bat"
if not exist "%CONFIG_FILE%" (
    echo ERROR: %CONFIG_FILE% not found.
    echo Copy tools\release.config.bat.example to tools\release.config.bat and fill it in.
    goto :fail
)
call "%CONFIG_FILE%"

rem ---------- 3. validate ----------
if not exist "%KEYSTORE_PATH%" (
    echo ERROR: keystore not found at "%KEYSTORE_PATH%"
    goto :fail
)
if "%UPDATE_MANIFEST_URL%"=="" (
    echo ERROR: UPDATE_MANIFEST_URL not set in release.config.bat
    goto :fail
)
if not defined DRY_RUN (
    if "%GH_REPO%"=="" (
        echo ERROR: GH_REPO not set in release.config.bat
        goto :fail
    )
)

rem ---------- 4. derive versionCode (monotonic from commit count) ----------
for /f %%c in ('git rev-list --count HEAD') do set "VERSION_CODE=%%c"
if "%VERSION_CODE%"=="" (
    echo ERROR: failed to derive versionCode from git
    goto :fail
)

echo.
echo === Releasing v%VERSION_NAME% (versionCode %VERSION_CODE%) ===
if defined DRY_RUN echo (dry run: no tag, no upload)
echo.

rem ---------- 5. build signed APK ----------
set "APP_VERSION_NAME=%VERSION_NAME%"
set "APP_VERSION_CODE=%VERSION_CODE%"

echo [1/6] Building signed release APK...
call "%REPO_ROOT%\gradlew.bat" :app:assembleRelease --no-daemon
if errorlevel 1 (
    echo ERROR: gradle build failed
    goto :fail
)

rem ---------- 6. locate, rename, hash APK ----------
set "APK_SRC="
for /f "delims=" %%f in ('dir /b /s "%REPO_ROOT%\app\build\outputs\apk\release\*.apk"') do (
    if not defined APK_SRC set "APK_SRC=%%f"
)
if not defined APK_SRC (
    echo ERROR: no APK produced under app\build\outputs\apk\release
    goto :fail
)

set "DIST_DIR=%REPO_ROOT%\dist"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
set "APK_NAME=human-radar-v%VERSION_NAME%.apk"
set "APK_OUT=%DIST_DIR%\%APK_NAME%"
copy /y "%APK_SRC%" "%APK_OUT%" >nul
if errorlevel 1 (
    echo ERROR: failed to copy APK to %APK_OUT%
    goto :fail
)

echo [2/6] Computing SHA-256 and size...
for /f "usebackq delims=" %%h in (`powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%APK_OUT%').Hash.ToLower()"`) do set "APK_SHA=%%h"
for /f "usebackq delims=" %%s in (`powershell -NoProfile -Command "(Get-Item '%APK_OUT%').Length"`) do set "APK_SIZE=%%s"

echo     APK : %APK_OUT%
echo     size: %APK_SIZE% bytes
echo     sha : %APK_SHA%

rem ---------- 7. generate update.json ----------
echo [3/6] Generating update.json...
set "MANIFEST=%DIST_DIR%\update.json"
set "RELEASE_NOTES_RAW="
for /f "usebackq delims=" %%n in (`git log -1 --pretty^=%%B`) do (
    if not defined RELEASE_NOTES_RAW (
        set "RELEASE_NOTES_RAW=%%n"
    ) else (
        set "RELEASE_NOTES_RAW=!RELEASE_NOTES_RAW!\n%%n"
    )
)
rem Escape any embedded double-quote
set "RELEASE_NOTES=!RELEASE_NOTES_RAW:"=\"!"

for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "[DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')"`) do set "PUBLISHED_AT=%%t"

set "APK_URL=https://github.com/%GH_REPO%/releases/download/v%VERSION_NAME%/%APK_NAME%"

(
    echo {
    echo   "versionCode": %VERSION_CODE%,
    echo   "versionName": "%VERSION_NAME%",
    echo   "minSdkVersion": 26,
    echo   "apkUrl": "%APK_URL%",
    echo   "sha256": "%APK_SHA%",
    echo   "sizeBytes": %APK_SIZE%,
    echo   "mandatory": false,
    echo   "releaseNotes": "!RELEASE_NOTES!",
    echo   "publishedAt": "%PUBLISHED_AT%"
    echo }
) > "%MANIFEST%"

echo     manifest: %MANIFEST%

if defined DRY_RUN (
    echo.
    echo DRY RUN complete. Artifacts in %DIST_DIR%
    echo No tag pushed, no GitHub release created.
    goto :ok
)

rem ---------- 8. git tag ----------
if not defined SKIP_TAG (
    echo [4/6] Creating git tag v%VERSION_NAME%...
    git tag -a "v%VERSION_NAME%" -m "Release v%VERSION_NAME%"
    if errorlevel 1 (
        echo ERROR: git tag failed (already exists?). Use SKIP_TAG=1 to bypass.
        goto :fail
    )
    git push origin "v%VERSION_NAME%"
    if errorlevel 1 (
        echo ERROR: failed to push tag
        goto :fail
    )
) else (
    echo [4/6] Skipping git tag (SKIP_TAG=1^)
)

rem ---------- 9. GitHub Release ----------
if not defined SKIP_GH_RELEASE (
    echo [5/6] Creating GitHub Release v%VERSION_NAME%...
    where gh >nul 2>nul
    if errorlevel 1 (
        echo ERROR: gh CLI not found in PATH. Install from https://cli.github.com/
        goto :fail
    )
    gh release create "v%VERSION_NAME%" "%APK_OUT%" "%MANIFEST%" ^
        --repo "%GH_REPO%" ^
        --title "v%VERSION_NAME%" ^
        --notes "%RELEASE_NOTES_RAW%"
    if errorlevel 1 (
        echo ERROR: gh release create failed
        goto :fail
    )
) else (
    echo [5/6] Skipping GitHub Release (SKIP_GH_RELEASE=1^)
)

rem ---------- 10. push update.json to gh-pages ----------
if not defined SKIP_GH_PAGES (
    echo [6/6] Publishing update.json to gh-pages branch...
    set "WORKTREE=%REPO_ROOT%\.gh-pages-tmp"
    if exist "!WORKTREE!" (
        git worktree remove --force "!WORKTREE!" 2>nul
        rmdir /s /q "!WORKTREE!" 2>nul
    )

    git fetch origin gh-pages
    if errorlevel 1 (
        echo NOTE: gh-pages branch not found on origin, creating...
        git worktree add -b gh-pages "!WORKTREE!"
    ) else (
        git worktree add "!WORKTREE!" gh-pages
    )
    if errorlevel 1 (
        echo ERROR: failed to add gh-pages worktree
        goto :fail
    )

    copy /y "%MANIFEST%" "!WORKTREE!\update.json" >nul
    pushd "!WORKTREE!"
    git add update.json
    git commit -m "Publish update manifest for v%VERSION_NAME%"
    git push origin gh-pages
    set "GH_PAGES_RC=!ERRORLEVEL!"
    popd

    git worktree remove --force "!WORKTREE!" 2>nul
    if not "!GH_PAGES_RC!"=="0" (
        echo ERROR: failed to push gh-pages
        goto :fail
    )
) else (
    echo [6/6] Skipping gh-pages publish (SKIP_GH_PAGES=1^)
)

:ok
echo.
echo === Done. v%VERSION_NAME% is live. ===
echo Users will see the update prompt the next time their app polls (within 6h^),
echo or immediately if they tap "Check for updates".
popd
endlocal
exit /b 0

:fail
echo.
echo === Release FAILED ===
popd
endlocal
exit /b 1
