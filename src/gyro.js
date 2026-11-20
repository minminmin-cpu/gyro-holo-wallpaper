/** 陀螺仪抽象层：三条数据通路 + iOS 权限 + 校准 + 生命周期。
 *  输出统一为归一化倾角 {x, y} ∈ [-maxViewXY, maxViewXY]，与传感器类型解耦。 */
import {
  angleDelta, screenAngleDeg, deviceTiltToScreen, degToTilt,
  quatToTilt, quatConjugate, relativeQuaternion, clamp,
} from "./math.js";

export const SOURCE = {
  GENERIC: "generic-sensor",
  POLYFILL: "sensor-polyfill",
  ORIENTATION: "deviceorientation",
  FALLBACK: "fallback",
};

const isIOS = () => /iPhone|iPad|iPod/i.test(navigator.userAgent) ||
  (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);

export class GyroSource {
  constructor(options = {}) {
    this.options = {
      maxTiltDeg: 38, maxViewXY: 0.78, frequency: 60,
      invertX: false, invertY: false,
      useGenericSensor: true, usePolyfill: true, forcePolyfill: false,
      onStatus: () => {}, onError: () => {}, onSample: () => {},
      ...options,
    };
    this.source = SOURCE.FALLBACK;
    this.enabled = false;
    this.baseEuler = null;
    this.baseQuat = null;
    this.lastSample = { x: 0, y: 0, t: 0 };
    this._sensor = null;
    this._orientationHandler = null;
    this._visibilityHandler = null;
    this._pointer = { x: 0, y: 0 };
  }

  _status(text, source) {
    if (source) this.source = source;
    this.options.onStatus(text, this.source);
  }

  /** 必须由一个真实的用户手势（click/touchend）直接调用。 */
  async requestPermission() {
    const DOE = window.DeviceOrientationEvent;
    if (DOE && typeof DOE.requestPermission === "function") {
      const result = await DOE.requestPermission();
      if (result !== "granted") throw new Error("iOS 拒绝了「动作与方向」访问权限");
    }
    const DME = window.DeviceMotionEvent;
    if (DME && typeof DME.requestPermission === "function") {
      try { await DME.requestPermission(); } catch (_) { /* 方向事件不依赖 motion 授权 */ }
    }
  }

  async start() {
    await this.requestPermission();
    const o = this.options;
    const force = new URLSearchParams(location.search).get("sensor") === "polyfill" || o.forcePolyfill;
    const canPolyfill = o.usePolyfill && location.protocol !== "file:";

    // iOS 只走 deviceorientation：其 webkitCompassHeading 在倒置方向有 180° 翻转缺陷。
    if (force && canPolyfill) {
      try {
        const mod = await import("../vendor/sensor-polyfills/motion-sensors.js");
        await this._startSensor(mod.RelativeOrientationSensor, SOURCE.POLYFILL);
        return this.source;
      } catch (err) {
        this.options.onError("polyfill 加载失败，已回退：" + err.message);
      }
    }
    if (!isIOS() && o.useGenericSensor && typeof window.RelativeOrientationSensor === "function") {
      try {
        await this._startSensor(window.RelativeOrientationSensor, SOURCE.GENERIC);
        return this.source;
      } catch (err) {
        this.options.onError("Generic Sensor 启动失败，已回退：" + err.message);
      }
    }
    if ("ondeviceorientation" in window || typeof DeviceOrientationEvent !== "undefined") {
      this._startOrientation();
      return this.source;
    }
    this._status("设备无姿态传感器，已启用指针/自动巡游", SOURCE.FALLBACK);
    return this.source;
  }

  async _startSensor(SensorClass, source) {
    this._sensor = new SensorClass({ frequency: this.options.frequency, referenceFrame: "device" });
    this._sensor.addEventListener("reading", () => {
      const q = this._sensor.quaternion;
      if (!q) return;
      this._emitQuat([q[0], q[1], q[2], q[3]]);
    });
    this._sensor.addEventListener("error", (e) => {
      const name = (e.error && e.error.name) || "SensorError";
      this.options.onError(name + "（传感器被拒绝或占用）");
    });
    this._sensor.start();
    this.baseQuat = null;
    this.enabled = true;
    this._status("四元数姿态通道已启动", source);
    this._bindVisibility();
  }

