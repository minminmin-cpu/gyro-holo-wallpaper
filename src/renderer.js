/** 纯 WebGL1 渲染器：一个全屏 quad + 5 张纹理 + 1 次 draw call。
 *  壁纸常驻，刻意避免 three.js 的场景图/几何体开销。 */
import { VERTEX_SHADER, FRAGMENT_SHADER, UNIFORM_NAMES } from "./holo-shader.js";

const TEXTURE_UNITS = ["tSubject", "tBackground", "tText", "tLine", "tEffects"];
const ASSET_FOR_UNIT = ["subject", "background", "text", "lineart", "effects"];

function compile(gl, type, src) {
  const shader = gl.createShader(type);
  gl.shaderSource(shader, src);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    throw new Error("着色器编译失败: " + gl.getShaderInfoLog(shader));
  }
  return shader;
}

function createProgram(gl) {
  const program = gl.createProgram();
  gl.attachShader(program, compile(gl, gl.VERTEX_SHADER, VERTEX_SHADER));
  gl.attachShader(program, compile(gl, gl.FRAGMENT_SHADER, FRAGMENT_SHADER));
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error("着色器链接失败: " + gl.getProgramInfoLog(program));
  }
  return program;
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    if (!url.startsWith("data:")) img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("图片加载失败: " + url.slice(0, 80)));
    img.src = url;
  });
}

function createTexture(gl, img) {
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
  return tex;
}

export class HoloRenderer {
  constructor(canvas, config) {
    const attrs = { alpha: false, antialias: false, depth: false, stencil: false, powerPreference: "low-power" };
    const gl = canvas.getContext("webgl", attrs) || canvas.getContext("experimental-webgl", attrs);
    if (!gl) throw new Error("此设备不支持 WebGL");
    this.gl = gl;
    this.canvas = canvas;
    this.config = config;
    this.qualityScale = 1;
    this.program = createProgram(gl);
    gl.useProgram(this.program);

    this.uniforms = {};
    for (const name of UNIFORM_NAMES) this.uniforms[name] = gl.getUniformLocation(this.program, name);
    TEXTURE_UNITS.forEach((name, i) => gl.uniform1i(this.uniforms[name], i));

    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
      -1, -1, 1, -1, -1, 1,
      -1, 1, 1, -1, 1, 1,
    ]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(this.program, "aPosition");
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);

    this.textures = {};
    this.hasEffects = false;
    this.hasLine = true;
    this.aspect = [0.5, 1.0];
    this.resize();
  }

  async loadAssets(urls) {
    for (let i = 0; i < ASSET_FOR_UNIT.length; i++) {
      const key = ASSET_FOR_UNIT[i];
      const img = await loadImage(urls[key]);
      this.textures[key] = createTexture(this.gl, img);
      if (key === "effects") this.hasEffects = true;
      if (key === "lineart") this.hasLine = true;
    }
  }

  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, this.config.power.maxDpr) * this.qualityScale;
    const w = Math.max(1, Math.round(this.canvas.clientWidth * dpr));
    const h = Math.max(1, Math.round(this.canvas.clientHeight * dpr));
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    this.gl.viewport(0, 0, w, h);
    const m = Math.max(w, h);
    this.aspect = [w / m, h / m];
  }

  setQuality(scale) {
    this.qualityScale = Math.max(0.5, Math.min(1, scale));
    this.resize();
  }

  setParameter(key, value) { this.config.parameters[key] = Number(value); }

  render(view, timeSec, finish, motion = { vx: 0, vy: 0, trail: 0 }) {
    const gl = this.gl;
    const p = this.config.parameters;
    gl.useProgram(this.program);
    for (let i = 0; i < TEXTURE_UNITS.length; i++) {
      gl.activeTexture(gl.TEXTURE0 + i);
      gl.bindTexture(gl.TEXTURE_2D, this.textures[ASSET_FOR_UNIT[i]] || null);
    }
    const u = this.uniforms;
    gl.uniform1f(u.uTime, timeSec);
    gl.uniform1f(u.uFoil, p.foil);
    gl.uniform1f(u.uScale, p.subjectScale);
    gl.uniform1f(u.uDepth, p.subjectDepth);
    gl.uniform1f(u.uBgDepth, p.backgroundDepth);
    gl.uniform1f(u.uFinish, finish);
    gl.uniform1f(u.uHasLine, this.hasLine ? 1 : 0);
    gl.uniform1f(u.uHasFx, this.hasEffects ? 1 : 0);
    gl.uniform1f(u.uSafeScale, this.config.safeArea.scale);
    gl.uniform1f(u.uFxDepth, p.effectsDepth);
    gl.uniform1f(u.uGlintGain, p.glintGain);
    gl.uniform1f(u.uGlintSigma, p.glintSigma);
    gl.uniform1f(u.uGlintRange, p.glintRange);
    gl.uniform1f(u.uGhost, p.ghost);
    gl.uniform1f(u.uCardMode, p.cardMode);
    gl.uniform1f(u.uCardScale, p.cardScale);
    gl.uniform1f(u.uTiltGain, p.tiltGain);
    gl.uniform1f(u.uRelief, p.relief);
    gl.uniform1f(u.uLineDepth, p.lineDepth);
    gl.uniform1f(u.uTrail, motion.trail || 0);
    gl.uniform2f(u.uTrailVec, motion.vx || 0, motion.vy || 0);
    gl.uniform2f(u.uFit, this.config.fit[0], this.config.fit[1]);
    gl.uniform2f(u.uSafeOffset, this.config.safeArea.offset[0], this.config.safeArea.offset[1]);
    gl.uniform2f(u.uAspect, this.aspect[0], this.aspect[1]);
    gl.uniform3f(u.uView, view[0], view[1], view[2]);
    gl.drawArrays(gl.TRIANGLES, 0, 6);
  }

  /** 供第二阶段「保存到相册」使用：重绘一帧后立即取像素。 */
  capture(view, timeSec, finish, motion) {
    this.render(view, timeSec, finish, motion);
    return this.canvas.toDataURL("image/png");
  }
}
