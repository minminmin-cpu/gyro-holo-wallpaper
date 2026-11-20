"""程序化生成「星穹棱镜」演示用分层素材（确定性，不依赖外部出图模型）。
产物符合 RuiC-card-skill 的 validate_assets.py 契约：
  subject/text/effects = 真 RGBA；background = 不透明；lineart = 白底黑线；全部同画布。
用法: python tools/generate_assets.py
"""
from __future__ import annotations

import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1024, 1536
OUT = Path(__file__).resolve().parents[1] / "assets"
OUT.mkdir(parents=True, exist_ok=True)
random.seed(20261112)

FONT_CANDIDATES = [
    "C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf", "/System/Library/Fonts/PingFang.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
]


def font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def save(img: Image.Image, name: str) -> None:
    img.save(OUT / f"{name}.png", "PNG")
    print(f"  {name}.png  {img.size}  {img.mode}")


def make_background() -> Image.Image:
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cx, cy = W * 0.5, H * 0.43
    r = np.sqrt(((xx - cx) / (W * 0.78)) ** 2 + ((yy - cy) / (H * 0.66)) ** 2)
    glow = np.clip(1.0 - r, 0.0, 1.0) ** 1.7
    violet = np.array([58, 34, 112], dtype=np.float32)
    teal = np.array([12, 62, 96], dtype=np.float32)
    base = np.array([7, 9, 26], dtype=np.float32)
    band = (yy / H)[..., None]
    arr = base + glow[..., None] * (violet * (1 - band) + teal * band) * 1.25
    arr += np.sin(xx / 190.0 + yy / 260.0)[..., None] * 6.0
    arr = np.clip(arr, 0, 255).astype(np.uint8)
    img = Image.fromarray(arr, "RGB").convert("RGBA")

    overlay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    for i in range(7):
        rr = 210 + i * 78
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=(168, 190, 255, 20 + i * 3), width=2)
    for _ in range(720):
        x, y = random.randint(0, W - 1), random.randint(0, H - 1)
        b = random.randint(90, 255)
        rad = random.choice([1, 1, 1, 2])
        d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=(b, b + 10 if b < 245 else 255, 255, random.randint(60, 210)))
    img = Image.alpha_composite(img, overlay)
    return img.convert("RGB")


def make_subject() -> Image.Image:
    """中心棱镜 + 星芒，主体层带真实 alpha。"""
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    cx, cy = W // 2, 690
    top, bottom, left, right = cy - 350, cy + 350, cx - 285, cx + 285

    mask = Image.new("L", (W, H), 0)
    dm = ImageDraw.Draw(mask)
    dm.polygon([(cx, top), (right, cy), (cx, bottom), (left, cy)], fill=230)
    dm.polygon([(cx, top), (right, cy), (cx, cy + 40)], fill=255)
    dm.polygon([(cx, top), (left, cy), (cx, cy + 40)], fill=190)

    grad = np.zeros((H, W, 4), dtype=np.uint8)
    yy = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
    c0 = np.array([118, 214, 255], dtype=np.float32)
    c1 = np.array([214, 126, 255], dtype=np.float32)
    rgb = c0 * (1 - yy) + c1 * yy + np.sin(yy * 18) * 18
    grad[..., 0] = np.clip(rgb[..., 0], 0, 255).astype(np.uint8)
    grad[..., 1] = np.clip(rgb[..., 1], 0, 255).astype(np.uint8)
    grad[..., 2] = np.clip(rgb[..., 2], 0, 255).astype(np.uint8)
    grad[..., 3] = 255
    body = Image.fromarray(grad, "RGBA")
    body.putalpha(mask)
    layer = Image.alpha_composite(layer, body)

    d = ImageDraw.Draw(layer)
    edges = [(cx, top), (right, cy), (cx, bottom), (left, cy), (cx, top)]
    d.line(edges, fill=(236, 246, 255, 235), width=5, joint="curve")
    for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        d.line([(cx, cy + 40), (cx + dx * 285, cy + dy * 350 + 40)], fill=(255, 255, 255, 110), width=3)
    d.line([(left, cy), (right, cy)], fill=(255, 255, 255, 150), width=3)
    for length, alpha, width in [(430, 190, 3), (300, 220, 2)]:
        d.line([(cx - length, cy), (cx + length, cy)], fill=(255, 245, 255, alpha), width=width)
        d.line([(cx, cy - length), (cx, cy + length)], fill=(255, 245, 255, alpha), width=width)
    d.ellipse([cx - 34, cy - 34, cx + 34, cy + 34], fill=(255, 255, 255, 235))

    # 柔和外发光，同时清掉极低的 alpha 噪声以保留真正的透明像素。
    halo = layer.getchannel("A").filter(ImageFilter.GaussianBlur(26))
    halo = halo.point(lambda v: int(v * 0.42) if v > 10 else 0)
    glow_img = Image.new("RGBA", (W, H), (150, 130, 255, 0))
    glow_img.putalpha(halo)
    layer = Image.alpha_composite(glow_img, layer)
    return layer


