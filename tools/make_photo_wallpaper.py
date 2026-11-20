"""用一张真实照片生成「闪光 + 重影（残影）」动态壁纸测试成品。

流程：照片转正 -> 按 RuiC 契约生成 5 层素材 -> 注入强化重影参数 ->
调用 build_standalone.py 产出可双击打开的单文件 HTML -> 用 RuiC 校验脚本体检。

用法:
  python tools/make_photo_wallpaper.py
  python tools/make_photo_wallpaper.py --photo "D:/some/photo.jpg"
"""
from __future__ import annotations

import argparse
import json
import math
import random
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "examples" / "photo-test"
ASSETS = OUT / "assets"
W, H = 1024, 1536
DEFAULT_PHOTO = Path(r"C:\Users\1\Pictures\Saved Pictures\微信图片_20210330223758.jpg")
RUIC_VALIDATE = Path(r"C:\Users\1\.workbuddy\skills\ruic-card-skill\scripts\validate_assets.py")

FONTS = [
    "C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf", "/System/Library/Fonts/PingFang.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
]
LANCZOS = Image.Resampling.LANCZOS


def font(size: int):
    for p in FONTS:
        if Path(p).exists():
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def cover(img: Image.Image, w: int, h: int) -> Image.Image:
    scale = max(w / img.width, h / img.height)
    r = img.resize((round(img.width * scale), round(img.height * scale)), LANCZOS)
    x, y = (r.width - w) // 2, (r.height - h) // 2
    return r.crop((x, y, x + w, y + h))


def make_background(photo: Image.Image) -> Image.Image:
    """照片的模糊暗化延展：卡片之外的氛围底，同时也是卡面底图。"""
    base = cover(photo, W, H).filter(ImageFilter.GaussianBlur(32))
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    v = np.clip(1.0 - ((xx - W / 2) / (W * 0.82)) ** 2 - ((yy - H / 2) / (H * 0.72)) ** 2, 0, 1)
    arr = np.asarray(base, dtype=np.float32) * (0.26 + 0.34 * v[..., None])
    arr += np.array([7, 9, 24], dtype=np.float32)
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB")


