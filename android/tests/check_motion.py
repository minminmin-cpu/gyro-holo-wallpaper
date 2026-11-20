"""比较两张桌面截图，判断动态壁纸是否在动。

用法：
  python android/tests/check_motion.py a.png b.png
输出：变化像素占比、差异外接框。占比 > 1% 基本可判定画面在动。
"""
from __future__ import annotations

import sys

from PIL import Image, ImageChops


def main() -> int:
    if len(sys.argv) < 3:
        print("用法: python check_motion.py <a.png> <b.png>")
        return 2

    a = Image.open(sys.argv[1]).convert("RGB")
    b = Image.open(sys.argv[2]).convert("RGB")
    if a.size != b.size:
        print(f"  尺寸不一致: {a.size} vs {b.size}")
        return 1

    diff = ImageChops.difference(a, b)
    bbox = diff.getbbox()
    pixels = list(diff.getdata())
    total = a.size[0] * a.size[1]
    changed = sum(1 for r, g, bl in pixels if r + g + bl > 18)
    ratio = changed * 100.0 / total

    print(f"  尺寸 {a.size[0]}x{a.size[1]}  差异区域 {bbox}")
    print(f"  变化像素 {changed} / {total} = {ratio:.2f}%")
    print("  判定: " + ("画面在动 ✓" if ratio > 1.0 else "几乎静止 ✗"))
    return 0 if ratio > 1.0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
