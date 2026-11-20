import { DEFAULT_CONFIG, ASSET_URLS, FINISH_LABELS } from "./config.js";
import { DampedView, viewFromTilt, clamp } from "./math.js";
import { HoloRenderer } from "./renderer.js";
import { GyroSource, SOURCE } from "./gyro.js";

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const config = JSON.parse(JSON.stringify(DEFAULT_CONFIG));
const injectedAssets = window.__WALLPAPER_ASSETS__ || null;
const assetUrls = injectedAssets || ASSET_URLS;

const state = {
  finish: Number(config.parameters.finish ?? config.finish),
  target: [0, 0],
  view: [0, 0, 1],
  elapsed: 0,
  lastTime: 0,
  lastRender: 0,
  lastSensor: 0,
  prevX: 0,
  prevY: 0,
  lastView: [0, 0, 1],
  motion: { vx: 0, vy: 0, trail: 0 },
  forcedTrail: params.get("trail") === "1",
  fps: 0,
  frameCount: 0,
  fpsWindow: 0,
  started: false,
  source: "fallback",
};

let renderer = null;
let cssFallback = null;
let gyro = null;
const damped = new DampedView(config.gyro.tau);

function status(text, source) {
  if (source) state.source = source;
  const el = $("source");
  if (el) el.textContent = text;
  const badge = $("source-badge");
  if (badge) badge.textContent = state.source;
}

function error(text) {
  const el = $("hint");
  if (el) { el.textContent = text; el.hidden = false; }
  console.warn("[holo-wallpaper]", text);
}

/** WebGL 不可用时的 CSS-3D 兜底：分层位移 + 径向渐变高光跟随。 */
function createCssFallback(urls) {
  const host = $("css-layers");
  host.hidden = false;
  $("gl").hidden = true;
  const make = (cls, url) => {
    const d = document.createElement("div");
    d.className = "layer " + cls;
    d.style.backgroundImage = `url("${url}")`;
    host.appendChild(d);
    return d;
  };
  const bg = make("bg", urls.background);
  const subject = make("subject", urls.subject);
  const text = make("text", urls.text);
  const glint = document.createElement("div");
  glint.className = "layer glint";
  host.appendChild(glint);
  return {
    render(view) {
      const dx = view[0] * 26;
      const dy = view[1] * 26;
      bg.style.transform = `translate3d(${-dx * 0.35}px, ${-dy * 0.35}px, 0) scale(1.10)`;
      subject.style.transform = `translate3d(${dx}px, ${dy}px, 0) scale(1.04)`;
      text.style.transform = "translate3d(0,0,0)";
      glint.style.backgroundPosition = `${50 + view[0] * 70}% ${50 + view[1] * 70}%`;
    },
  };
}

function bindUI() {
  const gate = $("gate");
  // 纯净壁纸模式：隐藏全部 UI，用于截图验收、投屏或作为「网页壁纸」的实际画面。
  if (params.get("clean") === "1") {
    for (const id of ["panel", "hud", "gate"]) $(id).hidden = true;
  }
  const start = async () => {
    if (state.started) return;
    state.started = true;
    gate.classList.add("dismissed");
    try {
      await gyro.start();
      $("calibrate").disabled = false;
      if (state.source === SOURCE.FALLBACK) {
        error("未检测到姿态传感器：可在画面上拖动指针模拟倾斜，或保持自动巡游。");
      }
    } catch (e) {
      error("传感器启动失败：" + e.message + " 已切换为指针/自动巡游。");
      gyro.stop();
    }
  };
  $("permission").addEventListener("click", start);
  $("calibrate").addEventListener("click", () => {
    gyro.calibrate();
    damped.reset();
    status("已重新校准中性姿态");
  });
  $("panel-toggle").addEventListener("click", () => {
    $("panel").classList.toggle("collapsed");
  });

  document.querySelectorAll("[data-finish]").forEach((btn) => {
    btn.addEventListener("click", () => {
      state.finish = Number(btn.dataset.finish);
      document.querySelectorAll("[data-finish]").forEach((b) => b.classList.toggle("active", b === btn));
      $("finish-name").textContent = FINISH_LABELS[state.finish];
    });
  });

  const bindRange = (id, key) => {
    const el = $(id);
    el.value = String(config.parameters[key]);
    const out = $(id + "-out");
    if (out) out.textContent = Number(el.value).toFixed(2);
    el.addEventListener("input", () => {
      if (renderer) renderer.setParameter(key, el.value);
      else config.parameters[key] = Number(el.value);
      if (out) out.textContent = Number(el.value).toFixed(2);
    });
  };
  bindRange("foil", "foil");
  bindRange("glint", "glintGain");
  bindRange("depth", "subjectDepth");
  bindRange("trail", "trail");
  bindRange("card", "cardScale");

  document.querySelectorAll("[data-mode]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const v = Number(btn.dataset.mode);
      if (renderer) renderer.setParameter("cardMode", v);
      else config.parameters.cardMode = v;
      document.querySelectorAll("[data-mode]").forEach((b) => b.classList.toggle("active", b === btn));
    });
  });
  if (params.get("flat") === "1") {
    config.parameters.cardMode = 0;
    document.querySelector('[data-mode="0"]')?.classList.add("active");
    document.querySelector('[data-mode="1"]')?.classList.remove("active");
  }

  const stage = $("stage");
  const pointer = (e) => {
    const r = stage.getBoundingClientRect();
    const x = clamp((e.clientX - r.left) / r.width * 2 - 1, -1, 1);
    const y = clamp((e.clientY - r.top) / r.height * 2 - 1, -1, 1);
    gyro.setPointer(x, y);
    state.lastSensor = performance.now();
  };
  stage.addEventListener("pointermove", pointer);
  stage.addEventListener("touchmove", (e) => { if (e.touches[0]) pointer(e.touches[0]); }, { passive: true });

  const fixed = params.get("view");
  if (fixed) {
    const [x, y] = fixed.split(",").map(Number);
    if (Number.isFinite(x) && Number.isFinite(y)) {
      state.fixedView = viewFromTilt(x, y);
    }
  }
  if (params.get("debug") === "1") $("hud").hidden = false;
  if (params.has("finish")) {
    state.finish = clamp(Number(params.get("finish")) || 0, 0, 3);
    document.querySelectorAll("[data-finish]").forEach((b) => b.classList.toggle("active", Number(b.dataset.finish) === state.finish));
    $("finish-name").textContent = FINISH_LABELS[state.finish];
  }
}

