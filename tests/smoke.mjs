/** 阶段一离线验收：纯算法单测 + 着色器契约检查 + 交付物完整性。
 *  运行: node tests/smoke.mjs   （无需浏览器 / 无需真机） */
import { strict as assert } from "node:assert";
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import {
  angleDelta, screenAngleDeg, deviceTiltToScreen, quatMultiply,
  quatConjugate, rotateVectorByQuat, relativeQuaternion, quatToTilt,
  degToTilt, DampedView, viewFromTilt, parallaxUv,
} from "../src/math.js";
import { FRAGMENT_SHADER, VERTEX_SHADER, UNIFORM_NAMES } from "../src/holo-shader.js";
import { DEFAULT_CONFIG } from "../src/config.js";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
let pass = 0;
const cases = [];
function test(name, fn) {
  try { fn(); cases.push(["PASS", name]); pass++; }
  catch (e) { cases.push(["FAIL", name + " -> " + e.message]); }
}
const near = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) < eps, `${a} !~ ${b}`);

test("angleDelta 跨 0/360 跳变", () => {
  near(angleDelta(1, 359), 2);
  near(angleDelta(359, 1), -2);
  near(angleDelta(180, 0), 180);
});

test("screenAngleDeg 读取标准/旧式接口", () => {
  assert.equal(screenAngleDeg({ orientation: { angle: 90 } }), 90);
  assert.equal(screenAngleDeg({ msOrientation: "landscape-primary" }), 90);
  assert.equal(screenAngleDeg({}, undefined), 0);
});

test("deviceTiltToScreen 竖屏恒等、横屏 90 度旋转", () => {
  const a = deviceTiltToScreen(10, 0, 0);
  near(a.x, 10); near(a.y, 0);
  const b = deviceTiltToScreen(0, 10, 90);
  near(b.x, -10, 1e-5); near(b.y, 0, 1e-5);
});

const ID = [0, 0, 0, 1];
test("四元数单位元与共轭", () => {
  const q = [0.1, -0.2, 0.05, 0.9737];
  const p = quatMultiply(q, ID);
  near(p[0], q[0], 1e-9); near(p[3], q[3], 1e-9);
  const c = quatConjugate(q);
  near(c[0], -q[0]); near(c[3], q[3]);
  near(quatMultiply(q, c)[3], 1, 0.02);
});

test("rotateVectorByQuat 单位四元数不改变向量", () => {
  const v = rotateVectorByQuat([0, 0, 1], ID);
  near(v[0], 0); near(v[1], 0); near(v[2], 1);
});

test("relativeQuaternion 校准后自身为零旋转", () => {
  const q = [0.12, 0.05, -0.03, 0.99];
  const rel = relativeQuaternion(q, q);
  near(rel[0], 0, 1e-9); near(rel[1], 0, 1e-9); near(rel[2], 0, 1e-9);
  const [x, y] = quatToTilt(rel);
  near(x, 0); near(y, 0);
});

test("degToTilt 饱和钳制", () => {
  assert.deepEqual(degToTilt(999, -999, 38, 0.78), [0.78, -0.78]);
});

test("viewFromTilt 是单位向量且中性为 +Z", () => {
  const v0 = viewFromTilt(0, 0);
  assert.deepEqual(v0, [0, 0, 1]);
  const v = viewFromTilt(0.5, -0.3);
  near(Math.hypot(...v), 1, 1e-9);
  assert.ok(v[2] > 0.4, "uView.z 必须保持可除的下界");
});

test("parallaxUv 与 RuiC 公式一致且不会除零爆炸", () => {
  const uv = [0.5, 0.5];
  const view = [0.4, -0.2, Math.sqrt(1 - 0.16 - 0.04)];
  const [x, y] = parallaxUv(uv, view, 0.4);
  near(x, 0.5 + (view[0] / Math.max(Math.abs(view[2]), 0.4)) * 0.4 * 0.10);
  near(y, 0.5 + (view[1] / Math.max(Math.abs(view[2]), 0.4)) * 0.4 * 0.10);
  const tiny = parallaxUv(uv, [1, 0, 0.00001], 0.4);
  assert.ok(Number.isFinite(tiny[0]) && Math.abs(tiny[0]) < 1.1);
});

test("DampedView 收敛、帧率无关、死区抑抖", () => {
  const d = new DampedView(0.1);
  for (let i = 0; i < 100; i++) d.step(0.6, -0.4, 1 / 60);
  assert.ok(Math.abs(d.x - 0.6) < 1e-3, "应收敛到目标");
  const slow = new DampedView(0.1), fast = new DampedView(0.1);
  slow.step(1, 0, 0.2);
  for (let i = 0; i < 12; i++) fast.step(1, 0, 0.2 / 12);
  near(slow.x, fast.x, 1e-6);
  const dead = new DampedView(0.1);
  const [x] = dead.step(0.0005, 0, 1 / 60, 0.01);
  near(x, 0);
});

