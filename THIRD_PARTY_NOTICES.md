# Third-party notices

This project is MIT-licensed (see `LICENSE`). The following third-party material is
redistributed or adapted, and its original notices are preserved here.

## 1. RuiC-card-skill — MIT

- Upstream: <https://github.com/HRuiCcc/RuiC-card-skill>
- Copyright (c) 2026 HRuiCcc
- Used for: the holographic foil / parallax GLSL (`assets/web-template/app.js`
  `common` + `frontFragment`), ported into `src/holo-shader.js` and, in stage 2,
  into `android/app/src/main/assets/shader/holo.frag`; the five-layer asset contract
  and the `card-config.json` parameter schema.
- Upstream LICENSE also states: *"Generated artwork and user-uploaded references
  are not distributed with this repository and are not covered by this software license."*
  Accordingly, no upstream demo frames, generated artwork or user photos are
  redistributed in this repository (see `.gitignore`).

Full license text:

```
MIT License

Copyright (c) 2026 HRuiCcc

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 2. kenchris/sensor-polyfills — BSD-3-Clause

- Upstream: <https://github.com/kenchris/sensor-polyfills>
- Copyright (c) 2017, Intel Corporation
- Used for: the optional Generic Sensor API polyfill channel
  (`vendor/sensor-polyfills/motion-sensors.js`, `sensor.js`).
- Full license text is preserved verbatim at `vendor/sensor-polyfills/LICENSE.md`;
  provenance and the reason it is optional are documented in
  `vendor/sensor-polyfills/SOURCE.md`.

## 3. Not redistributed

- Frames extracted from upstream `demo-after.gif` / `demo-before.gif` were used only
  for local visual comparison during development; they are git-ignored and are not
  part of any release.
- User-uploaded photographs and wallpapers generated from them stay on the user's
  device. They are git-ignored and are not covered by this project's license.
- `assets/*.png` in this repository are deterministic, programmatically generated
  demo layers produced by `tools/generate_assets.py`; they contain no third-party
  artwork.
