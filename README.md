# 清屏 QingPing

一款**本地运行**的 Android 开屏广告跳过、误触跳转保护与按应用统计工具。

- 包名：`com.qingping.clearscreen`
- 版本：3.0.0（versionCode 11，测试版）
- 下载：[release/qingping-3.0.0-test.apk](release/qingping-3.0.0-test.apk)
- minSdk 26 / targetSdk 34（可安装在 Android 8.0–16）
- 无 `INTERNET` 权限，不上传任何数据
- 统计保存在本机 SQLite：`qingping_stats.db`

---

## 一、先看结论：哪些能做，哪些不能做

| 能力 | 状态 | 说明 |
|---|---|---|
| 自动点击开屏广告的“跳过 / 关闭广告” | ✅ 可做，尽力而为 | 通过无障碍节点文字、位置、尺寸、可点击性综合判断 |
| 按 APP 统计拦截次数 | ✅ 可做 | 记录来源包名、应用名、类型、时间；支持“今天 / 全部” |
| 误触跳转后自动返回 | ✅ 可做，事后返回 | 检测到 1.6 秒内跳到浏览器/应用商店后执行返回 |
| 开机后自动恢复 | ✅ 基本可做 | 无障碍服务由系统恢复；`BOOT_COMPLETED` 再拉起前台服务 |
| 清理后台后继续工作 | ⚠️ 取决于厂商 | 前台服务 + 无障碍绑定 + 电池白名单，realme 仍需手动放行 |
| 点击前全局拦截触摸 | ❌ 普通应用做不到 | Android 不向第三方应用开放全局触摸拦截 |
| 识别 Canvas / SurfaceView / 视频广告 | ❌ 不可靠 | 这类广告没有可读的“跳过”节点 |
| 识别同一 APP 内 WebView/Custom Tab 的跳转 | ❌ 不可靠 | 前台包名没有变化，不能判定为“跳到外部应用” |
| 去除广告内容 / 变成会员 | ❌ 不是这个工具的目标 | 只点“跳过”或误跳后返回，不修改目标 APP |

> 结论：**“开屏跳过”是自动点击，不是网络层去广告；“误触保护”是跳转后自动返回，不是点击前拦截。** 这是 Android 权限模型决定的。

### 3.0.0：双模式 + 无障碍快速路径

用户可以在“权限与自启动”里用 **Root 模式** 开关自由切换：

- 关闭 = **纯无障碍模式**：不执行任何 Root 命令；
- 开启 = **Root 模式**：自动识别控件后直接用 `su -c input tap`，最快。

无障碍模式的快速路径：

1. 开屏约 160ms 首次识别；
2. 直接发真实触摸手势；
3. 380ms 验证；
4. 仍在则用 `ACTION_CLICK`，300ms 验证；
5. 仍在则重新识别并发第二次手势；
6. 仍失败就结束，不乱点、不退出、不计数。

Root 模式的快速路径：

1. 开屏约 120ms 首次识别；
2. 直接 Root 点击；
3. 300ms 第一次验证，之后每 250ms 复查；
4. 800ms 仍未关闭才重试，最多 4 次。

重要限制：**大麦这个广告会接受无障碍 `ACTION_CLICK`/手势但忽略它**。日志里 `action=true`、`gesture completed` 后仍然是 `SplashMainActivity`；只有 Root `input tap` 能让它进入首页。因此：

- 无障碍模式对其他广告可以很快，但在大麦上可能无法跳过；
- 要稳定跳过这个大麦广告，需要使用 Root 模式。

### 2.2.0：可选 Root 点击兜底

v2.1.1 的日志显示：大麦开屏没有识别到候选，因为唯一的右上角控件 `homepage_advert_pb` 被当成进度条过滤；而即使用它，普通无障碍点击也没有让广告关闭。

2.2.0 增加一个**默认关闭**的 Root 兜底：