test("着色器保留 RuiC 核心公式与分层契约", () => {
  for (const token of [
    "uView.xy / max(abs(uView.z), .4) * depth * .10",
    "spectrum(phase)", "float sweep(vec2 uv)", "float strength()",
    "tSubject", "tBackground", "tText", "tLine", "tEffects",
    "glintCenter", "uGlintGain", "uGhost",
    "cardUv", "uCardMode", "uLineDepth", "glintBand", "uTrailVec",
  ]) {
    assert.ok(FRAGMENT_SHADER.includes(token), "缺少片段: " + token);
  }
  assert.ok(VERTEX_SHADER.includes("aPosition"));
  assert.equal(new Set(UNIFORM_NAMES).size, UNIFORM_NAMES.length, "uniform 名不能重复");
  assert.ok(!FRAGMENT_SHADER.includes("colorspace_fragment"), "不应残留 three.js 的 include");
});

test("默认参数保持 RuiC 的出厂深度值", () => {
  assert.equal(DEFAULT_CONFIG.parameters.subjectDepth, 0.4);
  assert.equal(DEFAULT_CONFIG.parameters.backgroundDepth, -0.25);
  assert.equal(DEFAULT_CONFIG.parameters.effectsDepth, 0.5);
  assert.ok(DEFAULT_CONFIG.power.idleFps <= 15, "静止时必须降帧省电");
});

test("官方演示对齐：卡片透视 + 分层错位重影 + 时间残影", () => {
  const p = DEFAULT_CONFIG.parameters;
  assert.equal(p.cardMode, 1, "默认应是官方演示的卡片透视构图");
  assert.equal(p.lineDepth, 0.22, "线稿需与主体使用不同深度");
  assert.notEqual(p.lineDepth, p.subjectDepth, "层深度相同会重影消失、卡片变平");
  assert.ok(p.trail > 0 && p.relief > 0);
  assert.ok(FRAGMENT_SHADER.includes("vec2 lu =") && FRAGMENT_SHADER.includes("texture2D(tLine,clamp(lu"));
  assert.ok(FRAGMENT_SHADER.includes("for (int i = 1; i <= 3; i++)"), "时间残影需多 tap 采样");
  assert.ok(UNIFORM_NAMES.includes("uTrail") && UNIFORM_NAMES.includes("uTrailVec"));
});

test("交付物齐全", () => {
  for (const f of [
    "index.html", "README.md", "docs/阶段一技术方案.md", "standalone.html",
    "src/config.js", "src/math.js", "src/holo-shader.js", "src/gyro.js",
    "src/renderer.js", "src/main.js", "tools/generate_assets.py",
    "tools/build_standalone.py", "tools/serve.py", "tools/make_photo_wallpaper.py",
    "tests/verify_render.py",
    "assets/subject.png", "assets/background.png", "assets/text.png",
    "assets/lineart.png", "assets/effects.png",
    "vendor/sensor-polyfills/motion-sensors.js", "vendor/sensor-polyfills/LICENSE.md",
    "vendor/sensor-polyfills/SOURCE.md",
    "app-ui/index.html", "app-ui/README.md", "app-ui/tests/verify_ui.mjs",
    "tools/sync_shader.py", "tools/verify_android.py",
    "android/README.md", "android/settings.gradle.kts", "android/app/build.gradle.kts",
    "android/app/src/main/AndroidManifest.xml",
    "android/app/src/main/assets/shader/holo.frag",
    "android/app/src/main/assets/demo/card-config.json",
    "android/app/src/main/kotlin/com/example/holowallpaper/MainActivity.kt",
    "android/app/src/main/kotlin/com/example/holowallpaper/wallpaper/EglRenderer.kt",
    "android/app/src/main/kotlin/com/example/holowallpaper/wallpaper/HoloWallpaperService.kt",
  ]) {
    assert.ok(existsSync(join(ROOT, f)), "缺少文件: " + f);
  }
  const html = readFileSync(join(ROOT, "standalone.html"), "utf8");
  assert.ok(html.includes("__WALLPAPER_ASSETS__") && html.includes("data:image/png;base64,"));
  assert.ok(!html.includes('type="module"'), "单文件版不应保留 ESM 入口");
});

for (const [state, name] of cases) console.log(`  [${state}] ${name}`);
const failed = cases.length - pass;
console.log(`\n阶段一离线验收: ${pass}/${cases.length} 通过${failed ? `，${failed} 项失败` : ""}`);
if (failed) process.exit(1);
