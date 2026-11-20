"""把 ESM 源码 + 分层 PNG 打包成单文件 HTML（零依赖、可 file:// 直接打开）。

用法:
  python tools/build_standalone.py
  python tools/build_standalone.py --assets examples/photo-test/assets \
      --out examples/photo-test/photo-wallpaper.html \
      --params '{"ghost":0.62,"trail":1.25}'
"""
from __future__ import annotations

import argparse
import base64
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
ORDER = ["config.js", "math.js", "holo-shader.js", "gyro.js", "renderer.js", "main.js"]
KEYS = ["subject", "background", "text", "lineart", "effects"]


def strip_imports(source: str) -> str:
    out, skipping = [], False
    for line in source.splitlines():
        if not skipping and line.lstrip().startswith("import "):
            if ";" in line:
                continue
            skipping = True
            continue
        if skipping:
            if ";" in line:
                skipping = False
            continue
        out.append(line)
    return "\n".join(out)


def concat() -> str:
    parts = []
    for name in ORDER:
        text = (SRC / name).read_text(encoding="utf-8")
        text = strip_imports(text)
        text = re.sub(r"^export\s+", "", text, flags=re.MULTILINE)
        parts.append(f"/* --- src/{name} --- */\n{text}")
    return "\n\n".join(parts)


def assets_script(asset_dir: Path, params: dict | None) -> str:
    pairs = []
    for key in KEYS:
        data = (asset_dir / f"{key}.png").read_bytes()
        uri = "data:image/png;base64," + base64.b64encode(data).decode("ascii")
        pairs.append(f'  "{key}": "{uri}"')
    out = "window.__WALLPAPER_ASSETS__ = {\n" + ",\n".join(pairs) + "\n};"
    if params:
        out += "\nwindow.__HOLO_PARAMS__ = " + json.dumps(params, ensure_ascii=False) + ";"
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", default=str(ROOT / "assets"))
    ap.add_argument("--out", default=str(ROOT / "standalone.html"))
    ap.add_argument("--params", default="")
    args = ap.parse_args()

    asset_dir = Path(args.assets)
    params = json.loads(args.params) if args.params else None
    html = (ROOT / "index.html").read_text(encoding="utf-8")
    marker_assets = '<script>window.__WALLPAPER_ASSETS__ = null;</script>'
    marker_module = '<script type="module" src="./src/main.js"></script>'
    assert marker_assets in html, "index.html 缺少素材注入标记"
    assert marker_module in html, "index.html 缺少模块入口标记"

    js = concat()
    html = html.replace(marker_assets, "<script>\n" + assets_script(asset_dir, params) + "\n</script>")
    html = html.replace(marker_module, "<script>\n" + js + "\n</script>")
    target = Path(args.out)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(html, encoding="utf-8")
    print(f"已生成 {target}  ({target.stat().st_size / 1024:.0f} KB)")
    print(f"  素材目录: {asset_dir}   参数覆盖: {params or '无'}")


if __name__ == "__main__":
    main()
