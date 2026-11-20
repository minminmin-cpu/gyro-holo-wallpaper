/** 片元着色器：移植自 RuiC-card-skill 的 assets/web-template/app.js
 *  （common / frontFragment），并对照仓库自带 demo-after.gif / demo-before.gif
 *  的官方演示帧做视觉对齐：
 *
 *  1. 闪光 = 覆盖全卡面的箔光（spectrum）+ 斜向扫光带（sweep）
 *     + 随视角游走的镜面高光中心；不是孤立的圆形聚光灯。
 *  2. 重影 = 主体 / 线稿 / 特效 / 背景各用独立深度做视差错位（空间重影，
 *     官方「图层分散」演示的本质），叠加快速转动时的多 tap 时间残影。
 *  3. 加入卡片透视投影，让卡片在壁纸中随手机姿态倾斜，还原演示的构图。
 *
 *  使用 GLSL ES 1.00，WebGL1 / WebGL2 均可运行。原 three.js 版本末尾的
 *  pow(col, 2.2) + <colorspace_fragment> 是一次 sRGB<->linear 往返，净效果近似恒等，
 *  这里不做 three 的编码步骤，因此直接输出 col。 */
export const VERTEX_SHADER = `
attribute vec2 aPosition;
varying vec2 vUv;
void main() {
  vUv = vec2(aPosition.x * .5 + .5, 1.0 - (aPosition.y * .5 + .5));
  gl_Position = vec4(aPosition, 0.0, 1.0);
}`;

