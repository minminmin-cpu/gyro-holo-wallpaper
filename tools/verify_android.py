"""Android 工程静态校验（本机无 JDK17/Android SDK，无法真实编译时用它兜底）。

用法: python tools/verify_android.py
检查：XML 可解析、Gradle/Kotlin 括号平衡与必要符号、GLSL 单一源同步可复现、
shader uniform 与 Kotlin UNIFORMS 完全一致、资源引用齐全、CI 路径正确。
"""
from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
MAIN = ANDROID / "app" / "src" / "main"
results: list[tuple[str, str, str]] = []


def check(name: str, fn):
    try:
        detail = fn()
        results.append(("PASS", name, str(detail or "")))
    except Exception as e:  # noqa: BLE001
        results.append(("FAIL", name, str(e)))


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_xml():
    files = sorted(list((ANDROID / "app" / "src" / "main").rglob("*.xml")))
    assert len(files) >= 5, f"XML 文件过少: {len(files)}"
    for p in files:
        ET.parse(p)
    return f"{len(files)} 个 XML 可解析"


def test_gradle():
    files = [
        ANDROID / "settings.gradle.kts", ANDROID / "build.gradle.kts",
        ANDROID / "app" / "build.gradle.kts",
    ]
    need = {
        files[0]: ['include(":app")', "google()", "mavenCentral()"],
        files[1]: ['com.android.application', 'org.jetbrains.kotlin.android'],
        files[2]: ["namespace", "minSdk = 24", "compileSdk = 35", "KEYSTORE_FILE", "jvmTarget"],
    }
    for p in files:
        s = read(p)
        assert s.count("{") == s.count("}"), f"{p.name} 大括号不平衡"
        for token in need[p]:
            assert token in s, f"{p.name} 缺少 {token}"
    assert (ANDROID / "gradle" / "wrapper" / "gradle-wrapper.properties").exists()
    return "3 个 KTS + wrapper 配置齐全"


def test_kotlin():
    files = sorted((MAIN / "kotlin").rglob("*.kt"))
    assert len(files) >= 12, f"Kotlin 文件过少: {len(files)}"
    for p in files:
        s = read(p)
        assert s.count("{") == s.count("}"), f"{p.name} 大括号不平衡"
        assert "TODO(" not in s, f"{p.name} 仍有未实现 TODO"
        assert s.startswith("package com.example.holowallpaper"), f"{p.name} 包名错误"
    joined = "\n".join(read(p) for p in files)
    for symbol in ["WallpaperService", "Choreographer.FrameCallback", "EGL14.eglCreateWindowSurface",
                   "eglSwapBuffers", "TYPE_GAME_ROTATION_VECTOR", "ACTION_CHANGE_LIVE_WALLPAPER",
                   "card-config.json", "glDrawArrays", "AndroidKeyStore",
                   "JavascriptInterface", "LayerBuilder", "HttpURLConnection",
                   "WebView", "AndroidKeystore"]:
        assert symbol in joined, f"缺少关键实现: {symbol}"
    return f"{len(files)} 个 Kotlin 文件，关键实现齐全"


def test_shader_sync():
    frag = MAIN / "assets" / "shader" / "holo.frag"
    vert = MAIN / "assets" / "shader" / "holo.vert"
    before = (hashlib.sha256(frag.read_bytes()).hexdigest(),
              hashlib.sha256(vert.read_bytes()).hexdigest())
    subprocess.run([sys.executable, str(ROOT / "tools" / "sync_shader.py")],
                   check=True, capture_output=True, text=True)
    after = (hashlib.sha256(frag.read_bytes()).hexdigest(),
             hashlib.sha256(vert.read_bytes()).hexdigest())
    assert before == after, "重复同步产生了不同内容（单一源失效）"
    src = read(ROOT / "src" / "holo-shader.js")
    frag_text = read(frag)
    for token in ["vec2 parallax(", "spectrum(phase)", "glintCenter", "cardUv(", "uTrailVec"]:
        assert token in frag_text and token in src, f"GLSL 单一源缺少 {token}"
    return f"vert {len(read(vert))} / frag {len(frag_text)} 字符，与 Web 源一致"


def test_uniforms():
    frag = read(MAIN / "assets" / "shader" / "holo.frag")
    glsl_names = set()
    for decl in re.findall(r"uniform\s+\w+\s+([^;]+);", frag):
        glsl_names.update(n.strip() for n in decl.split(",") if n.strip())
    kt = read(MAIN / "kotlin" / "com" / "example" / "holowallpaper" / "wallpaper" / "EglRenderer.kt")
    # SAMPLERS 定义在 UNIFORMS 之前，且以 `+ SAMPLERS` 拼接，需一起纳入比对范围。
    block = kt[kt.index("private val SAMPLERS"):kt.index("fun initialize(")]
    kt_names = set(re.findall(r'"(u[A-Z]\w*|t[A-Z]\w*)"', block))
    missing = sorted(glsl_names - kt_names)
    extra = sorted(kt_names - glsl_names)
    assert not missing, f"Kotlin 未设置的 uniform: {missing}"
    assert not extra, f"Kotlin 多余的 uniform: {extra}"
    assert len(glsl_names) >= 28, f"uniform 数量异常: {len(glsl_names)}"
    return f"{len(glsl_names)} 个 uniform 完全对齐"


