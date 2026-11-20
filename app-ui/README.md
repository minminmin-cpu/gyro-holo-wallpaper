# 阶段二 · 移动端前端界面

阶段二方案里 `MainActivity` 的**界面层实现**。一份可直接运行的单文件前端应用
（`index.html`，零依赖、零构建），既能在手机浏览器 / PWA 里用，也能作为 Android App 内
WebView 的 UI 层；原生 Jetpack Compose 版本可照同一套设计 token 与组件映射实现。

> 界面里的壁纸预览是**前端 2D 预览**；真机上的系统动态壁纸仍由
> `WallpaperService.Engine + GLES20` 渲染（见 `docs/阶段二技术方案.md`）。

## 运行

```bash
# 直接双击 app-ui/index.html 即可（file:// 也能跑，不依赖任何服务）
# 或起本地服务后手机访问：
python tools/serve.py            # https://<局域网IP>:8443/app-ui/
```

调试参数：`?debug=1` 显示 fps / 设备分级 / 溢出量，`?page=preview|models|library` 直达页面，
`?static=1` 强制静态降级（等同系统「减少动态效果」）。

## 四个页面

| 页面 | 对应方案模块 | 内容 |
|---|---|---|
| 创作 | `LayerBuilder` 入口 | 上传照片 + 设计描述，模型规划卡片 JSON 后本地合成五层 |
| 预览 | `PreviewScreen` | 手机外框实时预览、镭射/残影/景深/闪光增益滑块、四种质感、设为壁纸、导出当前帧、启用体感 |
| 模型 | `ModelSettingsScreen` | 多模态能力提示卡、Provider 列表、添加模型表单（协议/URL/Key/能力声明/测试连接） |
| 我的 | `ProfileStore` | Profile 网格、设备与省电状态、低端机分级说明 |

界面刻意还原了方案里的关键产品决策：BYOM（Key 只存本机、不代理请求）、
「文本/视觉模型只返回卡片设计 JSON」的精确提示，以及「壁纸服务不联网」的说明。首次启动模型列表为空，服务地址、模型 ID 和密钥完全由用户配置；旧协议必须重新选择后保存。

## 闪光与重影动效

| 效果 | 实现 | 动画属性 |
|---|---|---|
| 光晕闪烁 | 3 个固定径向渐变层，缓慢呼吸 + 位移 | `transform` / `opacity` |
| 镭射扫光 | 卡片与按钮的伪元素斜向渐变，循环扫过 | `transform: translate3d` |
| 残影拖尾 | 4 个预创建光斑，按历史队列插值跟随指针，透明度递减 | `transform` / `opacity` |
| 星点粒子 | 单 canvas，数量按屏幕面积自适应 | `fillRect` + `globalAlpha` |
| 卡片倾斜 | 指针 / `deviceorientation` → 指数阻尼 → CSS 变量 → phone 3D 旋转 | `transform` |
| 预览闪光重影 | 离屏缓存的卡面底图 + 3 层偏移残影 + 缓存的彩虹箔与径向高光 | `drawImage` |

**只用 GPU 合成友好的属性**（transform / opacity），不改 `box-shadow`、不动画 `filter`、
不动画 `background` 渐变；离屏渐变只构建一次，每帧只做 `drawImage` / `fillRect`。

## 性能策略

- **单一 rAF 调度器**：粒子、拖尾、倾角、预览共用一个循环，预绑定回调避免每帧创建函数。
- **静止降帧**：无输入且倾角稳定时从 60fps 降到 12fps；`document.hidden` 直接停循环。
- **设备分级**：`prefers-reduced-motion` → 静态；`deviceMemory<=2` 或 `hardwareConcurrency<=4`
  → 省电模式（关粒子与残影、30/8fps）；其余高画质（≤52 粒子、60/12fps）。
- **DPR 上限 1.75**，粒子数 `min(52, 面积/16000)`，低端机为 0。
- **零 DOM 增删**：残影层与光晕层都是预创建固定节点，只改样式。
- **离屏缓存**：卡面底图、彩虹箔、高光圆三张 offscreen canvas 只在尺寸/质感变化时重建。
- 交互全部 `passive` 监听，`resize` 做 120ms 防抖。