- 仍然自动识别控件 bounds，不使用固定坐标；
- 无障碍 `ACTION_CLICK` 和手势都无效后，才用 `su -c input tap x y` 注入真实点击；
- 首次使用会请求 Root 授权（KernelSU/Magisk）；
- 如果 Root 被拒绝或命令失败，只记录日志，不计数、不退出、不乱点。

开启位置：清屏 → 权限与自启动 → **Root 点击兜底 · 实验**。如果你的设备没有 Root，保持关闭即可。

### 2.1.0：修复“自动退出、主界面乱点”

2.0.0 的日志暴露了一个严重问题：只要前台包名是 `cn.damai` 就开启 15 秒会话，导致大麦主界面的右上角菜单也被当成“跳过”；返回键兜底还把大麦退到了设置页。2.1.0 修复：

- 只有 `SplashActivity / AdActivity / 小节点树广告窗口` 才能开启会话；
- 进入 `MainActivity / homepage / channel / search` 等主界面后立即结束会话；
- 右上角图片按钮兜底只在“开屏上下文”中生效，主界面绝不扫描；
- **彻底移除返回键兜底**，不再出现“大麦被关掉/跳到设置”；
- 点击失败就结束本次会话，宁可漏跳，不乱点。

### 2.0.0：自动识别、窗口扫描、无固定坐标

参考了 GitHub 上的这些实现：

