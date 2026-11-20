"""把 app-ui/index.html 同步到 Android assets（WebView 入口）。

同步时会从 src/holo-shader.js 提取 GLSL 注入到占位 <script> 里，
保证 WebView 预览与 WallpaperService 用的是同一份着色器（单一源）。

用法: python tools/build_app_ui.py
"""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app-ui" / "index.html"
SHADER = ROOT / "src" / "holo-shader.js"
TARGET = ROOT / "android" / "app" / "src" / "main" / "assets" / "app-ui" / "index.html"


def render_html() -> bytes:
    """返回应写入 assets 的最终字节；tools/verify_android.py 也调用它做一致性校验。"""
    html = SOURCE.read_text(encoding="utf-8")
    assert "HoloAndroid" in html, "前端未包含原生桥检测（HoloAndroid）"
    assert "__onNative" in html, "前端未定义原生回调对象"

    shader_src = SHADER.read_text(encoding="utf-8")
    frag = re.search(r"export const FRAGMENT_SHADER = `([\s\S]*?)`;", shader_src)
    vert = re.search(r"export const VERTEX_SHADER = `([\s\S]*?)`;", shader_src)
    assert frag and vert, "未能从 src/holo-shader.js 提取 GLSL"

    html = html.replace("/*__HOLO_FRAG__*/", frag.group(1).lstrip("\n"))
    html = html.replace("/*__HOLO_VERT__*/", vert.group(1).lstrip("\n"))
    assert "/*__HOLO_FRAG__*/" not in html and "/*__HOLO_VERT__*/" not in html, "GLSL 占位符未替换"
    assert "precision highp float" in html, "注入后的 WebView 入口缺少片元着色器"

    return html.encode("utf-8")


def main() -> None:
    data = render_html()
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()[:12]
    print(f"  已同步 WebView 入口 -> {TARGET}  ({len(data)} 字节, sha256:{digest})")


if __name__ == "__main__":
    main()