## 屏幕适配

- `viewport-fit=cover` + `env(safe-area-inset-*)`（刘海 / 底部手势条）
- `100dvh` 配合 `100vh` 回退；`clamp()` 字号；`min()` / `max()` 控制内容最大宽度 560px
- 断点：≤340px 收紧间距与 Tab；≥600px 内容居中；横屏且 ≥700×520 时切双栏 + 横向 Tab
- 网格 `repeat(auto-fill, minmax(140px, 1fr))` 自适应折叠屏 / 平板
- `overflow-x: hidden` + `overscroll-behavior-y: contain`，验证时用 `data-ovf` 断言无横向溢出

## 与 Jetpack Compose 的映射

| Web 组件 | Compose 对应 |
|---|---|
| `.card` / `.card.glossy` | `Card` + 自定义 `drawBehind` 扫光 `Modifier` |
| `.tabbar` | `NavigationBar` + `NavigationBarItem` |
| `#tabs` / `.page` 切换 | `NavHost` + `rememberNavController()` |
| `--violet/--cyan/--gold` | `Color.kt` / `MaterialTheme.colorScheme` |
| `.halos` / `#ghosts` | `Canvas` + `Animatable`（`graphicsLayer` 只改 translation/alpha） |
| 统一 rAF 调度器 | `Choreographer` / `withFrameNanos` 单一 `LaunchedEffect` |
| `state.params` | `ViewModel` + `StateFlow` |
| `localStorage`（本 demo 未启用） | `DataStore` / Room（`ProfileStore`） |
| `deviceorientation` | `SensorManager.TYPE_GAME_ROTATION_VECTOR` |

## 验证

```bash
node app-ui/tests/verify_ui.mjs
```

脚本用 CDP `Emulation.setDeviceMetricsOverride` 精确模拟 **320×640 / 360×780 /
390×844 / 430×932 / 844×390** 五种视口与四个页面，断言 `innerWidth` 精确匹配、
`main` 无横向溢出、最后一个 Tab 与品牌徽章不出界；另检查「减少动效」降级
（tier=none、粒子=0）并采样软件渲染 fps。截图输出到 `app-ui/tests/shots/`。

> 踩坑记录：headless Edge 的 `--window-size` 会被钳到最小宽度，`--screenshot`
> 只裁左上角，用它验证小屏会得到「无溢出」的假阳性（视口其实是 496px）。
> 移动端适配必须用 CDP 模拟视口才可信。

## Android 原生桥

`MainActivity` 用 WebView 加载本页并注入 `HoloAndroid`。`Native.json(name, arg)` 在桥存在时
调用原生能力，否则自动回退到浏览器演示模式 —— 同一份前端可在 PWA、桌面预览和 App 内共用。

| 前端动作 | 桥方法 | 原生实现 |
|---|---|---|
| 模型列表 / 保存 | `listProviders` / `saveProvider` | ProviderRegistry + AndroidKeystore 加密 Key |
| 测试连接 | `testProvider` | 异步 `probe()` 探活 `/models`，回调 `onTestResult` |
| 生成壁纸 | `generate` | 后台线程：照片 + 描述 → Chat 设计 JSON → 严格白名单校验 → 原始照片本地合成 5 层与配置 → 原子写；失败明确报错、不落替代 Profile |
| 选择照片 | `pickPhoto` | 系统文件选择 → 复制到 cache，回调 `onPhotoPicked` |
| 设为壁纸 | `setWallpaper` | `ACTION_CHANGE_LIVE_WALLPAPER` |
| 导出当前帧 | `exportFrame` | 保存到 `files/exports/` |
| 收藏列表 / 切换 | `listProfiles` / `setActiveProfile` | Profile 目录扫描 + 壁纸服务热加载 |

`tools/build_app_ui.py` 按原始字节把本页同步到 `android/app/src/main/assets/app-ui/`；
`tools/verify_android.py` 会校验桥方法名与回调一致性。

## 已知边界

iOS 的 `DeviceOrientationEvent.requestPermission()` 必须由用户手势触发且需要
HTTPS/localhost；`file://` 下体感不可用，但指针拖动与自动光效仍完整可用。