function updateHud(dt) {
  state.frameCount++;
  state.fpsWindow += dt;
  if (state.fpsWindow >= 1) {
    state.fps = Math.round(state.frameCount / state.fpsWindow);
    state.frameCount = 0;
    state.fpsWindow = 0;
    if ($("fps")) $("fps").textContent = state.fps;
  }
  if (!$("hud").hidden) {
    $("view-x").textContent = state.view[0].toFixed(3);
    $("view-y").textContent = state.view[1].toFixed(3);
  }
}

function frame(now) {
  requestAnimationFrame(frame);
  if (document.hidden) { state.lastTime = now; return; }
  const dt = Math.min((now - state.lastTime) / 1000, 0.06) || 0;
  state.lastTime = now;
  state.elapsed += dt;

  if (!state.fixedView && now - state.lastSensor > 400) {
    state.target = gyro.autoTour(state.elapsed);
  }
  const deadband = config.gyro.deadbandDeg / config.gyro.maxTiltDeg;
  const [tx, ty] = damped.step(state.target[0], state.target[1], dt, deadband);
  state.view = state.fixedView || viewFromTilt(tx, ty);

  // 角速度 -> 时间残影强度。静止时自动归零，避免常态糊（与官方演示一致）。
  if (!state.forcedTrail) {
    const rawVx = (state.view[0] - state.lastView[0]) / Math.max(dt, 1e-3);
    const rawVy = (state.view[1] - state.lastView[1]) / Math.max(dt, 1e-3);
    const k = 1 - Math.exp(-dt / 0.09);
    state.motion.vx += (rawVx - state.motion.vx) * k;
    state.motion.vy += (rawVy - state.motion.vy) * k;
    const speed = Math.hypot(state.motion.vx, state.motion.vy);
    state.motion.trail = clamp(speed * 0.055, 0, 1) * config.parameters.trail;
  }
  state.lastView = state.view.slice();

  const moving = Math.abs(damped.x - state.prevX) + Math.abs(damped.y - state.prevY) > config.power.stillEpsilon;
  state.prevX = damped.x;
  state.prevY = damped.y;
  const interval = 1000 / (moving ? config.power.activeFps : config.power.idleFps);
  if (now - state.lastRender < interval) return;
  state.lastRender = now;

  if (renderer) renderer.render(state.view, state.elapsed, state.finish, state.motion);
  else if (cssFallback) cssFallback.render(state.view);
  updateHud(dt);
}

async function init() {
  const canvas = $("gl");
  try {
    renderer = new HoloRenderer(canvas, config);
    await renderer.loadAssets(assetUrls);
    status("WebGL 就绪，等待启用传感器", "pending");
  } catch (e) {
    // 暴露到 DOM，便于无头浏览器用 --dump-dom 快速发现着色器编译/链接失败。
    document.body.dataset.glError = String(e.message);
    cssFallback = createCssFallback(assetUrls);
    error("WebGL 不可用，已启用 CSS-3D 分层兜底：" + e.message);
    status("CSS-3D 兜底运行中");
  }

  gyro = new GyroSource({
    ...config.gyro,
    onStatus: status,
    onError: error,
    onSample: (s) => {
      state.target = [s.x, s.y];
      state.lastSensor = performance.now();
    },
  });

  bindUI();
  window.addEventListener("resize", () => { if (renderer) renderer.resize(); });
  $("loading").hidden = true;

  window.__holoWallpaper = {
    ready: true,
    version: "stage1-1.0.0",
    config,
    renderer,
    gyro,
    getState: () => ({
      view: state.view.slice(), target: state.target.slice(),
      finish: state.finish, source: state.source, fps: state.fps,
      mode: renderer ? "webgl" : "css-fallback",
    }),
    setView: (x, y) => { state.fixedView = viewFromTilt(x, y); },
    clearFixedView: () => { delete state.fixedView; },
    setParameter: (key, value) => { if (renderer) renderer.setParameter(key, value); config.parameters[key] = Number(value); },
    setFinish: (v) => { state.finish = Number(v); },
    capture: () => renderer ? renderer.capture(state.view, state.elapsed, state.finish, state.motion) : null,
    setMotion: (vx, vy) => {
      state.forcedTrail = true;
      state.motion = { vx, vy, trail: clamp(Math.hypot(vx, vy) * 0.055, 0, 1) * config.parameters.trail };
    },
    start: () => $("permission").click(),
  };

  if (state.forcedTrail) state.motion = { vx: 2.2, vy: 0.7, trail: config.parameters.trail };

  if (params.get("autostart") === "1") $("permission").click();
  requestAnimationFrame(frame);
}

init().catch((e) => {
  $("loading").textContent = "初始化失败：" + e.message;
  console.error(e);
});