def make_subject(photo: Image.Image):
    """完整横图放在卡面中央，圆角羽化 alpha —— 边缘是重影出现的位置。"""
    pw = W - 112
    ph = round(pw * photo.height / photo.width)
    p = photo.resize((pw, ph), LANCZOS)
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, pw - 1, ph - 1], radius=34, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(5))
    pos = ((W - pw) // 2, (H - ph) // 2)
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    canvas.paste(p, pos, mask)
    return canvas, p, pos, mask


def make_lineart(p: Image.Image, pos, mask) -> Image.Image:
    """从照片本身提取轮廓（与主体同源），白底黑线，深度独立于主体 -> 错位重影。"""
    edges = p.convert("L").filter(ImageFilter.FIND_EDGES).point(lambda v: 255 if v > 26 else 0)
    ink = Image.eval(edges, lambda v: 255 - v)
    line = Image.new("L", (W, H), 255)
    line.paste(ink, pos, mask)
    return line


def make_effects() -> Image.Image:
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    random.seed(3110)
    for _ in range(120):
        ang = random.uniform(0, math.tau)
        rad = random.uniform(180, 660)
        x = W / 2 + math.cos(ang) * rad * 0.80
        y = H / 2 + math.sin(ang) * rad * 1.08
        if not (0 < x < W and 0 < y < H):
            continue
        s = random.randint(4, 18)
        col = random.choice([(255, 214, 244, 205), (180, 228, 255, 195), (255, 234, 180, 185)])
        d.ellipse([x - s, y - s * 0.42, x + s, y + s * 0.42], fill=col)
    return img


def make_text() -> Image.Image:
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = 46
    d.rounded_rectangle([m, m, W - m, H - m], radius=30, outline=(232, 224, 255, 195), width=3)
    d.rounded_rectangle([m + 14, m + 14, W - m - 14, H - m - 14], radius=22, outline=(232, 224, 255, 70), width=2)
    f_small, f_mid, f_big = font(26), font(38), font(80)
    d.text((W / 2, 118), "AFTERGLOW  /  流光残影", font=f_small, fill=(230, 222, 255, 225), anchor="mm")
    d.line([(W / 2 - 160, 154), (W / 2 + 160, 154)], fill=(210, 195, 255, 130), width=2)
    d.text((W / 2, H - 244), "闪光重影", font=f_big, fill=(250, 247, 255, 248), anchor="mm",
           stroke_width=1, stroke_fill=(70, 52, 130, 170))
    d.text((W / 2, H - 170), "HOLOGRAPHIC GHOST EDITION", font=f_mid, fill=(208, 198, 255, 230), anchor="mm")
    d.line([(W / 2 - 210, H - 130), (W / 2 + 210, H - 130)], fill=(210, 195, 255, 120), width=2)
    d.text((W / 2, H - 92), "PHOTO TEST 001 / 001   ·   GYRO FOIL", font=f_small, fill=(214, 208, 255, 215), anchor="mm")
    for x, y, sx, sy in [(m + 26, m + 26, 1, 1), (W - m - 26, m + 26, -1, 1),
                         (m + 26, H - m - 26, 1, -1), (W - m - 26, H - m - 26, -1, -1)]:
        d.line([(x, y), (x + sx * 44, y)], fill=(255, 232, 190, 220), width=4)
        d.line([(x, y), (x, y + sy * 44)], fill=(255, 232, 190, 220), width=4)
    return img


# 针对「闪光重影」测试强化：拉开层深度差、加大空间重影与时间残影。
PHOTO_PARAMS = {
    "subjectScale": 1.0,
    "subjectDepth": 0.50,
    "backgroundDepth": -0.30,
    "effectsDepth": 0.66,
    "lineDepth": -0.06,
    "ghost": 0.88,
    "glintGain": 0.72,
    "glintSigma": 0.20,
    "glintRange": 0.46,
    "foil": 0.60,
    "trail": 1.50,
    "tiltGain": 0.30,
    "finish": 0,
}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--photo", default=str(DEFAULT_PHOTO))
    args = ap.parse_args()
    src = Path(args.photo)
    if not src.exists():
        raise SystemExit(f"找不到照片: {src}")
    ASSETS.mkdir(parents=True, exist_ok=True)

    photo = ImageOps.exif_transpose(Image.open(src)).convert("RGB")
    # 该图是横屏插画被顺时针旋转后保存的，逆时针 90 度回正。
    if photo.height > photo.width:
        photo = photo.rotate(90, expand=True)
    print(f"照片: {src.name}  转正后 {photo.size}")

    subject, p, pos, mask = make_subject(photo)
    layers = {
        "background": make_background(photo),
        "subject": subject,
        "lineart": make_lineart(p, pos, mask),
        "effects": make_effects(),
        "text": make_text(),
    }
    for name, img in layers.items():
        target = ASSETS / f"{name}.png"
        img.save(target, "PNG")
        print(f"  {target.relative_to(ROOT)}  {img.size} {img.mode}")

    html = OUT / "photo-wallpaper.html"
    subprocess.run([
        sys.executable, str(ROOT / "tools" / "build_standalone.py"),
        "--assets", str(ASSETS), "--out", str(html),
        "--params", json.dumps(PHOTO_PARAMS, ensure_ascii=False),
    ], check=True)
    text = html.read_text(encoding="utf-8")
    text = text.replace("星穹棱镜 · 陀螺仪全息动态壁纸", "流光残影 · 照片闪光重影壁纸")
    text = text.replace("<h1>星穹棱镜</h1>", "<h1>流光残影</h1>")
    html.write_text(text, encoding="utf-8")

    if RUIC_VALIDATE.exists():
        print("RuiC 素材体检:")
        subprocess.run([sys.executable, str(RUIC_VALIDATE), str(OUT)], check=True)
    print(f"\n成品: {html}")
    print("  打开方式: 直接双击；或 ?clean=1&view=0.4,0.15&trail=1 查看残影")


if __name__ == "__main__":
    main()