export const FRAGMENT_SHADER = `
precision highp float;
varying vec2 vUv;

uniform float uTime, uFoil, uScale, uDepth, uBgDepth, uFinish;
uniform float uHasLine, uHasFx, uSafeScale, uFxDepth;
uniform float uGlintGain, uGlintSigma, uGlintRange, uGhost;
uniform float uCardMode, uCardScale, uTiltGain, uRelief, uLineDepth;
uniform float uTrail;
uniform vec2 uTrailVec, uFit, uSafeOffset, uAspect;
uniform vec3 uView;
uniform sampler2D tSubject, tBackground, tText, tLine, tEffects;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1,311.7))) * 43758.5453); }
float inside(vec2 p) { return step(0.,p.x)*step(0.,p.y)*step(p.x,1.)*step(p.y,1.); }
vec2 parallax(vec2 uv, float depth) {
  return uv + uView.xy / max(abs(uView.z), .4) * depth * .10;
}
vec3 spectrum(float phase) {
  return .66 + .25 * cos(6.28318 * (phase + vec3(0., .33, .67)));
}
float strength() { return abs(uFinish - 2.0) < 0.05 ? 0. : uFoil; }
vec3 film(vec2 uv) {
  float phase = uv.x * .85 + uv.y * .55 + uView.x * 1.5 - uView.y * .9;
  if (uFinish > 2.5) {
    float hi = 0.5 + 0.5 * sin(phase * 6.28318);
    float glint = 0.5 + 0.5 * cos((phase + 0.25) * 6.28318);
    vec3 deep = vec3(.72, .50, .20);
    vec3 bright = vec3(1.00, .90, .60);
    return mix(deep, bright, hi * .7 + glint * .3);
  }
  vec3 color = spectrum(phase);
  return mix(color, vec3(dot(color,vec3(.2126,.7152,.0722))), step(.5,uFinish));
}
float sweep(vec2 uv) {
  return pow(.5+.5*sin((uv.x*.72+uv.y*.45+uView.x*1.2+uView.y*.6)*6.283),10.);
}

// 屏幕 uv -> 卡面 uv：由 uView 驱动的近似单应变换，还原演示中卡片随手倾斜的透视。
vec2 cardUv(vec2 uv, vec3 v) {
  vec2 p = (uv - .5) * uAspect * 2.0;
  float rx = v.y * uTiltGain;
  float ry = -v.x * uTiltGain;
  float z = max(1.0 + dot(p, vec2(-ry, rx)) * .42, .55);
  vec2 q = (p + vec2(ry, -rx) * .16) / z;
  // 注意：half 是 GLSL ES 保留字，变量名必须用 halfSize。
  vec2 halfSize = vec2(.355, .569) * uCardScale;
  return q / (2.0 * halfSize) + .5;
}

void main() {
  vec2 uv = vUv;
  vec2 cuv = mix(uv, cardUv(uv, uView), uCardMode);
  float edgeDist = min(min(cuv.x, 1.-cuv.x), min(cuv.y, 1.-cuv.y));
  float cardMask = mix(1.0, smoothstep(-.012, .012, edgeDist), uCardMode);

  vec2 su = ((parallax(cuv,uDepth)-.5)*uScale/uFit+.5)*uSafeScale+uSafeOffset;
  su = (su - .5) * (1.0 + uRelief * .06) + .5;
  // 线稿用独立深度：转动时与主体错开，形成官方演示里的双重轮廓重影。
  vec2 lu = ((parallax(cuv,uLineDepth)-.5)*uScale/uFit+.5)*uSafeScale+uSafeOffset;
  vec2 bu = parallax(cuv,uBgDepth);
  vec2 eu = parallax(cuv,uFxDepth);

  vec4 subject = texture2D(tSubject,clamp(su,0.,1.));
  subject.a *= inside(su);
  vec3 bg = texture2D(tBackground,clamp(bu,0.,1.)).rgb;
  vec3 col = mix(bg,subject.rgb,subject.a);

  // 空间重影：沿视线方向错开一份主体 alpha，主体层「浮」在卡框之外的观感。
  vec2 ghostUv = clamp(su + uView.xy * uGhost * .06, 0., 1.);
  float ghostA = texture2D(tSubject, ghostUv).a * inside(ghostUv);
  col += subject.rgb * ghostA * uGhost * .12 * (1. - subject.a);

  // 时间残影：只在主体轮廓之外叠加逐帧偏移的彩色残像，避免整块主体过曝。
  // uTrail 由 JS 按角速度动态置零，因此静止画面干净、与官方静态帧一致。
  if (uTrail > .001) {
    vec3 trail = vec3(0.);
    float baseA = subject.a;
    for (int i = 1; i <= 3; i++) {
      float f = float(i);
      vec2 toff = uTrailVec * f * .024;
      vec4 ts = texture2D(tSubject, clamp(su + toff, 0., 1.));
      float ghostEdge = max(0., ts.a - baseA) * inside(su + toff);
      float w = .78 / (f * f);
      vec3 tint = spectrum(uView.x * 1.5 + f * .17 + .3);
      if (abs(uFinish - 1.0) < .05) tint = vec3(.82,.88,.96);
      trail += tint * ghostEdge * w;
    }
    col += trail * uTrail;
  }

  if (uFinish > 2.5) col = col * vec3(1.02, .95, .78) + vec3(.05, .012, 0.0);

  vec4 fx = texture2D(tEffects,clamp(eu,0.,1.));
  col = mix(col,fx.rgb,fx.a*uHasFx);

  vec3 foil = film(cuv);
  float amount = strength();
  float luminance = dot(col,vec3(.2126,.7152,.0722));
  float band = sweep(cuv);
  float goldBoost = uFinish > 2.5 ? 1.7 : 1.0;
  col *= 1. - amount * .21 * (1.-foil) * (.2 + band*.8);
  col += foil * amount * band * goldBoost * (.065 + .11*(1.-luminance));

  // 闪光跟随：斜向扫光带 + 镜面点，中心由 uView.xy 驱动，色散随距离展开。
  vec2 glintCenter = vec2(.5) + uView.xy * uGlintRange;
  vec2 d = (cuv - glintCenter) * uAspect;
  float r = length(d);
  float axis = dot(cuv - glintCenter, normalize(vec2(.72,.45)));
  float s2 = max(uGlintSigma * uGlintSigma, 1e-4);
  float glintBand = exp(-axis * axis / (s2 * 2.6));
  float glintCore = exp(-r * r / (s2 * .55));
  float phase = cuv.x * .85 + cuv.y * .55 + uView.x * 1.5 - uView.y * .9 + r * 1.2;
  vec3 glintColor = spectrum(phase);
  if (abs(uFinish - 1.0) < .05) glintColor = vec3(.82,.88,.96);
  col += glintColor * (glintBand * .55 + glintCore * .75) * amount * uGlintGain;

  float edge = 1.-smoothstep(.015,.06,edgeDist);
  col = mix(col,foil*.75+.21,edge*amount*(uFinish > 2.5 ? .42 : .3));

  vec2 cell = floor(cuv*vec2(480.,720.));
  float flake = step(.994,hash(cell))*pow(.5+.5*sin(hash(cell+8.)*30.+uView.x*20.+uTime*.6),10.);
  col += foil*flake*amount*.13;

  float line = (1.-smoothstep(.06,.25,texture2D(tLine,clamp(lu,0.,1.)).r))*uHasLine;
  col += line*inside(lu)*subject.a*band*amount*.055;

  vec4 text = texture2D(tText,cuv);
  col = mix(col,text.rgb,text.a);

  // 卡片之外：暗化的背景延伸 + 柔和边缘，让卡片像浮在壁纸上。
  vec3 outer = texture2D(tBackground, clamp(uv*.8+.1,0.,1.)).rgb * .20;
  outer += vec3(.02,.025,.05);
  col = mix(outer, col, cardMask);

  gl_FragColor = vec4(clamp(col,0.,1.),1.);
}`;

export const UNIFORM_NAMES = [
  "uTime", "uFoil", "uScale", "uDepth", "uBgDepth", "uFinish",
  "uHasLine", "uHasFx", "uSafeScale", "uFxDepth",
  "uGlintGain", "uGlintSigma", "uGlintRange", "uGhost",
  "uCardMode", "uCardScale", "uTiltGain", "uRelief", "uLineDepth",
  "uTrail", "uTrailVec",
  "uFit", "uSafeOffset", "uAspect", "uView",
  "tSubject", "tBackground", "tText", "tLine", "tEffects",
];