  _emitQuat(q) {
    if (!this.baseQuat) this.baseQuat = q.slice();
    // 对齐 RuiC 的 camera.position.applyMatrix4(root.matrixWorld.invert())：
    // 求相机在卡面局部坐标系中的方向，因此对相对旋转取共轭。
    const rel = quatConjugate(relativeQuaternion(q, this.baseQuat));
    let [x, y] = quatToTilt(rel, this.options.maxViewXY);
    if (this.options.invertX) x = -x;
    if (this.options.invertY) y = -y;
    this._emit(x, y);
  }

  _startOrientation() {
    this._orientationHandler = (e) => {
      if (e.alpha === null || e.beta === null || e.gamma === null) return;
      if (!this.baseEuler) this.baseEuler = { alpha: e.alpha, beta: e.beta, gamma: e.gamma };
      const b = this.baseEuler;
      const dAlpha = angleDelta(e.alpha, b.alpha);
      const dBeta = angleDelta(e.beta, b.beta);
      const dGamma = e.gamma - b.gamma;
      // alpha（偏航）受磁力计漂移影响大，仅以小权重参与横向偏移。
      const angle = screenAngleDeg(window.screen, window.orientation);
      const s = deviceTiltToScreen(dGamma + dAlpha * 0.12, dBeta, angle);
      let [x, y] = degToTilt(s.x, s.y, this.options.maxTiltDeg, this.options.maxViewXY);
      if (this.options.invertX) x = -x;
      if (this.options.invertY) y = -y;
      this._emit(x, y);
    };
    window.addEventListener("deviceorientation", this._orientationHandler, true);
    this.enabled = true;
    this._status("deviceorientation 通道已启动", SOURCE.ORIENTATION);
    this._bindVisibility();
  }

  _emit(x, y) {
    const sample = {
      x: clamp(x, -1, 1), y: clamp(y, -1, 1),
      t: performance.now(), source: this.source,
    };
    this.lastSample = sample;
    this.options.onSample(sample);
  }

  _bindVisibility() {
    if (this._visibilityHandler) return;
    this._visibilityHandler = () => {
      if (!this._sensor) return;
      if (document.hidden) { try { this._sensor.stop(); } catch (_) {} }
      else { try { this._sensor.start(); } catch (_) {} }
    };
    document.addEventListener("visibilitychange", this._visibilityHandler);
  }

  /** 以当前手持姿态为中性点重新校准。 */
  calibrate() {
    this.baseEuler = null;
    this.baseQuat = null;
    this._pointer = { x: 0, y: 0 };
    this._status("已重新校准中性姿态", this.source);
  }

  setInvert(x, y) {
    this.options.invertX = !!x;
    this.options.invertY = !!y;
  }

  /** 桌面/无传感器时的指针输入，参数为 [-1, 1]。 */
  setPointer(x, y) {
    this._pointer = { x: clamp(x, -1, 1), y: clamp(y, -1, 1) };
    if (this.source === SOURCE.FALLBACK) {
      this._emit(this._pointer.x * this.options.maxViewXY, -this._pointer.y * this.options.maxViewXY);
    }
  }

  /** 无输入时的缓慢自动巡游，保证桌面预览也能看到闪光游走。 */
  autoTour(seconds) {
    return [
      Math.sin(seconds * 0.42) * 0.55 * this.options.maxViewXY,
      Math.sin(seconds * 0.53 + 1.1) * 0.32 * this.options.maxViewXY,
    ];
  }

  stop() {
    this.enabled = false;
    if (this._sensor) { try { this._sensor.stop(); } catch (_) {} }
    if (this._orientationHandler) window.removeEventListener("deviceorientation", this._orientationHandler, true);
    if (this._visibilityHandler) document.removeEventListener("visibilitychange", this._visibilityHandler);
    this._sensor = null;
    this._orientationHandler = null;
    this._visibilityHandler = null;
  }
}