def test_resources():
    manifest = read(MAIN / "AndroidManifest.xml")
    for token in ["BIND_WALLPAPER", "android.service.wallpaper.WallpaperService",
                  "@xml/wallpaper", "HoloWallpaperService", "MainActivity", 'android:allowBackup="false"']:
        assert token in manifest, f"Manifest 缺少 {token}"
    values = read(MAIN / "res" / "values" / "strings.xml")
    used = set(re.findall(r"R\.string\.(\w+)",
                          "\n".join(read(p) for p in (MAIN / "kotlin").rglob("*.kt"))))
    for name in used:
        assert f'name="{name}"' in values, f"strings.xml 缺少 {name}"
    assert (MAIN / "res" / "xml" / "wallpaper.xml").exists()
    assert (MAIN / "res" / "drawable" / "wallpaper_thumb.xml").exists()
    return f"Manifest / {len(used)} 个字符串引用 / wallpaper.xml 齐全"


def test_assets():
    demo = MAIN / "assets" / "demo"
    for name in ["subject.png", "background.png", "text.png", "lineart.png",
                 "effects.png", "card-config.json"]:
        assert (demo / name).stat().st_size > 100, f"演示素材缺失或过小: {name}"
    for name in ["holo.vert", "holo.frag"]:
        assert (MAIN / "assets" / "shader" / name).stat().st_size > 100
    import json
    cfg = json.loads(read(demo / "card-config.json"))
    for key in ["subjectDepth", "backgroundDepth", "glintGain", "ghost", "trail"]:
        assert key in cfg["parameters"], f"card-config.json 缺少 {key}"
    return "5 张分层素材 + 2 个 shader + 配置齐全"


def test_m2_files():
    base = MAIN / "kotlin" / "com" / "example" / "holowallpaper"
    expected = [
        "security/KeyVault.kt", "model/ModelProvider.kt", "model/ProviderRegistry.kt",
        "model/ImageGenerationClient.kt", "model/CardDesign.kt", "generator/LayerBuilder.kt",
        "generator/ProfileWriter.kt", "bridge/NativeBridge.kt",
    ]
    for rel in expected:
        assert (base / rel).exists(), f"M2 缺少 {rel}"
    asset = MAIN / "assets" / "app-ui" / "index.html"
    assert asset.exists(), "缺少 WebView 入口 assets/app-ui/index.html"
    # assets 里是「前端源 + 注入 GLSL」的产物，用同一渲染函数比对而不是直接比源文件哈希。
    sys.path.insert(0, str(ROOT / "tools"))
    from build_app_ui import render_html  # noqa: E402
    assert asset.read_bytes() == render_html(), \
        "WebView 入口与「前端源 + GLSL 注入」不一致（请运行 tools/build_app_ui.py）"
    return f"{len(expected)} 个 M2 模块 + WebView 入口同步"


def test_m2_bridge():
    kt = read(MAIN / "kotlin" / "com" / "example" / "holowallpaper" / "bridge" / "NativeBridge.kt")
    native = set(re.findall(r"@JavascriptInterface\s+fun\s+(\w+)\s*\(", kt))
    html = read(ROOT / "app-ui" / "index.html")
    called = set(re.findall(r'Native\.json\("(\w+)"', html)) | set(re.findall(r'Native\.call\("(\w+)"', html))
    missing = sorted(called - native)
    assert not missing, f"前端调用但原生未实现: {missing}"
    assert len(native) >= 10, f"桥方法过少: {len(native)}"
    # 照片选择回调由 MainActivity 发起，需一并纳入扫描。
    kt_all = kt + read(MAIN / "kotlin" / "com" / "example" / "holowallpaper" / "MainActivity.kt")
    for callback in ["onGenerateResult", "onTestResult", "onPhotoPicked"]:
        assert callback in kt_all and callback in html, f"原生回调未对齐: {callback}"
    return f"{len(native)} 个桥方法 / {len(called)} 个前端调用 / 3 个回调对齐"


def test_m2_security():
    vault = read(MAIN / "kotlin" / "com" / "example" / "holowallpaper" / "security" / "KeyVault.kt")
    for token in ["AndroidKeyStore", "AES/GCM/NoPadding", "KeyGenParameterSpec", "GCMParameterSpec"]:
        assert token in vault, f"KeyVault 缺少 {token}"
    provider = read(MAIN / "kotlin" / "com" / "example" / "holowallpaper" / "model" / "ModelProvider.kt")
    assert "apiKeyEncrypted" in provider and "KeyVault.encrypt" in provider
    registry = read(MAIN / "kotlin" / "com" / "example" / "holowallpaper" / "model" / "ProviderRegistry.kt")
    assert "apiKey" not in registry.replace("apiKeyEncrypted", ""), "Registry 不应出现明文 Key"
    gradle = read(ANDROID / "app" / "build.gradle.kts")
    block = gradle[gradle.index("dependencies"):] if "dependencies" in gradle else ""
    assert "implementation(" not in block, "M2 不应引入外部依赖"
    manifest = read(MAIN / "AndroidManifest.xml")
    assert 'android:allowBackup="false"' in manifest
    return "Keystore AES-GCM、无明文 Key、零外部依赖、禁云备份"


