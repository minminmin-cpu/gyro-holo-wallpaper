# Contributing

This project is free and open source. No account, payment or telemetry is involved.

## Web reference implementation

```bash
python tools/generate_assets.py
python tools/build_standalone.py
npm test                                    # 14 offline algorithm/contract tests
python tests/verify_render.py --capture     # headless Chromium pixel verification
python tools/serve.py                       # HTTPS preview at localhost:8443
```

`standalone.html` is a build artifact and is git-ignored; regenerate it rather than
committing it. Any change to `src/*.js` must keep `npm test` and
`tests/verify_render.py` green.

## Android app (stage 2, M1 onwards)

The Gradle project lives in `android/`. Build locally with:

```bash
cd android
./gradlew :app:assembleDebug
```

Release signing is configured entirely through environment variables
(`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); CI supplies
them from GitHub Secrets. **Never commit a keystore or a password.**

The GLSL in `android/app/src/main/assets/shader/holo.frag` is generated from
`src/holo-shader.js` — update the source and re-run the sync script instead of
editing two copies by hand.

## Rules of the road

- Do not commit user photos, generated wallpapers, upstream demo frames,
  verification screenshots, `.cert/` certificates or keystores (see `.gitignore`).
- Do not add analytics, advertising, account systems or network calls to the
  wallpaper service. The wallpaper must work fully offline once a profile exists.
- Keep the license notices in `THIRD_PARTY_NOTICES.md` intact when updating
  vendored code.
- Before opening a PR, state which Android version / device you tested on for
  rendering changes.
