# The Chocolate Rabbit Product Photos

Android app that turns a phone photo of a product into a uniform website product shot:

1. Take a photo (or import one from the phone).
2. The background is removed on-device with ML Kit subject segmentation. Only the background
   is touched; the product pixels are kept exactly as shot.
3. The product is placed on the shop's square template (bundled cream background, 1512 x 1512).
4. Optional one-tap **Auto clean-up** (levels, gentle sharpening, a touch of saturation) and a
   **Brightness** slider. Both apply to the product only, never the template.
5. Saved photos live in the in-app gallery and are also copied to
   `Pictures/Chocolate Rabbit Products` on the phone.

## Settings

- **Background template**: pick any square image from the phone to replace the built-in one,
  or reset to the bundled cream template.
- **Start with Auto clean-up**: pre-select the cleaned-up version on the review screen.
- **Centre and size the product**: keeps every product the same size and position (default on,
  80% of the frame; adjustable).
- **Also save to phone gallery**: on by default.

## Install

1. Download the latest `Chocolate-Rabbit-Product-Photos-<version>.apk` from the
   [Releases](../../releases) page, on the phone itself.
2. Open the downloaded file and allow installs from this source when Android asks.
3. First launch needs internet once so Google Play services can download the background-removal
   model. After that the app works offline.
4. Future updates install from inside the app: it checks the release feed on start, shows the
   changelog, downloads the APK with a progress bar, verifies its SHA-256 and hands it to Android
   to install. Settings also has a "Check for updates" button. See `UPDATE_FORMAT.md`.

Minimum Android version is 7.0 (API 24). The phone needs Google Play services.

## Releases

Every push to `main` builds a signed release APK with GitHub Actions
(`.github/workflows/build.yml`). When `main` carries a `VERSION_NAME` (in `gradle.properties`)
that has no release yet, the workflow creates the `vX.Y.Z` tag and a GitHub Release with the APK
attached and the matching `CHANGELOG.md` section as its notes, then commits the matching
`release/update.json` (with SHA-256 and size) to `main` for the in-app updater.

To ship a new version:

1. Bump `VERSION_NAME` in `gradle.properties` (for example `1.0.1`).
2. Add a `## v1.0.1 — date` section to `CHANGELOG.md`.
3. Push to `main`. The release appears a few minutes later.

### Signing key (one-time setup)

Android only installs a newer APK over an older one when both are signed with the same key.
Until the key is configured the workflow signs with a temporary key and prints a warning, so the
APK installs but each later version needs the previous one uninstalled first.

Create a permanent key once and store it in the repository secrets
(Settings > Secrets and variables > Actions > Repository secrets):

```
keytool -genkeypair -v -keystore chocolaterabbit-release.jks -alias chocolaterabbit \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 chocolaterabbit-release.jks
```

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the base64 output above |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | optional, defaults to `chocolaterabbit` |
| `KEY_PASSWORD` | optional, defaults to `KEYSTORE_PASSWORD` |

Keep the `.jks` file somewhere safe. If it is lost, future versions cannot update over installed ones.

## Building locally

Requirements: Android Studio Ladybug (2024.2) or newer, JDK 17, Android SDK 35.

- Open the project folder in Android Studio and press **Run**, or
- from a terminal: `./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.

## Project layout

```
app/src/main/java/com/chocolaterabbit/productphotos/
  MainActivity.kt               navigation between screens
  AppContainer.kt               wires the pieces together
  data/SettingsRepository.kt    user settings (DataStore)
  data/TemplateStore.kt         bundled + custom template handling
  data/PhotoStore.kt            in-app gallery + export to phone gallery
  processing/ImageLoader.kt     decode, fix rotation, centre-crop to 1:1
  processing/BackgroundRemover.kt  ML Kit segmentation -> alpha mask
  processing/AutoEnhancer.kt    product-only auto clean-up
  processing/Compositor.kt      places the cut-out on the template (+ brightness)
  processing/ProductPhotoPipeline.kt
  ui/screens/                   Home (gallery), Camera, Edit (review), Settings, PhotoViewer
app/src/main/res/drawable/template_default.jpg   the bundled template
```
