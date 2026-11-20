/** 默认规格：字段与 RuiC-card-skill 的 references/config.example.json 对齐，
 *  额外增加 glint、ghost、gyro、power 四组壁纸专用参数。 */
export const ASSET_KEYS = ["subject", "background", "text", "lineart", "effects"];

export const DEFAULT_CONFIG = {
  title: "星穹棱镜",
  subtitle: "PRISM / CELESTIAL",
  edition: "001 / 001",
  finish: 0,
  parameters: {
    subjectScale: 1.25,
    subjectDepth: 0.40,
    backgroundDepth: -0.25,
    effectsScale: 1.0,
    effectsDepth: 0.50,
    foil: 0.65,
    glintGain: 0.85,
    glintSigma: 0.20,
    glintRange: 0.42,
    ghost: 0.28,
    // 官方演示对齐参数：卡片透视 + 分层错位重影 + 快速转动时的时间残影
    cardMode: 1,
    cardScale: 1.0,
    tiltGain: 0.34,
    relief: 0.55,
    lineDepth: 0.22,
    trail: 0.85,
    // 官方演示的暖金卡面使用「烫金」质感；默认仍为珠光，见 README 切换说明
    finish: 0,
  },
  safeArea: { scale: 1.0, offset: [0, 0] },
  fit: [1, 1],
  gyro: {
    maxTiltDeg: 38,
    maxViewXY: 0.78,
    tau: 0.11,
    deadbandDeg: 0.6,
    invertX: false,
    invertY: false,
    frequency: 60,
    useGenericSensor: true,
    usePolyfill: true,
  },
  power: {
    maxDpr: 1.75,
    idleFps: 12,
    activeFps: 60,
    stillEpsilon: 0.0015,
  },
};

export const FINISHES = { pearl: 0, silver: 1, original: 2, gold: 3 };
export const FINISH_LABELS = ["珠光", "银箔", "原画", "烫金"];

export const ASSET_URLS = {
  subject: "./assets/subject.png",
  background: "./assets/background.png",
  text: "./assets/text.png",
  lineart: "./assets/lineart.png",
  effects: "./assets/effects.png",
};

// 构建自定义单文件壁纸（例如用用户照片做测试）时可注入覆盖参数。
if (typeof window !== "undefined" && window.__HOLO_PARAMS__) {
  Object.assign(DEFAULT_CONFIG.parameters, window.__HOLO_PARAMS__);
}