def test_no_bundled_api():
    """发布硬约束：APK 内不得内置任何第三方模型 API / 密钥 / 预置配置。

    发布版首次启动无任何模型配置；模型必须由用户自行添加，
    配置只落在设备私有目录（files/providers.json），不进 APK。"""
    for kt in (MAIN / "kotlin").rglob("*.kt"):
        s = read(kt)
        assert not re.search(r"https?://[A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,}", s), f"{kt.name} 含硬编码 URL，发布包不许内置外部 API"
        assert not re.search(r"sk-[A-Za-z0-9]{8,}", s), f"{kt.name} 含疑似 API Key"
        for m in re.finditer(r'baseUrl\s*=\s*"([^"]+)"', s):
            raise AssertionError(f"{kt.name} 给 baseUrl 设了非空默认值: {m.group(1)}")

    for p in (MAIN / "assets").rglob("*"):
        if p.is_file() and p.name.lower() in ("providers.json", "provider.json"):
            raise AssertionError(f"assets 不应包含预置模型配置: {p.name}")

    provider = read(MAIN / "kotlin" / "com" / "example" / "holowallpaper" / "model" / "ModelProvider.kt")
    assert 'fun builtin(' not in provider, "不应再预置模型配置"
    registry = read(MAIN / "kotlin" / "com" / "example" / "holowallpaper" / "model" / "ProviderRegistry.kt")
    assert "if (!f.exists()) return emptyList()" in registry, "首次启动必须无模型配置"
    return "无硬编码 URL / 密钥 / 预置模型，首发模型列表为空"


def test_design_only():
    base = MAIN / "kotlin" / "com" / "example" / "holowallpaper"
    client = read(base / "model" / "ImageGenerationClient.kt")
    bridge = read(base / "bridge" / "NativeBridge.kt")
    design = read(base / "model" / "CardDesign.kt")
    html = read(ROOT / "app-ui" / "index.html")
    active = client + bridge + html
    for forbidden in ["/images/generations", "/images/edits", "OPENAI_IMAGES", "CUSTOM_WEBHOOK",
                      "TEXT_TO_IMAGE", "IMAGE_TO_IMAGE", 'data-entry="prompt"', "copyDemo("]:
        assert forbidden not in active, f"仍包含旧生图入口或请求: {forbidden}"
    assert '"/chat/completions"' in client and "data:image/jpeg;base64," in client
    assert "CardDesign.parse(text)" in client and "instanceFollowRedirects = false" in client
    assert "LayerBuilder.build(photo, design)" in bridge and "provider.validate()" in bridge
    assert 'keys(j, setOf(' in design and '"effectsDepth", 0.65, 0.85' in design
    assert "needsMigration" in html and "escapeHtml(w.name)" in html
    return "仅 Chat 设计请求、白名单校验、本地照片合成、迁移提示与文本转义"


def test_ci():
    wf = read(ROOT / ".github" / "workflows" / "build-apk.yml")
    for token in ["gradle/actions/setup-gradle@v4", 'gradle-version: "8.9"',
                  "android-actions/setup-android@v3", "working-directory: android",
                  "gradle :app:assembleRelease", "KEYSTORE_BASE64",
                  "softprops/action-gh-release@v2", "APK_SHA256.txt"]:
        assert token in wf, f"workflow 缺少 {token}"
    assert "./gradlew" not in wf, "仓库不含 gradle-wrapper.jar，不能用 ./gradlew"
    gi = read(ROOT / ".gitignore")
    assert "*.jks" in gi and "release.jks" in gi, ".gitignore 未排除 keystore"
    return "CI 使用 gradle CLI、签名与 Release 路径正确"


def main() -> int:
    check("XML 可解析", test_xml)
    check("Gradle 配置", test_gradle)
    check("Kotlin 实现", test_kotlin)
    check("GLSL 单一源同步", test_shader_sync)
    check("shader uniform 对齐", test_uniforms)
    check("Manifest 与资源引用", test_resources)
    check("演示素材与配置", test_assets)
    check("M2 模块与 WebView 入口", test_m2_files)
    check("M2 桥方法对齐", test_m2_bridge)
    check("M2 密钥安全与依赖", test_m2_security)
    check("发布包不含内置 API", test_no_bundled_api)
    check("仅照片与 Chat 设计", test_design_only)
    check("CI 构建与发布", test_ci)
    for status, name, detail in results:
        print(f"  [{status}] {name}" + (f": {detail}" if detail else ""))
    failed = sum(1 for s, _, _ in results if s == "FAIL")
    print(f"\nAndroid 工程静态校验: {len(results) - failed}/{len(results)} 通过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
