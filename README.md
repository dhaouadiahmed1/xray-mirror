# X-Ray Mirror — v0.1

An Android app that turns your front camera into an "X-ray mirror": it tracks
your face (468 points) and your hand live, and draws a stylized anatomy
overlay on top of you — skull, brain, eyes, muscles, hand bones — just like
the X-Ray Mirror video by Hashem Alghaili.

## What it is — and what it is NOT

**It is NOT a real X-ray, and NOT a scan of you.** A phone camera cannot see
through skin. The overlay is a simplified drawing fitted to YOUR face
landmarks (eyes, nose, jaw, forehead) and scaled with the "Fit to my head"
slider. The anatomy is a generic model, not your anatomy, and this app is
**not a medical device**. The app says so on screen, on first launch, and
here — on purpose.

## Features (v0.1)

- Front camera preview (CameraX), portrait, dark theme only
- Face tracking: Google MediaPipe FaceLandmarker, **468 points**, on-device
- Hand tracking: Google MediaPipe HandLandmarker, 21 points, on-device
- Live stats: `468 pts`, live fps, `Face: locked / searching`,
  `Hand: locked / searching`
- Layers (tap to toggle): Skull ON, Brain ON, Eyes ON, Muscles OFF,
  Hand bones OFF (anchored to your real hand when visible)
- Look: **X-ray** (cyan translucent, default) / **Solid** / **Skin only**
- Reveal: Whole head (default) / Left half / Right half / Top only /
  Lower face — the overlay is clipped to that part of your face
- Fit to my head slider: 100–130%, default **117%**
- Brightness slider: 30–100%, default **80%** (overlay alpha)
- No account, no server, no ads

## First run — model download

The two MediaPipe models are **not bundled in the APK** (about 3 MB + 8 MB).
On first launch the app downloads them once from Google's official model
storage and stores them in its private files, then works fully offline:

- `face_landmarker.task` —
  `https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task`
- `hand_landmarker.task` —
  `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`

Use Wi-Fi for the first launch. If the download fails, the app shows the
error and a Retry button.

## Tech

- Kotlin + Jetpack Compose, single `MainActivity`, minSdk 26, targetSdk 34
- CameraX 1.3.4 (`camera-core`, `camera-camera2`, `camera-lifecycle`,
  `camera-view`), front camera, RGBA frames
- MediaPipe Tasks Vision 0.10.14 (`tasks-vision`), LIVE_STREAM mode
- AGP 8.5.2 / Kotlin 2.0.0 / Gradle 8.7 / JDK 17 (same setup as
  Pêche Débutant, which builds green)

## Project layout

```
xray-mirror/
├── README.md
├── .github/workflows/android.yml   ← add this file on GitHub web (see below)
└── android/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    ├── gradle/wrapper/gradle-wrapper.properties
    └── app/
        ├── build.gradle.kts
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/app/xraymirror/
            │   ├── MainActivity.kt    ← camera, permissions, UI, controls
            │   ├── ModelManager.kt    ← one-time model download
            │   ├── Tracker.kt         ← MediaPipe face + hand wrappers
            │   ├── Overlay.kt         ← skull/brain/eyes/muscles/hand drawing
            │   └── ui/theme/Theme.kt  ← dark navy + cyan theme
            └── res/values/
                ├── strings.xml
                └── themes.xml
```

## Setup — steps for AHMED (phone, GitHub web)

Muse's GitHub App cannot create a repository or a workflow file via the API,
so these two steps are done by hand, once:

1. **Create the empty repo:** on github.com, New repository →
   name `xray-mirror`, Public, **do NOT tick "Add a README"** (empty repo),
   Create. Then open the Muse GitHub App installation and select the new
   `xray-mirror` repo (same as was done for the other apps).
2. **Add the workflow file:** in the repo on GitHub web → Add file →
   Create new file → path must be exactly
   `.github/workflows/android.yml` — **no space after the slash**
   (`.github/ workflows/...` is a different folder and no build will run).
   Paste the exact content of `.github/workflows/android.yml` from this
   project, Commit directly to `main`.

After that, every push to `main` builds the debug APK in GitHub Actions
(about 3–5 minutes). Download it from the run → Artifacts →
`xray-mirror-debug-apk` → install the APK on the phone.

## Test steps (HONOR 400)

1. Install the APK, open **X-Ray Mirror**.
2. Tap **Allow camera** when asked.
3. First launch: let the two models download (Wi-Fi is best). Wait for the
   mirror screen — do not close the app during the download.
4. Point the front camera at your face, in good light. You should see
   `Face: locked` and the skull + brain + eyes overlay following your face.
5. Try it:
   - Layers: turn Muscles ON, turn Brain OFF — the overlay must change.
   - Look: switch X-ray → Solid → Skin only (no overlay at all).
   - Reveal: Left half / Right half / Top only / Lower face — only that
     part of the face shows the overlay.
   - Fit slider: 100% → 130% — the skull grows/shrinks around your face.
   - Brightness slider: the overlay fades in/out.
   - Hold your hand up next to your face, turn **Hand bones** ON — the
     hand skeleton should follow your hand.
6. Move your head side to side and tilt it — the overlay must stay stuck
   to your face. If it lags or drifts, note the phone model + what happened.

## Known limits (v0.1)

- The anatomy is a simplified drawing, not a 3D anatomy dataset — the
  original video uses a full 3D dataset; v0.1 draws the skull/brain/muscles
  as 2D shapes fitted to your landmarks.
- One face and one hand at a time.
- Needs good light; tracking is slower in the dark.
- The overlay follows the face in 2D (no 3D head-pose rotation yet).
