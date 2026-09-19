# Changelog

Each `## vX.Y.Z` section becomes the release notes for that version.

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