def make_lineart(subject: Image.Image) -> Image.Image:
    """与主图同源的轮廓：对 subject 的 alpha 做边缘检测，再反相成白底黑线。"""
    edges = subject.getchannel("A").filter(ImageFilter.FIND_EDGES)
    edges = edges.point(lambda v: 255 if v > 24 else 0)
    return Image.eval(edges, lambda v: 255 - v).convert("L")


def make_effects() -> Image.Image:
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for _ in range(86):
        ang = random.uniform(0, math.tau)
        rad = random.uniform(210, 620)
        x = W / 2 + math.cos(ang) * rad * 0.82
        y = 690 + math.sin(ang) * rad * 1.12
        if not (0 < x < W and 0 < y < H):
            continue
        s = random.randint(5, 20)
        col = random.choice([(255, 214, 244, 210), (196, 236, 255, 200), (255, 236, 186, 190)])
        d.ellipse([x - s, y - s * 0.42, x + s, y + s * 0.42], fill=col)
        d.line([(x - s * 2, y), (x + s * 2, y)], fill=(255, 255, 255, 90), width=1)
    return img


def make_text() -> Image.Image:
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = 46
    d.rounded_rectangle([m, m, W - m, H - m], radius=30, outline=(228, 222, 255, 190), width=3)
    d.rounded_rectangle([m + 14, m + 14, W - m - 14, H - m - 14], radius=22, outline=(228, 222, 255, 70), width=2)

    f_small, f_mid, f_big = font(26), font(40), font(88)
    d.text((W / 2, 122), "CELESTIAL ARCHIVE  /  天穹典藏", font=f_small, fill=(226, 220, 255, 220), anchor="mm")
    d.line([(W / 2 - 150, 158), (W / 2 + 150, 158)], fill=(210, 195, 255, 130), width=2)

    d.text((W / 2, H - 250), "星穹棱镜", font=f_big, fill=(250, 247, 255, 245), anchor="mm", stroke_width=1, stroke_fill=(80, 60, 140, 160))
    d.text((W / 2, H - 172), "PRISM OF THE CELESTIAL", font=f_mid, fill=(206, 198, 255, 225), anchor="mm")
    d.line([(W / 2 - 210, H - 132), (W / 2 + 210, H - 132)], fill=(210, 195, 255, 120), width=2)
    d.text((W / 2, H - 92), "No.001 / 001   ·   HOLOGRAPHIC EDITION", font=f_small, fill=(214, 208, 255, 210), anchor="mm")

    for x, y, sx, sy in [(m + 26, m + 26, 1, 1), (W - m - 26, m + 26, -1, 1), (m + 26, H - m - 26, 1, -1), (W - m - 26, H - m - 26, -1, -1)]:
        d.line([(x, y), (x + sx * 44, y)], fill=(255, 232, 190, 220), width=4)
        d.line([(x, y), (x, y + sy * 44)], fill=(255, 232, 190, 220), width=4)
    return img


def main() -> None:
    print("生成分层素材 ->", OUT)
    bg = make_background()
    subject = make_subject()
    save(bg, "background")
    save(subject, "subject")
    save(make_lineart(subject), "lineart")
    save(make_effects(), "effects")
    save(make_text(), "text")
    print("完成。可用 RuiC-card-skill 的 validate_assets.py 校验。")


if __name__ == "__main__":
    main()
