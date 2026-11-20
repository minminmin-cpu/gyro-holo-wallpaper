# Android 工程（阶段二 M1 + M2）

这是**可直接用 Android Studio 打开的 Gradle 工程**：`MainActivity`（WebView 生成器界面）与
`HoloWallpaperService`（真·系统动态壁纸）在同一个 APK 里，默认同进程，Profile 离线共享。
M2 把 `app-ui/` 的完整界面接进 WebView，并实现模型配置层、端上拆层与 Profile 生成。

## 构建环境

| 项 | 版本 |
|---|---|
| JDK | **17**（已安装在 `E:/java/jdk-17`，不占 C 盘） |
| Android SDK | compileSdk 35 / minSdk 24 / targetSdk 35 |
| Gradle | 8.9（AGP 8.7.3 / Kotlin 2.0.21） |
| 依赖 | 无外部依赖，仅 Kotlin stdlib + framework API（不依赖 AndroidX / Compose） |

```bash
# 方式 A：Android Studio -> Open -> 选择 android/ 目录，IDE 会生成 gradle wrapper
# 方式 B：命令行（需已装 JDK17 + Android SDK + Gradle 8.9）
cd android
gradle :app:assembleDebug
```

> 仓库**不提交 `gradle-wrapper.jar`**（二进制），CI 用 `gradle/actions/setup-gradle@v4`
> 提供的 `gradle` CLI 构建；见 `.github/workflows/build-apk.yml`。
> **本地工具链**：JDK 17、Android SDK（platform-tools + android-35 + build-tools 35）、Gradle 8.9。
> 在 `android/local.properties` 写入 `sdk.dir=<你的 Android SDK 路径>`（该文件已被 `.gitignore` 排除）。
>
> 已实测：`gradle :app:assembleDebug` **BUILD SUCCESSFUL（6m33s）**，产出
> `app/build/outputs/apk/debug/app-debug.apk`（1.3 MB）；`assembleRelease` 也能产出未签名包。
> 签名 Release APK 仍由 GitHub Actions 在 tag `v*` 时完成。

## 目录

```
android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties      # 仅版本锁定
└── app/src/main/
    ├── AndroidManifest.xml                       # BIND_WALLPAPER + meta-data
    ├── assets/
    │   ├── shader/holo.vert / holo.frag          # 由 tools/sync_shader.py 同步生成
    │   └── demo/                                 # 5 层素材 + card-config.json
    ├── kotlin/com/example/holowallpaper/
    │   ├── MainActivity.kt                       # 安装 demo Profile + 跳系统壁纸设置
    │   ├── model/CardConfig.kt                   # 参数解析，与 web/src/config.js 对齐
    │   └── wallpaper/
    │       ├── HoloWallpaperService.kt           # Engine + Choreographer + 生命周期
    │       ├── EglRenderer.kt                    # EGL14 + GLES20 + 纹理 + uniforms
    │       ├── AttitudeSource.kt                 # 旋转矢量 -> 相对四元数 -> uView
    │       └── ProfileStore.kt                   # 私有目录 Profile 安装/读取
    └── res/{values,xml,drawable}/
```

## 关键实现

- **EGL14 六步**：`eglGetDisplay → eglInitialize → eglChooseConfig(RGBA8888, GLES2) →
  eglCreateContext(CLIENT_VERSION=2) → eglCreateWindowSurface(holder.surface) → eglMakeCurrent`；
  每帧 `glDrawArrays` + `eglSwapBuffers`。`onSurfaceChanged` 只重建 window surface，
  program / 纹理 / context 全部复用；`onSurfaceDestroyed` 完整释放。
- **GLSL 单一源**：`python ../tools/sync_shader.py` 从 `web/src/holo-shader.js` 提取
  `VERTEX_SHADER` / `FRAGMENT_SHADER` 写入 `assets/shader/`，GLSL ES 1.00 由 GLES2 直接编译。
  同步脚本会拦截 `half` 保留字等已知编译陷阱。
- **传感器链路**：`TYPE_GAME_ROTATION_VECTOR`（无需权限、不受磁干扰）→ 相对四元数校准 →
  共轭旋转 `[0,0,1]` → `1-exp(-dt/0.11)` 阻尼 → `normalize(tx,ty,1)` 作为 `uView`；
  长时间无传感器数据时用极缓慢巡游兜底，避免壁纸「死掉」。
- **省电**：`onVisibilityChanged(false)` 注销传感器并停 Choreographer；静止 ~12fps、
  预览态 ~30fps、跟随时跟 vsync；`isPreview()` 时不联网、不弹权限。
- **离线**：`ProfileStore` 首次启动把 `assets/demo/` 原子安装到
  `files/profiles/demo/`，之后壁纸服务完全不依赖网络，App 被杀也能恢复。
