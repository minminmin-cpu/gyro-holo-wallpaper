"""单一源同步：从 web/src/holo-shader.js 提取 GLSL，生成 Android assets/shader/。

用法: python tools/sync_shader.py
Web 与 Android 共用同一份 shader，避免两份实现漂移。
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src" / "holo-shader.js"
OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "shader"

REQUIRED_TOKENS = [
    "vec2 parallax(", "vec3 spectrum(", "float sweep(", "vec3 film(",
    "glintCenter", "cardUv(", "uTrailVec", "gl_FragColor",
]


def extract(name: str, source: str) -> str:
    match = re.search(r"export const " + name + r" = `([\s\S]*?)`;", source)
    if not match:
        raise SystemExit(f"未能在 {SOURCE.name} 中找到 {name}")
    return match.group(1).lstrip("\n")


def main() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    vert = extract("VERTEX_SHADER", source)
    frag = extract("FRAGMENT_SHADER", source)

    # half 是 GLSL ES 保留字，历史上曾因它导致编译失败并静默退回降级渲染。
    assert "vec2 half =" not in frag, "着色器含 GLSL ES 保留字 half"
    for token in REQUIRED_TOKENS:
        assert token in frag, f"着色器缺少关键片段: {token}"
    assert "attribute vec2 aPosition" in vert, "顶点着色器缺少 aPosition"

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "holo.vert").write_text(vert, encoding="utf-8")
    (OUT / "holo.frag").write_text(frag, encoding="utf-8")
    print(f"  同步完成: holo.vert {len(vert)} 字符, holo.frag {len(frag)} 字符 -> {OUT}")


if __name__ == "__main__":
    main()
