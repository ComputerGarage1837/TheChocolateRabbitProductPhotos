# Changelog

Each `## vX.Y.Z` section becomes the release notes for that version.

## v1.0.7 — 2026-09-19

### Improved
- Edge repair. Where the model cuts slightly inside the product along one edge (typically a
  dark product against a dark background), pixels just outside the outline that clearly match
  the product colour next to them, and clearly do not match the background further out, are
  reclaimed. Kept to a narrow band so it cannot run off into the background.
- Removed the gap-closing step from Balanced and Tight: it could paint a wedge of background
  into a concave corner of the product.

## v1.0.6 — 2026-09-19

### Added
- Batch mode. Tap "Batch" on the home screen, pick any number of photos, and they are processed
  one after another with the current template and settings. Each photo gets its own
  "Auto clean-up" switch and a "Show original" switch; "Save all" saves every photo in the
  version you chose.

### Improved
- Two-pass background removal: after the first pass, the model runs again zoomed in on the
  product so it fills the frame, and the two results are blended. Cleaner edges, especially on
  cluttered backgrounds.
- APK is about 60 MB smaller: only the 64-bit ARM runtime is included (phones only).

## v1.0.5 — 2026-09-19

### Changed
- New background-removal model, built into the app. Google's Play services model kept dropping
  large parts of products (a whole side of a case, for example). The app now bundles the ISNet
  dichotomous segmentation model and runs it with ONNX Runtime, entirely on the phone. Tested
  on a real product photo that failed before: the whole product is kept with clean edges.
  Nothing is downloaded any more and Google Play services is no longer needed.
- Because the model is inside the app, this update is about 190 MB. First launch after
  installing takes a few extra seconds while the model is unpacked.
- "Balanced" cut-out no longer uses the colour-based reclaim (it pulled in background clutter
  on busy backgrounds). "Generous" still does, for plain backdrops.

## v1.0.4 — 2026-09-19

### Added
- In-app updates. The app checks for a new version on start and offers to download and install
  it, with release notes, a progress bar and a "skip this version" option. There is also a
  "Check for updates" button in Settings.
- "Compare with original" switch on the review screen shows the photo as taken next to the result.

### Improved
- Product outlines again. The cut-out now learns the backdrop colours from the photo and keeps
  any connected part of the product that clearly is not backdrop, even when the model missed
  the whole part (a lid, a label, a ribbon, a differently coloured wrapper). Small gaps and
  enclosed holes in the outline are filled. Shadows stay out.

## v1.0.3 — 2026-09-19

### Changed
- First release signed with the shop's permanent key. Install this one over the top of any
  earlier version after uninstalling it once; every version after this installs as a normal
  update.

## v1.0.2 — 2026-09-19

### Improved
- Much better product outlines. The cut-out no longer relies on the model's guess alone: it
  learns the background colour from the photo and reclaims any nearby pixels that clearly are
  not background (thin parts, ribbons, shiny or light edges the model missed), then snaps the
  edge to the real edges in the photo and removes stray specks.
- New "Cut-out sensitivity" setting (Tight / Balanced / Generous) for products that still get
  clipped or that keep bits of background.

## v1.0.1 — 2026-09-19

### Fixed
- The background-removal model was never downloaded on phones where the app was installed from
  the APK rather than the Play Store, so every photo failed with "model is still downloading".
  The app now downloads it itself on first launch, with a progress bar on the home screen, and
  the review screen waits for it instead of failing.

## v1.0.0 — 2026-09-19

### Added
- Take a photo with a square guide, or import one from the phone.
- On-device background removal (ML Kit subject segmentation). Only the background is replaced;
  the product itself is never recoloured by this step.
- Bundled cream 1512×1512 template, with a Settings option to choose a different square image
  or reset to the built-in one.
- Review screen with an "As shot / Auto clean-up" toggle and a Brightness slider. Both apply
  to the product only.
- Products are centred and sized consistently (80% of the frame by default, adjustable).
- In-app gallery of finished photos with share and delete. Photos are also saved to
  Pictures / Chocolate Rabbit Products on the phone.