- **M1 取舍**：为与第一阶段/官方演示视觉完全一致，先按原契约加载 **5 张纹理**；
  方案里「减到 3 张 + GLSL 求导」的优化留到 M2 与端上拆层一起做。

## 真机验证清单

1. `gradle :app:assembleDebug` 安装 APK，打开 App，确认提示「已安装内置演示 Profile」。
2. 点「设为动态壁纸」→ 系统预览页选中本应用 → 应用。
3. 回到主屏，左右 / 前后倾斜手机：镭射高光与分层重影应随视角移动，方向不倒转。
4. 静置 5 分钟：无异常发热，帧率降到 ~12fps；倾斜后立即恢复跟手。
5. 息屏 / 切到其它应用：传感器与渲染停止；返回后自动恢复。
6. 在系统「电池优化」里把应用加入白名单（厂商策略可能冻结壁纸服务）。

## 已知边界

- 多数 ROM 的动态壁纸只作用于**主屏**，锁屏通常是静态帧或模糊图。
- `MainActivity` 是 M1 的最小入口 UI；`app-ui/` 的完整界面与模型配置层在 M2 接入。
- `INTERNET` 权限用于 M2 用户自配模型的生成请求；壁纸服务本身永不联网。

## M2：生成器与 WebView 桥接

**选型**：不重写 Compose，直接把已验证的 `app-ui/index.html` 放进 WebView。界面、动效与
多尺寸适配已 9/9 通过，且能继续保持**零外部依赖**（无 AndroidX / OkHttp / 协程）。

| 模块 | 职责 |
|---|---|
| `security/KeyVault.kt` | AndroidKeystore AES-256-GCM 加密模型 API Key |
| `model/ModelProvider.kt` | 用户自配文本/视觉 Chat 协议、旧配置迁移标记、脱敏视图 `redacted()` |
| `model/ProviderRegistry.kt` | `providers.json` 原子写入 `files/`，绝不存明文 Key |
| `model/ImageGenerationClient.kt`（类名 `CardDesignClient`） | 仅 `/chat/completions`：视觉协议发送照片 data URI + 描述，文本协议仅发送描述；不请求图片输出、无图片下载；`probe()` 只探活 `/models` |
| `model/CardDesign.kt` | 严格设计 JSON 契约：字段白名单、文字长度、颜色格式、枚举和数值范围；拒绝 URL、资源路径及未知字段 |
| `generator/LayerBuilder.kt` | 端上把一张图拆成 5 层 Bitmap：背景（降采样模糊+暗化）、主体（圆角真 alpha）、文字（Canvas 绘制）、线稿（256×384 边缘检测后放大）、特效（星点） |
| `generator/ProfileWriter.kt` | 临时目录 + rename 原子写，`card-config.json` 与 `meta.json` |
| `bridge/NativeBridge.kt` | 10 个 `@JavascriptInterface` 方法；耗时操作在后台线程 |
| `MainActivity.kt` | WebView 加载 `file:///android_asset/app-ui/index.html`，注入 `HoloAndroid`，处理照片选择与返回键 |

**桥契约**：前端用 `Native.json(name, arg)` 调用；结果通过
`window.__onNative.{onGenerateResult, onTestResult, onPhotoPicked}` 回调。
浏览器里没有注入对象时自动回退到演示模式，同一份前端可在 PWA / 桌面预览复用。

**唯一创作入口**：上传照片并填写描述，选择用户自配文本/视觉 Chat 模型。设计 JSON 中的标题、副标题、配色、字号、对齐、边框、装饰及 RuiC 参数分别影响本地五层图像和 `card-config.json`；任何请求或校验失败均明确报错，不复制演示结果。

**Profile 热切换**：App 里点收藏卡调用 `setActiveProfile`，壁纸服务每 60 帧检查
`active_profile_id`，变化时 `EglRenderer.loadProfile` 重载（先释放旧纹理，避免 GPU 内存泄漏）。

**同步与校验**：

```bash
python tools/build_app_ui.py     # app-ui/index.html -> assets/app-ui/index.html（按原始字节复制）
python tools/verify_android.py   # 11 项静态校验，含桥方法名/回调一致性、密钥安全、CI 路径
```

## 验证动态效果是否真的在播放

壁纸「看起来不动」通常不是渲染停摆，而是驱动视角的值没变。两步确认：

**1. 心跳日志（约每秒一条）**

```bash
adb logcat -s HoloWallpaper | grep frame=
# frame=2401 elapsed=20.1s moving=true view=(0.27,-0.13) renderer=true
```

判据：`elapsed` 持续增长、`view` 在两个方向上明显摆动（正常约 ±0.3）、`moving=true`。若 `view` 长期固定在 (0.0x,0.0x)，说明巡游没启动。

