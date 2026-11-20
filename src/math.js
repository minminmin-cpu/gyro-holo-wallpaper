/** 纯函数层：不触碰 DOM / WebGL，可被 Node 直接单元测试。 */
export const DEG = Math.PI / 180;
export const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/** 角度差，处理 359grad -> 1grad 的跨圈跳变，返回 (-180, 180]。 */
export function angleDelta(a, b) {
  let d = (a - b) % 360;
  if (d > 180) d -= 360;
  if (d < -180) d += 360;
  return d;
}

/** 取屏幕旋转角；老 WebView 回退到 window.orientation 的字符串/数值形式。 */
export function screenAngleDeg(screenObj, windowOrientation) {
  if (screenObj && screenObj.orientation && typeof screenObj.orientation.angle === "number") {
    return screenObj.orientation.angle;
  }
  const legacy = { "portrait-primary": 0, "portrait-secondary": 180, "landscape-primary": 90, "landscape-secondary": -90 };
  if (screenObj && typeof screenObj.msOrientation === "string" && legacy[screenObj.msOrientation] !== undefined) {
    return legacy[screenObj.msOrientation];
  }
  const o = Number(windowOrientation);
  if (Number.isFinite(o) && o !== 0) return -o;
  return 0;
}

/** 把设备坐标系的 (gamma, beta) 旋转到当前屏幕坐标系。 */
export function deviceTiltToScreen(gamma, beta, angleDeg) {
  const t = angleDeg * DEG;
  const c = Math.cos(t);
  const s = Math.sin(t);
  return { x: gamma * c - beta * s, y: gamma * s + beta * c };
}

/** 四元数工具，顺序为 [x, y, z, w]。 */
export function quatConjugate(q) { return [-q[0], -q[1], -q[2], q[3]]; }

export function quatMultiply(a, b) {
  const [ax, ay, az, aw] = a;
  const [bx, by, bz, bw] = b;
  return [
    aw * bx + ax * bw + ay * bz - az * by,
    aw * by - ax * bz + ay * bw + az * bx,
    aw * bz + ax * by - ay * bx + az * bw,
    aw * bw - ax * bx - ay * by - az * bz,
  ];
}

export function rotateVectorByQuat(v, q) {
  const [qx, qy, qz, qw] = q;
  const tx = 2 * (qy * v[2] - qz * v[1]);
  const ty = 2 * (qz * v[0] - qx * v[2]);
  const tz = 2 * (qx * v[1] - qy * v[0]);
  return [
    v[0] + qw * tx + qy * tz - qz * ty,
    v[1] + qw * ty + qz * tx - qx * tz,
    v[2] + qw * tz + qx * ty - qy * tx,
  ];
}

/** 相对姿态：以校准时的四元数为基准，消去用户手持的初始朝向。 */
export function relativeQuaternion(q, base) {
  return quatMultiply(quatConjugate(base), q);
}

/** 四元数通道 -> 归一化倾角 [-1, 1]。取局部 z 轴经相对旋转后的横向分量。 */
export function quatToTilt(q, maxViewXY = 0.78) {
  const v = rotateVectorByQuat([0, 0, 1], q);
  const k = Math.max(Math.abs(v[2]), 0.5);
  return [clamp(v[0] / k, -1, 1) * maxViewXY, clamp(v[1] / k, -1, 1) * maxViewXY];
}

/** 欧拉角通道 -> 归一化倾角 [-maxViewXY, maxViewXY]。 */
export function degToTilt(txDeg, tyDeg, maxTiltDeg = 38, maxViewXY = 0.78) {
  return [
    clamp(txDeg / maxTiltDeg, -1, 1) * maxViewXY,
    clamp(tyDeg / maxTiltDeg, -1, 1) * maxViewXY,
  ];
}

/** 帧率无关的一阶指数阻尼 + 死区；tau 越小跟手越紧，越大越稳。 */
export class DampedView {
  constructor(tau = 0.11) {
    this.tau = Math.max(tau, 0.001);
    this.x = 0;
    this.y = 0;
  }
  step(targetX, targetY, dt, deadband = 0) {
    const a = 1 - Math.exp(-Math.max(dt, 0) / this.tau);
    const shrink = (d) => (Math.abs(d) <= deadband ? 0 : d - Math.sign(d) * deadband);
    this.x += shrink(targetX - this.x) * a;
    this.y += shrink(targetY - this.y) * a;
    return [this.x, this.y];
  }
  reset() { this.x = 0; this.y = 0; }
}

/** 归一化倾角 -> RuiC 着色器所需的 uView 单位视线向量。 */
export function viewFromTilt(tx, ty) {
  const len = Math.hypot(tx, ty, 1);
  return [tx / len, ty / len, 1 / len];
}

/** RuiC 的视差公式（CPU 版，用于测试与降级渲染）：
 *  uv + uView.xy / max(abs(uView.z), 0.4) * depth * 0.10 */
export function parallaxUv(uv, view, depth) {
  const k = view[0] / Math.max(Math.abs(view[2]), 0.4);
  const m = view[1] / Math.max(Math.abs(view[2]), 0.4);
  return [uv[0] + k * depth * 0.10, uv[1] + m * depth * 0.10];
}
