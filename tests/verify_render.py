"""阶段一渲染验收：证明「闪光跟随视角」而不是自动播放的循环动画。

用法:
  python tests/verify_render.py --capture   # 自动用本机 Chromium 截两帧并验收
  python tests/verify_render.py             # 只验收仓库已有的 shot-clean-*.png

两帧分别以 ?view=-0.55,0 与 ?view=0.55,0 锁定视角，其余参数完全相同。
若镭射高光确实由 uView 驱动，右倾帧应当是「左半变暗、右半变亮」。
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / "tests"
LEFT = TESTS / "shot-clean-left.png"
RIGHT = TESTS / "shot-clean-right.png"
URL = "file:///" + str(ROOT / "standalone.html").replace("\\", "/").replace(" ", "%20")

BROWSER_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/chromium",
    "/usr/bin/google-chrome",
]


def find_browser() -> str:
    for p in BROWSER_CANDIDATES:
        if Path(p).exists():
            return p
    for name in ["msedge", "chrome", "chromium", "google-chrome"]:
        found = shutil.which(name)
        if found:
            return found
    raise RuntimeError("未找到 Chromium 内核浏览器，可用 --browser 指定或手动截图")


def capture(exe: str) -> None:
    profile = Path(tempfile.gettempdir()) / "holo-wallpaper-verify"
    shutil.rmtree(profile, ignore_errors=True)
    profile.mkdir(parents=True, exist_ok=True)
    base = [
        exe, "--headless=new", "--disable-gpu-sandbox",
        "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
        "--no-first-run", f"--user-data-dir={profile}", "--hide-scrollbars",
        "--window-size=390,844", "--virtual-time-budget=5000",
    ]
    for out, view in [(LEFT, "-0.55,0"), (RIGHT, "0.55,0")]:
        cmd = base + [f"--screenshot={out}", f"{URL}?autostart=1&clean=1&view={view}"]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"  截图 {out.name}  {out.stat().st_size // 1024} KB")


def luminance(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32).mean(axis=2)


def centroid(x: np.ndarray) -> float:
    weight = np.clip(x - 170.0, 0.0, None) ** 2
    ys, xs = np.mgrid[0:x.shape[0], 0:x.shape[1]]
    return float((xs * weight).sum() / max(weight.sum(), 1.0))


def verify() -> int:
    a, b = luminance(LEFT), luminance(RIGHT)
    assert a.shape == b.shape, "两帧尺寸不一致"
    A, B = a[160:660, 20:370], b[160:660, 20:370]
    d = B - A
    w = d.shape[1]
    mean_abs = float(np.abs(d).mean())
    left_half = float(d[:, :w // 2].mean())
    right_half = float(d[:, w // 2:].mean())
    directed = right_half - left_half
    shift = centroid(B) - centroid(A)
    overall = float(np.concatenate([A.ravel(), B.ravel()]).mean())

    checks = [
        ("两帧存在可观测差异 (mean|Δ| > 10)", mean_abs > 10, f"{mean_abs:.2f}"),
        ("左半区变暗 (Δ < -5)", left_half < -5, f"{left_half:.2f}"),
        ("右半区变亮 (Δ > 5)", right_half > 5, f"{right_half:.2f}"),
        ("高光方向性位移 (右-左 > 15)", directed > 15, f"{directed:.2f}"),
        ("高光重心右移 (> 8px)", shift > 8, f"{shift:.1f}px"),
        ("画面非黑屏/白屏 (20 < 亮度 < 235)", 20 < overall < 235, f"{overall:.1f}"),
    ]
    failed = 0
    for name, ok, value in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {value}")
        if not ok:
            failed += 1
    print(f"\n渲染验收: {len(checks) - failed}/{len(checks)} 通过")
    return 1 if failed else 0


if __name__ == "__main__":
    if "--capture" in sys.argv:
        print("截图中…")
        browser = sys.argv[sys.argv.index("--browser") + 1] if "--browser" in sys.argv else find_browser()
        capture(browser)
    for f in (LEFT, RIGHT):
        if not f.exists():
            print(f"缺少 {f}，请先运行: python tests/verify_render.py --capture")
            sys.exit(1)
    print("验收中…")
    sys.exit(verify())
