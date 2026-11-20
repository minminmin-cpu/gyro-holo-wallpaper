"""把指定图片做成 Android 全套应用图标（adaptive + 各密度 legacy + 圆形 + 壁纸缩略图）。

用法（需带 Pillow 的解释器）:
  python tools/make_app_icon.py
  python tools/make_app_icon.py --src "D:/path/to/icon.jpg"
"""
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android" / "app" / "src" / "main" / "res"
DEFAULT_SRC = Path(
    r"D:/xwechat_files/wxid_pn2hnrrhurtx12_9b63/temp/RWTemp/2026-11/"
    r"d3c135cc0100cf6b8624f8ec924de713/fe8b5a115632e483efde2ebb4e7c4155.jpg"
)
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = 432
INNER_RATIO = 0.72


def square_crop(image: Image.Image) -> Image.Image:
    """居中裁正方形，略微上移以保住头部。"""
    w, h = image.size
    side = min(w, h)
    left = (w - side) // 2
    top = int(h * 0.05)
    if top + side > h:
        top = h - side
    return image.crop((left, top, left + side, top + side))


def rounded_mask(size: int, radius_ratio: float) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255,
    )
    return mask


def circle_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(DEFAULT_SRC))
    args = ap.parse_args()

    src = Path(args.src)
    if not src.exists():
        raise SystemExit(f"找不到图标源图: {src}")

    base = square_crop(Image.open(src).convert("RGB"))
    print(f"源图 {src.name} -> 裁切 {base.size}")

    written = 0
    # 1) legacy 各密度：方形不透明 + 圆形（透明角），覆盖 Android 7.x
    for density, size in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        base.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher.png", "PNG")
        round_img = base.resize((size, size), Image.LANCZOS).convert("RGBA")
        round_img.putalpha(circle_mask(size))
        round_img.save(folder / "ic_launcher_round.png", "PNG")
        written += 2

    # 2) adaptive icon：前景内容占 72% 居中，四周留透明；背景是模糊暗化的同图
    drawable = RES / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    inner = int(ADAPTIVE * INNER_RATIO)
    foreground = Image.new("RGBA", (ADAPTIVE, ADAPTIVE), (0, 0, 0, 0))
    head = base.resize((inner, inner), Image.LANCZOS).convert("RGBA")
    head.putalpha(rounded_mask(inner, 0.18))
    offset = (ADAPTIVE - inner) // 2
    foreground.paste(head, (offset, offset), head)
    foreground.save(drawable / "ic_launcher_foreground.png", "PNG")
    written += 1

    background = base.resize((ADAPTIVE, ADAPTIVE), Image.LANCZOS).filter(ImageFilter.GaussianBlur(16))
    background = Image.blend(background, Image.new("RGB", (ADAPTIVE, ADAPTIVE), (7, 10, 24)), 0.45)
    background.save(drawable / "ic_launcher_background.png", "PNG")
    written += 1

    # 3) 动态壁纸选择器里的缩略图（原来的 vector 换成同一张图）
    thumb = base.resize((512, 512), Image.LANCZOS).convert("RGBA")
    thumb.putalpha(rounded_mask(512, 0.12))
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    thumb.save(nodpi / "holo_thumb.png", "PNG")
    written += 1

    print(f"已生成 {written} 个图标资源")
    for p in sorted(RES.rglob("ic_launcher*")):
        print(f"  {p.relative_to(ROOT)}  {p.stat().st_size} B")


if __name__ == "__main__":
    main()
