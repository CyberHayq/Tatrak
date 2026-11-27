# Store and README screenshot cards

Six promotional cards (1080x1920) built from real emulator captures of the app, laid on Tatrak's
own "Federation" background (ink, a hairline network of nodes, messages in flight). The capture
procedure, demo data and per-scene settings are in the runbook
[knowledge/ops/store-screenshots.md](../../knowledge/ops/store-screenshots.md).

Python 3 and Pillow only; nothing here is part of the app build.

| File | What it does |
|------|--------------|
| `compose.py` | Captures `01.png`..`06.png` → `fastlane/metadata/android/en-US/images/phoneScreenshots/<NN>.png` (the README shows the same files); always also the F-Droid feature graphic `fastlane/metadata/android/en-US/images/featureGraphic.png` (1024x500) |
| `make_media.py` | Generated avatars and photos for the demo data (no personal files) |
| `../test-server/seed_showcase.sh` | Demo accounts, roster, groups and chat history on the test server |

```sh
# trial run into a scratch folder (keeps the committed images untouched)
python3 -I tools/store-screenshots/compose.py <captures dir> --out <scratch dir>
# final run: writes into fastlane/ of this repository
python3 -I tools/store-screenshots/compose.py <captures dir>
# only the feature graphic (no captures needed)
python3 -I tools/store-screenshots/compose.py
```

- Card texts, accents and glow tints are the `CARDS` table at the top of `compose.py`; the order
  is the capture number. The feature graphic's lines are `TAGLINE` and `FEATURES` next to it
  (icon: the store `icon.png`; bullets in the first four card accents). Output is deterministic (fixed seeds): the same captures give the same
  bytes.
- The emulator's screencap has the real status bar and navigation bar but no camera cut-out, so
  the script draws a 19 dp punch-hole camera with a lens glint at the top centre. Pass
  `--no-camera` if the captures already show one.
- A capture is scaled to a 580 px wide screen with its top at y = 495; a capture taller than
  about 21.6:9 does not fit and the script stops with an error.
