# 第三方来源说明

| 项 | 内容 |
|---|---|
| 项目 | `kenchris/sensor-polyfills`（W3C Generic Sensor API polyfills） |
| 地址 | https://github.com/kenchris/sensor-polyfills |
| npm | `motion-sensors-polyfill` |
| 许可 | BSD-3-Clause（见同目录 `LICENSE.md`，Copyright (c) 2017 Intel Corporation） |
| 引入文件 | `src/motion-sensors.js`、`src/sensor.js`（原样复制，未修改） |
| 取用时间 | 2026-11-12，master 分支 |
| 状态 | 上游维护已停滞（npm 最近一次发布约 4 年前），本项目仅作**可选增强通道** |

## 为什么引入

Chromium 原生提供 `RelativeOrientationSensor`，可直接给出四元数（避免欧拉角万向锁与
alpha 跨圈跳变），并可精确指定 `frequency`。但 Safari / Firefox 未实现 Generic Sensor API。
该 polyfill 把 `DeviceOrientationEvent` 包装成同一套 `Sensor` 接口，其内部写法为
`export const RelativeOrientationSensor = window.RelativeOrientationSensor || class ...`，
会优先复用原生实现、不会覆盖浏览器自带能力，因此作为「统一接口」的兜底是安全的。

## 为什么不默认启用

- 上游 README 明确记录：iOS 上 `AbsoluteOrientationSensor` 依赖非标准的
  `webkitCompassHeading`，在 portrait-secondary 方向会 180° 翻转；
- iOS 13+ 的 `requestPermission()` 必须由用户手势触发，polyfill 不处理授权 UI；
- 项目已停更多年，不宜成为运行时硬依赖。

因此本项目默认路径是：iOS -> `deviceorientation`；Android/Chromium -> 原生
`RelativeOrientationSensor`；显式加 `?sensor=polyfill` 才动态 `import()` 本目录的模块。
`file://` 单文件版会直接禁用该通道（ES module 动态导入在 file 协议下会被 CORS 拦截）。
