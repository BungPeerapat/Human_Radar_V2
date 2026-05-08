# Releasing the Android App

The release flow is fully automated. Tag → CI → user prompt.

## One-time setup

### 1. Generate a release keystore

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias human-radar \
  -keyalg RSA -keysize 2048 -validity 10000
```

Save `release.jks` somewhere safe (1Password, encrypted backup). **Losing it
means every existing user has to uninstall before they can update.**

### 2. Configure GitHub secrets

In `Settings → Secrets and variables → Actions`:

| Type   | Name                  | Value |
|--------|-----------------------|-------|
| Secret | `KEYSTORE_BASE64`     | `base64 -w0 release.jks` |
| Secret | `KEYSTORE_PASSWORD`   | keystore password |
| Secret | `KEY_ALIAS`           | `human-radar` |
| Secret | `KEY_PASSWORD`        | key password |
| Var    | `UPDATE_MANIFEST_URL` | `https://<owner>.github.io/<repo>/update.json` |

### 3. Enable GitHub Pages

`Settings → Pages → Build and deployment → Branch: gh-pages / (root)`

## Cutting a release

You have **three** options depending on where your keystore lives.

### Option A — Local end-to-end (`tools\release.bat`)

Best when your keystore is on your dev machine and you want zero CI dependency.

```cmd
:: One-time setup
copy tools\release.config.bat.example tools\release.config.bat
notepad tools\release.config.bat
:: fill in keystore path/passwords + GH_REPO + UPDATE_MANIFEST_URL

:: Each release
tools\release.bat 1.2.0
```

The script builds + signs the APK, generates `update.json`, creates the git
tag, uploads to GitHub Releases (via `gh` CLI), and pushes `update.json` to the
`gh-pages` branch — all in one shot.

Add `--dry-run` to test the build + manifest without uploading anything:

```cmd
tools\release.bat 1.2.0 --dry-run
```

You can also skip individual steps via env vars set in `release.config.bat`:
`SKIP_TAG`, `SKIP_GH_RELEASE`, `SKIP_GH_PAGES`.

### Option B — Tag-only (`tools\tag-release.bat`)

Best when your keystore lives in CI secrets and you just want to trigger a
release.

```cmd
git commit -am "Prep v1.2.0"
tools\tag-release.bat 1.2.0
```

GitHub Actions does the rest.

### Option C — Manual git tag

```bash
git commit -am "Prep v1.2.0"
git tag v1.2.0
git push origin v1.2.0
```

CI will:

1. Resolve `versionCode` from `git rev-list --count HEAD` (monotonic) and
   `versionName` from the tag.
2. Build a signed release APK.
3. Hash + measure the APK and write `update.json`.
4. Create a GitHub Release with the APK + manifest attached.
5. Push `update.json` to the `gh-pages` branch (the URL the app polls).

## What users see

- **Auto check**: when the app starts (debounced 6 hours), it fetches
  `update.json`. If `versionCode > BuildConfig.VERSION_CODE`, an update dialog
  appears with release notes and Update / Later / Skip.
- **Manual check**: the Config tab has a "Check for updates" button.
- **Mandatory update**: set `"mandatory": true` in `update.json`. The dialog
  becomes non-cancellable.

## Skipping a buggy release

If a release goes out broken:

1. Bump `versionCode` and ship a fix as a new tag (the recommended path).
2. Or edit `update.json` on the `gh-pages` branch directly to point at an older
   working version. `versionCode` must stay strictly greater than what the
   broken users are running.

## Local release build (sanity check, not for distribution)

```bash
APP_VERSION_NAME=1.2.0 \
APP_VERSION_CODE=42 \
UPDATE_MANIFEST_URL=https://example.com/update.json \
./gradlew :app:assembleRelease
```

Without keystore env vars set, the resulting APK is unsigned and not
installable in place over a CI-signed build. Use it only for local smoke tests.