- [Android-Touch-Helper](https://github.com/zfdang/Android-Touch-Helper)：关键词、控件规则、位置规则；第一次 `ACTION_CLICK`，下一次发现控件还在才用真实手势。
- [SKIP](https://github.com/GuoXiCheng/SKIP)：无障碍 + 按应用配置规则。
- [ad-skipper](https://github.com/madeye/ad-skipper)：L1 节点 → L2 ML Kit OCR → L3 YOLO/VLM；用 `AccessibilityService.takeScreenshot()` 做无 MediaProjection 的截图检测，按 750ms 轮询并重试。
- [LiteSkip](https://github.com/stone1524/LiteSkip)：轻量无障碍跳过。

2.0.0 的改动：

- **删除大麦固定坐标兜底**，不再使用 `93.2% / 7.1%` 这类硬编码位置；
- 用 `getWindows()` 扫描该应用的所有交互窗口，而不只看 `getRootInActiveWindow()`；
- 每次点击前重新获取节点，避免使用已经失效/被替换的节点；
- 第一次用 `ACTION_CLICK`，如果广告还在，下一次用**新节点**的 bounds 发真实触摸；
- 如果点击把广告打开了落地页，自动返回，不计数；
- 保留 `GestureResultCallback` 日志，能区分“手势完成”和“手势被系统取消”。

### 1.2.1：针对“点击已派发但广告没关”

诊断日志显示大麦的开屏是一个 `FrameLayout` 窗口，右上角跳过控件是 `android.view.View`（约 `1130,140–1219,229`）。1.2.1 增加：

- 触摸手势无效时，对该节点再执行一次无障碍 `ACTION_CLICK`；
- 窗口类名是 `FrameLayout` 时，也允许根据前台 `SplashMainActivity` 走坐标兜底；
- 最后尝试一次系统返回键，只有回到大麦首页才计数；
- 记录节点 id、父控件、点击结果和点击后的前台 Activity。

### 1.2.0：先验证再计数

1.1.0 暴露出一个问题：系统返回“点击已执行”，但广告并没有关闭，统计却增加了。1.2.0 改为：

- 点击后等待 700ms，检查开屏 Activity 是否真的离开；
- 只有进入大麦首页（`homepage.MainActivity`）或目标 APP 的正常主界面才计数；
- 如果文字节点点击无效，依次尝试右上角三个纵向比例 `7.1% / 11.2% / 15.2%`，覆盖全屏广告和上下留白的 9:16 广告；
- 如果点击把广告打开了落地页，不计数，也不会误报“已跳过”；
- 增加本机 `files/diag.log` 诊断日志，便于定位下一版问题。

### 1.1.0 针对大麦的兼容更新

大麦的开屏“跳过”有时是一个没有文字/描述的右上角图片按钮。1.1.0 增加了两层兜底：

1. 对 `cn.damai` 优先扫描右上角 20% 区域内的小型可点击控件；
2. 如果仍然拿不到节点，在开屏约 0.7–1.1 秒后点击右上角约 `(93.2%, 7.1%)` 的位置，并在 750ms 后确认开屏 Activity 已消失才记为一次拦截，避免把普通点击误记成成功。

如果坐标在你的大麦版本上偏了，可以在 `ClearScreenService.DAMAI_SKIP_Y` 里调整比例。

![大麦开屏：右上角为圆形“跳过”按钮](docs/damai-splash.jpg)

上图为实际抓到的 `cn.damai` 开屏：广告是全屏图片，“跳过”是右上角一个很小的圆形图片按钮，没有明显的按钮文字。这类控件正是 1.1.0 兜底逻辑针对的情况。

---

## 二、需要哪些权限，为什么

| 权限 / 组件 | 是否必需 | 用途与限制 |
|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE`（无障碍服务） | **必需** | 读取当前窗口的无障碍节点，找到“跳过”并执行点击；检测前台应用切换。必须由用户在系统设置中手动开启，应用无法静默授权 |
| `RECEIVE_BOOT_COMPLETED` | 建议 | 开机后拉起前台守护服务；无障碍服务本身由系统恢复 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 建议 | Android 8+ 的前台服务，降低被后台清理的概率；Android 14+ 使用 `specialUse` 类型 |
| `POST_NOTIFICATIONS` | 可选 | Android 13+ 显示常驻通知；拒绝也不影响无障碍拦截 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 可选 | 引导用户把清屏加入电池优化白名单 |
| `QUERY_ALL_PACKAGES` | 统计需要 | 读取应用名称和图标，用于按应用统计展示；不会读取应用数据 |
| `INTERNET` | **不声明** | 应用没有联网能力，统计只在本机 |

无障碍服务能做什么、不能做什么：

- 能读取当前活动窗口的**文本、contentDescription、控件边界、是否可点击**；
- 能对控件执行 `ACTION_CLICK`，或在坐标上派发一个无障碍手势；
- 不能拦截/取消用户已经发生的触摸；
- 不能读取其他应用的私有数据、密码输入框内容（系统会屏蔽密码节点）；
- 目标应用可以把广告绘制成不可访问的 Surface/Canvas，此时没有节点可点。

---

## 三、实现方案

### 1. 开屏广告跳过

`ClearScreenService` 监听三类事件：

- `TYPE_WINDOW_STATE_CHANGED`：判断前台应用，开启一个 **15 秒拦截会话**；
- `TYPE_WINDOW_CONTENT_CHANGED`：广告倒计时/跳过按钮出现时触发扫描；
- `TYPE_VIEW_CLICKED`：记录“最后一次点击发生在上一个 APP”，供误触判断。

每个会话中：

1. 用 `findAccessibilityNodeInfosByText` 搜索 `跳过`、`Skip`、`关闭广告`、`Close Ad`；
2. 过滤登录、注册、教程、引导等容易误点的文字；
3. 只接受**尺寸合理**的节点（宽度 < 屏宽 75%，高度 < 屏高 30%），避免把全屏广告容器当成按钮；
4. 综合“文字命中 + 位于屏幕上部/右侧 + 控件较小 + 可点击”打分，低于阈值不点；
5. 优先点击节点自身或最近的可点击父控件；没有可点击父控件时，才对高置信度的小按钮使用坐标手势兜底；
6. 每个前台会话最多成功点击一次，避免广告关闭后继续误点落地页。

### 2. 误触跳转保护

当发生以下组合时，判定为“误触跳转”：

- 上一个前台应用 A 是受保护应用；
- A 中刚刚发生过点击（1.6 秒内）；
- 前台切换到 B；
- B 是浏览器 / 应用商店（强信号），或 B 是购物类应用且刚才点击的控件带“广告/下载/立即购买”等广告词（弱信号）。

随后延迟约 260ms 执行一次 `GLOBAL_ACTION_BACK`，返回 A，并把这次拦截记到 **A 的统计**里。只返回一次，不连续连按，避免把用户正常浏览的页面退掉。

### 3. 按应用统计

`StatsDb` 使用 SQLite：

```sql
events(_id, package_name, label, kind, ts)
kind = 1  -- 开屏跳过
kind = 2  -- 误触返回
```

主界面支持：

- 今日 / 全部切换；
- 总拦截数、开屏跳过数、误触返回数、覆盖应用数；
- 按应用降序列表：图标、应用名、包名、次数、占比进度条、最近一次时间；
- 所有数据只在本机，不上传。

### 4. 开机与保活

- 无障碍服务一旦由用户开启，**系统会在重启后自动恢复**，这是最主要的“开机自启”来源；
- `BootReceiver` 监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`，启动 `GuardService` 前台服务；
- `GuardService` 显示低优先级常驻通知，进程被清理后 `START_STICKY` 尝试重建；
- 不申请 root，不使用 Shizuku，不修改系统设置。

---

## 四、安装与启用

### 方式 A：手机上直接安装

1. 从仓库下载：[release/qingping-3.0.0-test.apk](release/qingping-3.0.0-test.apk)，或自行构建；
2. 用“文件管理”打开 `Download/DSHA/qingping.apk`；
3. 允许“安装未知应用”，点击安装；
4. 打开“清屏”，点击“开启无障碍防护”；
5. 在系统无障碍列表中找到 **清屏 · 开屏广告拦截**，开启；
6. Android 13+ 如果开关是灰色：应用详情 → 右上角菜单 → **允许受限设置**，再回来开启；
7. 建议在“权限与自启动”里：允许通知、加入电池优化白名单、允许自启动、最近任务下拉锁定。

### 方式 B：有 ADB 的电脑

```bash
adb install -r release/qingping-3.0.0-test.apk
```

安装后仍需在手机上手动开启无障碍服务。

### 验证

打开“清屏” → “权限与自启动” → “运行一次拦截测试”。页面右上角有“跳过 5”，无障碍服务开启时会在约 1 秒内自动点击并返回。测试不写入统计，避免污染真实数据。

---

## 五、构建

当前工程使用纯 Android Framework（无 AndroidX、无 Kotlin），因此依赖很少。

```bash
cd qingping
./build.sh
```

默认依赖路径（可用环境变量覆盖）：

| 变量 | 默认值 |
|---|---|
| `ANDROID_JAR` | `$ANDROID_HOME/platforms/android-34/android.jar` |
| `AAPT2` | `PATH` 中的 `aapt2`，或 `$ANDROID_HOME/build-tools/34.0.0/aapt2` |
| `R8_JAR` | `.tools/r8.jar`，不存在时自动下载 |
| `JAVA_HOME` | 自动探测 JDK 17 |
| `KEYSTORE` | `./qingping.keystore`（已在 `.gitignore` 中，不会提交） |

构建流程：`aapt2 compile/link → javac → d8 → 打包 classes.dex → zipalign → apksigner`。

> 构建脚本在缺少密钥时会自动生成 `qingping.keystore`；它已被 `.gitignore` 排除，**不要提交任何签名密钥**。正式分发请通过 `KEYSTORE`、`KS_PASS`、`KEY_PASS` 环境变量提供自己的密钥。

---

## 六、源码结构

```text
qingping/
├── AndroidManifest.xml
├── build.sh
├── release/
│   └── qingping-3.0.0-test.apk   # 测试构建
├── docs/                         # 截图
├── LICENSE                       # MIT
├── PRIVACY.md
├── SECURITY.md
├── res/
│   ├── layout/activity_main.xml  # 仪表盘 + 按应用统计
│   ├── layout/activity_guide.xml # 权限与自启动向导
│   ├── layout/activity_test_ad.xml
│   ├── layout/item_app_stat.xml
│   ├── drawable/                 # 主题、卡片、图标
│   └── xml/accessibility_service_config.xml
└── src/com/qingping/clearscreen/
    ├── MainActivity.java         # 首页、统计列表
    ├── GuideActivity.java        # 权限向导、测试入口
    ├── TestAdActivity.java       # 本地模拟开屏广告
    ├── ClearScreenService.java   # 无障碍拦截核心
    ├── GuardService.java         # 前台保活通知
    ├── BootReceiver.java         # 开机 / 更新后恢复
    ├── StatsDb.java              # SQLite 统计
    ├── Prefs.java                # 开关偏好
    ├── PermissionUtils.java      # 权限状态与系统设置跳转
    └── RootInput.java            # 可选 Root input tap
```

---

## 七、可调参数（在 `ClearScreenService.java` 顶部）

| 参数 | 默认 | 作用 |
|---|---|---|
| `SESSION_MS` | 15000 | 每次前台切换后的扫描窗口 |
| `SCAN_INTERVAL_MS` | 320 | 扫描轮询间隔 |
| `CLICK_JUMP_WINDOW_MS` | 1600 | 点击后多久内的跳转算误触 |
| `JUMP_BACK_COOLDOWN_MS` | 3000 | 两次自动返回的最小间隔 |
| `IGNORED_PACKAGES` | 系统 UI / 设置 / 输入法等 | 不扫描的包名 |
| `findCandidate` 中的文字数组 | 跳过 / Skip / 关闭广告 | 可继续补充厂商文案 |

如果要减少误点，优先调高 `findCandidate` 的分数阈值（`110` / `96`），而不是扩大文字列表。

---

## 八、已知边界与风险

1. **不是 100% 准确**：广告控件可能没有文字、文字在图片里、或被故意设为不可访问；这类广告不会被跳过。
2. **可能误点**：某些 APP 的新手引导也有“跳过”，且位于右上角。当前用“15 秒窗口 + 尺寸/位置/分数阈值 + 排除登录教程字样”降低概率，但不能完全避免。
3. **误触保护可能误返回**：用户正常点击链接打开浏览器/商店时，也可能在 1.6 秒内被判定为误触。可在首页关闭“误触跳转保护”。
4. **同一应用内跳转无法识别**：Custom Tab、微信小程序、应用内 WebView 的落地页包名不变，不能可靠判断。
5. **厂商保活限制**：realme/OPPO 的“睡眠待机优化”“自启动管理”“一键清理”都可能结束后台；需要用户按向导放行。被“强行停止”后，开机广播也不会再收到，直到手动打开一次应用。
6. **无障碍服务被撤销**：用户或系统关闭无障碍后，拦截立即失效；历史统计仍在。
7. **Android 13+ 受限设置**：侧载应用的无障碍开关可能默认灰色，需要“允许受限设置”。
8. **应用商店政策**：Google Play 对无障碍 API 的使用有严格审核；本工程面向本地侧载/自用，不以 Play 上架为前提。

---

## 九、隐私与安全

详见 [PRIVACY.md](PRIVACY.md) 与 [SECURITY.md](SECURITY.md)。

- 无 `INTERNET` 权限，不上传统计或界面内容；
- 统计只保存在本机 SQLite；
- Root 模式默认关闭，开启后才会执行 `su -c input tap`；
- 诊断日志只在本机 `files/diag.log`。

## 十、参考项目

- [Android-Touch-Helper](https://github.com/zfdang/Android-Touch-Helper)
- [ad-skipper](https://github.com/madeye/ad-skipper)
- [SKIP](https://github.com/GuoXiCheng/SKIP)
- [LiteSkip](https://github.com/stone1524/LiteSkip)

## 十一、License

[MIT](LICENSE) © 2026 QingPing contributors

