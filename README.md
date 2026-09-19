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

## Building

Requirements: Android Studio Ladybug (2024.2) or newer, JDK 17, Android SDK 35.

- Open the project folder in Android Studio and press **Run**, or
- from a terminal: `./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.

Minimum Android version is 7.0 (API 24). The phone needs Google Play services; the segmentation
model is downloaded automatically the first time the app runs (needs internet once), after that
everything works offline.

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