**2. 截图比对（客观、不依赖人眼）**

```bash
adb shell screencap -p /sdcard/a1.png && adb pull /sdcard/a1.png a1.png
sleep 2
adb shell screencap -p /sdcard/a2.png && adb pull /sdcard/a2.png a2.png
python android/tests/check_motion.py a1.png a2.png
# 变化像素 548251 / 2592000 = 21.15%   判定: 画面在动 ✓
```

> 实现要点：自动巡游必须**常驻叠加**在传感器姿态上。手机静置时传感器仍会持续上报几乎不变的值，
> 若用「收到传感器事件」来决定何时启动巡游，巡游永远抢不到控制权，`view` 会钉在一个微小偏移上。

## 已知系统行为：部署会重置壁纸

`adb install -r`（覆盖安装）或 `am force-stop` 都会让系统认为壁纸服务失效，日志里是：

```
WallpaperManagerService: Wallpaper service gone: ...HoloWallpaperService
WallpaperManagerService: Wallpaper uninstalled, removing: ...HoloWallpaperService
```

随后 `mWallpaperComponent` 回退到 `com.miui.miwallpaper/...ImageWallpaper`，桌面变成默认壁纸（视觉上是黑的）。**这不是渲染 bug**，每次部署后需要重新设置一次动态壁纸。

排查渲染时不要部署，直接抓日志：

```bash
adb logcat -s HoloGL HoloWallpaper
```

正常应看到 `onSurfaceChanged 1080x2400` → `tex subject.png 1024x1536 id=.. err=0` → `loadProfile done textures=5` → `GL ready` → `first frame ... glError=0`；若出现 `tex ... MISSING -> placeholder`，壁纸会显示品红/暗紫棋盘，说明纹理路径有问题。

## M2.1：无 adb 时的三个问题排查

没有数据线、连不上 `adb` 时，**App 内「我的 → 诊断信息 → 刷新」**是最快的自查入口：
返回桥版本、`activeProfile`、Profile 目录列表、Provider 数量、SDK 版本、`filesDir`。
若桥本身不可用，会显示"浏览器演示模式（无原生桥）"及当前 `photoPath` / `activeProvider`。

### 1. 点「上传照片」没反应

| 可能原因 | 现象判据 | 处理 |
|---|---|---|
| WebView 没接管 `<input type="file">` | 连系统选择器都不弹 | 必须实现 `WebChromeClient.onShowFileChooser`（本次已加） |
| 原来走 JS 桥 `pickPhoto`，桥不可用时**静默失败** | 无 toast、无崩溃、完全没反应 | 改为标准 file input 路径，桥只作备用 |
| `<label>` 包 `hidden input`，隐式转发被 `preventDefault` 掉 | 有 toast 仍不弹 | 改成 `div` + `$("file").click()` |
| `startActivityForResult` 抛 `ActivityNotFound` | App 闪退 | 已 try/catch 并把失败原因回传前端 |
| 选完图但 `content://` 不能直接给 BitmapFactory | 生成时报"没有拿到照片" | `onActivityResult` 里复制到 `cacheDir`，回调绝对路径 |

排查顺序：点一次虚线框 → 有没有系统选择器 → 有没有 toast → 有没有闪退 →
再看「我的 → 诊断信息」。

### 2. 生成结果总是 demo 素材

根因：`entry=photo` 时前端 `state.photoPath` 为空（问题 1 的连锁），后端原来**静默降级**
到 `ProfileWriter.copyDemo()`，所以永远看到 demo。

M2.1 起：拿不到照片直接失败并提示"没有拿到照片（路径为空或文件不可读）"，**不再静默降级**；
生成结果新增 `source` 字段（`photo` / `model` / `demo`），前端 toast 会显示真实来源。

自查：①「已选择…」文案是否变成"正在读取路径…"后又被 `onPhotoPicked` 覆盖；
②「我的」里 Profile 数量是否增加、最新一条是否出现；③ 生成 toast 的"来源"是哪一个。

### 3. 如何指定模型生成

1. 「模型」页填写名称、文本/视觉 Chat 协议、Base URL、模型 ID、API Key。
2. 保存并选中模型；旧配置会标注「需迁移」，必须重新选择协议保存，密钥留空保留已有密文。
3. 「创作」页上传照片、填写卡片设计描述，点击生成。

Base URL 通常填到 `/v1` 根路径，创作请求只拼接 `/chat/completions`。视觉协议发送压缩照片 data URI 和描述，文本协议仅发送描述。模型须返回完整且受限的 JSON 设计；不接受图片、链接、任意资源路径或额外配置。未选择模型时明确提示配置，不启动替代生成。
