# 陀螺仪全息动态壁纸 · 阶段一

把 [RuiC-card-skill](https://github.com/HRuiCcc/RuiC-card-skill) 的分层视差 + 镭射材质
搬进一张手机动态壁纸：倾斜手机时，彩虹镭射相位、镜面高光中心与四层画面的景深一起跟着视角走。

**本仓库只含阶段一**（技术方案 + 可运行的 Web 壁纸 + Android 集成骨架）。
阶段二已改为 **Android 生成器 App + 内置 Live Wallpaper**（放弃微信小程序），支持自定义配置文本/视觉卡片设计模型，方案见 `docs/阶段二技术方案.md`。

> **本项目完全免费、开源（MIT），不含计费 / 账号 / 广告 / 遥测。**
> 源码公开到 GitHub，Release 提供编译签名的 APK 下载；模型由用户自己配置 endpoint 与 Key，App 直连，不经过任何中间服务。

## 快速开始

```bash
cd gyro-holo-wallpaper

python tools/generate_assets.py      # 1. 生成 5 张分层素材（Pillow + numpy）
python tools/build_standalone.py     # 2. 打包单文件版 standalone.html
npm test                             # 3. 离线算法验收（Node，无需浏览器）
python tools/serve.py                # 4. 启动 HTTPS 预览（默认 :8443）
python tests/verify_render.py --capture   # 5. 无头浏览器截两帧 + 像素级验收
```

- **桌面预览**：`https://localhost:8443/`（localhost 属于安全上下文，可直接用 HTTP 也行）
- **手机真机**：`https://<局域网IP>:8443/`，需先信任 `.cert/cert.pem`；
  更省事的做法是 Android 上 `adb reverse tcp:8443 tcp:8443`，然后手机访问
  `http://localhost:8443/` —— localhost 同样是安全上下文，可绕开自签证书。
- **零依赖单文件**：直接双击 `standalone.html`，5 张 PNG 已内联为 data URI，
  无 CORS 问题；此模式下用指针拖动 / 自动巡游模拟体感。

调试参数（可叠加）：

| 参数 | 作用 |
|---|---|
| `?debug=1` | 显示 uView / fps / 传感器来源 HUD |
| `?autostart=1` | 跳过授权遮罩，便于 headless 截图 |
| `?view=0.4,0.2` | 锁定视角，用于像素级对比验收 |
| `?sensor=polyfill` | 强制使用 vendor 的 Generic Sensor polyfill 通道 |
| `?clean=1` | 隐藏全部 UI，输出纯净壁纸画面（截图 / 投屏 / 网页壁纸 App 用） |
| `?flat=1` | 全屏铺满壁纸（关闭官方卡片透视构图） |
| `?finish=3` | 直接指定质感：0 珠光 / 1 银箔 / 2 原画 / 3 烫金（官方演示偏烫金） |
| `?trail=1` | 强制开启时间残影，便于截图对比「静止干净 / 转动拖影」 |

## 目录

```
index.html                     ESM 入口（需 HTTP(S)）
standalone.html                单文件零依赖版（构建产物，可 file:// 打开）
src/config.js                  默认参数（与 card-config.json 对齐）
src/math.js                    纯函数：坐标归一化、四元数、阻尼、视差公式
src/holo-shader.js             移植自 RuiC 的 GLSL + 闪光跟随 glint / 重影 ghost
src/gyro.js                    三通道传感器抽象层、iOS 权限、校准、生命周期
src/renderer.js                纯 WebGL1 渲染器（1 draw call / 5 纹理）
src/main.js                    主循环、UI、省电策略、CSS-3D 兜底、__holoWallpaper 钩子
assets/*.png                   程序化生成的演示分层素材
tools/                         素材生成、单文件打包、HTTPS 预览
tests/smoke.mjs                离线验收
vendor/sensor-polyfills/       kenchris/sensor-polyfills（BSD-3，可选通道）
android/                       WallpaperService 集成骨架
docs/阶段一技术方案.md          完整方案、库选型、可行性评估、难点与应对
```

## 与 RuiC-card-skill 的关系

| RuiC 原件 | 本项目的用法 |
|---|---|
| `assets/web-template/app.js` 的 `common` / `frontFragment` GLSL | 原样移植 `parallax / spectrum / film / sweep / strength` 与四层合成系数 |
| `uView = inverse(root.matrixWorld) * camera.position` | 改为由陀螺仪四元数 / 欧拉角直接生成 |
| 拖拽旋转相机 | 改为手机姿态；保留指针拖动作为桌面降级 |
| `assets/subject|background|lineart|text|effects.png` | 沿用同名同画布（1024×1536）契约，可用 `validate_assets.py` 校验 |
| `window.__holo` 测试钩子 | 对应 `window.__holoWallpaper`（`getState / setView / capture`） |
| 卡片 3D 几何 / `card.blend` | 阶段一不需要（壁纸是全屏 quad），正式出图时再走 `run_pipeline.py` |

新增 `glintCenter = 0.5 + uView.xy * uGlintRange` 的镜面高光与沿视线偏移的轻微重影，
使「闪光跟随」在锁屏尺寸下也清晰可见；镭射主相位仍由 `uView` 驱动，`uTime` 只推星点，
避免退化成循环视频。

## 验证

```bash
npm test                                                        # 13 项算法 / 契约单测
python <RuiC-card-skill 目录>/scripts/validate_assets.py .   # 素材契约
python tests/verify_render.py --capture                           # 渲染像素验收
```

渲染验收用两个固定视角各截一帧，断言高光的方向性位移。本机实测
（2026-11-12，headless Edge / SwiftShader，390×844，中央区域 350×500）：

| 指标 | 实测值 | 含义 |
|---|---|---|
| 平均像素差 | 42.46 | 两帧确有显著变化 |
| 左半区 Δ | −32.37 | 右倾时左半变暗 |
| 右半区 Δ | +20.39 | 右倾时右半变亮 |
| 方向性差值 | **+52.76** | 高光随视角定向移动，而非随机动画 |
| 高光重心位移 | **+36.4 px** | 与 `uGlintRange` 的预期方向一致 |

`window.__holoWallpaper.capture()` 可导出当前帧 PNG，供第二阶段「保存到相册」复用。

## 与官方演示的视觉对齐

直接提取仓库自带的 `demo-after.gif`（135 帧）与 `demo-before.gif`（161 帧）关键帧比对，
结论与对应实现：

| 官方演示特征 | 本项目实现 |
|---|---|
| 闪光是**覆盖全卡面的箔光 + 斜向扫光带**，暖金/彩虹随视角流走，不是圆形聚光灯 | 保留 `film/sweep/spectrum` 原式；新增 `glintBand`（沿 0.72/0.45 方向的斜向光带）+ `glintCore`，中心由 `uView.xy * uGlintRange` 驱动，边缘带 `r*1.2` 色散 |
| 「重影」主要是**分层空间错位**：主体浮出卡框、线稿/特效与主体错开 | 主体 `subjectDepth`、线稿 `lineDepth`、特效 `effectsDepth`、背景 `backgroundDepth` 各自独立；`uGhost` 沿视线错开一份主体 alpha；`uRelief` 让主体轻微前凸 |
| 完整卡片随视角倾斜浮在背景上，卡框与文字层稳定 | `cardUv()` 由 `uView` 生成近似单应变换；文字层不做视差；卡片外用暗化背景延伸 |
| 静止帧干净、没有循环动画感 | 主镭射相位全部由 `uView` 驱动，`uTime` 只推星点；时间残影 `uTrail` 由角速度动态置零，静止自动消失 |

另外提供快速转动时的**彩色时间残影**：3 次递减 tap 只叠加主体轮廓之外的部分
（避免整块过曝），`?trail=1` 可固定开启做对比。

## 照片闪光重影测试（第一轮效果验证）

```bash
python tools/make_photo_wallpaper.py --photo "你的照片.jpg"
```

脚本会把横躺的照片自动转正（逆时针 90°），按 RuiC 契约生成 5 层素材，注入
`ghost/lineDepth/trail` 强化参数，并用 RuiC 的 `validate_assets.py` 体检，
最终产出 **`examples/photo-test/photo-wallpaper.html`**（约 2.1 MB，单文件可双击）。

实测（390×844 headless）：照片细节完整保留（过曝饱和像素仅 2.4%），
左/右倾平均像素差 20.22、方向性变化 42.9%，残影开/关局部最大差 188.3，
照片矩形边缘可见清晰的双层彩色重影。截图在 `examples/photo-test/photo-*.png`。

完整的技术方案、开源库选型理由、兼容性与限制评估、难点应对，见 `docs/阶段一技术方案.md`。
阶段二（Android App + 可配置文本/视觉卡片设计模型 + 内置动态壁纸）的产品与技术设计，见 `docs/阶段二技术方案.md`。

## 模型配置（自带密钥，不入库）

App 的卡片设计依赖你自己配置的文本 / 视觉 Chat 服务，仓库**不内置任何模型、地址或密钥**：

- 在 App「模型」页填写 Base URL、模型 ID 和 API Key；密钥用 AndroidKeystore 加密后只存设备私有目录。
- 字段说明见 `examples/provider-config.example.json`（全部是占位符）。
- 发布包首次启动的模型列表为空，必须由使用者自行添加。

## 开源与发布

- **许可**：本项目代码 MIT（`LICENSE`，把署名换成你的 GitHub 名/组织名）；第三方版权声明见 `THIRD_PARTY_NOTICES.md`（RuiC-card-skill MIT / sensor-polyfills BSD-3）。
- **不入库**：`standalone.html` 是构建产物（`python tools/build_standalone.py` 生成）；私人照片与 `examples/photo-test/`、上游 demo 抽帧、验收截图、`.cert/`、keystore 均已由 `.gitignore` 排除。照片壁纸用 `python tools/make_photo_wallpaper.py --photo <你的图片>` 自行生成。
- **Release**：push tag `v*` 触发 `.github/workflows/build-apk.yml`，自动签名构建并上传 `app-release.apk` + `APK_SHA256.txt`。需在仓库 Secrets 配置 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
- **APK 构建**：`cd android && gradle :app:assembleDebug`（包名 `com.example.holowallpaper`，minSdk 24 / targetSdk 35）。
  本地工具链需 JDK 17 + Android SDK 35 + Gradle 8.9，路径写在 `android/local.properties`（不入库）。
- **签名**：`android/app/build.gradle.kts` 只从环境变量读取 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，
  仓库内不含任何 keystore 或密码；未提供时构建出 unsigned 包。
- 贡献与本地构建见 `CONTRIBUTING.md`。
