# HyperChanger 个人修改版：iOS 通知中心丝滑下拉、清除防卡顿与 iOS 18 原位壁纸揭示优化说明

> **版本性质声明**：本分支及附带安装包为基于上游开源项目 [ColdP/HyperChanger](https://github.com/ColdP/HyperChanger) 与个人分支 `codex/os404-dock-reveal`（commit `7205616`）制作的**个人修改与测试构建（Personal Fork Build）**，使用个人签名证书签发，仅发布于个人 Fork 仓库 `Hollowden/HyperChanger`，未向上游提交或冒充官方版本。

---

## 一、本次修改总览

围绕 HyperOS 4 / Android 17（实测设备：Xiaomi 17 Pro Max，120 Hz）上的「iOS 通知中心」模块，本次改动共完成三项核心修复与体验升级：

1. **清除通知自动上滑收起防卡顿**：修复点击自定义「× 清除」后系统自动收起通知栏时的掉帧与首帧卡片塌缩。
2. **下拉通知栏 120 FPS 性能重构**：通过 `dumpsys gfxinfo`、`atrace` 与 `simpleperf` 实机火焰图剖析，彻底移除热路径上的高频反射异常堆栈与 5 个前景图层的每帧大半径动态 `RenderEffect` 离屏模糊，将起拉首帧阻塞耗时降低 **92%**（`332 ms → 25.88 ms`）。
3. **iOS 18 风格「壁纸原位裁切揭示」与交互缺陷修复**：基于 iOS 18 实机逐帧参考，在关闭“分阶段壁纸渐显”（壁纸随下滑）模式时实现壁纸原位固定、水平平直分界线逐步“吃掉”清晰桌面的过渡动效，并修复下拉中途回拉冻住闪现、通知卡片下拉透出桌面光等交互问题。
4. **修复桌面原生线程 `hc-folder` 后台死循环高功耗 Bug（上游原版问题）**：在桌面版本升级至 `8.01.02.9259` 且开启原生 Dock / 全玻璃特性时，上游原版 `folder_spacing.cpp` 会反复解析数千行 `/proc/self/maps` 并全量扫描 `libapp.so`，导致 `hc-folder` 常驻消耗 ~22% 单核 CPU。修复后稳态 CPU 占用由 **22.0%（44 ticks / 2s）直接降至 0.0%（0 ticks / 5s）**。

---

## 二、修改前 vs 修改后详细对比

### 1. 性能剖析与核心卡顿修复

#### 修改前问题与实机剖析数据
- **帧率体感一卡一卡**：下拉手势区间 `gfxinfo` 实测仅 **~29–31 FPS**（帧间隔 24.6–57.9 ms），GPU 单帧耗时高达 **14–20.5 ms**，`IssueDrawCommands` 占 **7–15.5 ms**。
- **主线程起拉与拖动大卡顿**：`atrace` 捕捉到 `MotionEvent MOVE` 起拉首帧阻塞 **332 ms**，拖动过程中连续 6 帧 `traversal` 耗时达 **57–78 ms**。
- **火焰图根因（`simpleperf`）**：
  - `art::Throwable_nativeFillInStackTrace` 单独消耗主线程 CPU **11.05%**（源自 `IosNotificationStackHooks.isFocusOrMediaNotification` 与 `IosShadeMaterial.captureGroupFields` 在每一帧/每次布局中反复用会抛异常的 `Xp.getObjectField` / `Xp.callMethod` 试探不存在的字段与方法，吞掉海量 `IllegalArgumentException`）。
  - `IosNotificationCenterPresentation.sync()` 每帧对 **5 个前景图层**（时钟容器 `host`、通知列表 `stack`、状态栏镜像 `status`、自定义通知标题栏 `notificationHeaderBar`、底部快捷按钮 `IosNotificationShortcutHost`）以及壁纸图层动态重建最高 **84 px（28 dp）** 的 `RenderEffect.createBlurEffect`，导致 RenderThread 与 GPU 每帧重绘全屏离屏模糊缓存。
  - `syncStatus()` 与 `syncDepth()` 在每一帧 `onPreDraw` 都对整个 `NotificationShadeWindowView` 执行全树 `findViewById`。

#### 修改后方案与实测效果
- **零异常反射探测（`Xp.java`、`IosNotificationStackHooks.java`、`IosShadeMaterial.java`）**：
  - 在 `Xp` 中新增 `tryGetObjectField(Object, String)` 与 `tryCallNoArgMethod(Object, String)`，直接复用 `ClassValue<ConcurrentHashMap>` 缓存表，查不到时直接返回 `null` 而不抛异常。
  - 重写 `IosNotificationStackHooks.isFocusOrMediaNotification()`，复用单次 `getInjector()` 结果并全链路改用零异常反射。
  - 在 `IosShadeMaterial` 中新增 `SCANNED_OWNERS`（`WeakHashMap`）对象去重与 `OnGlobalLayoutListener` 60 ms 轻量防抖，改用 `Xp.tryGetObjectField` 扫描 12 个通知组控制字段。
- **消除前景动态离屏模糊与每帧全树遍历（`IosNotificationCenterPresentation.java`、`IosNotificationShortcutHost.java`、`IosShadeWallpaperReveal.java`）**：
  - 移除前景 5 个图层（`host`、`stack`、`status`、`notificationHeaderBar`、`IosNotificationShortcutHost`）以及竖屏非分阶段壁纸的动态 `RenderEffect` 高斯模糊。
  - `syncStatus()` 在 `status.source` 已附加且宽高有效时直接复用；`syncDepth()` 在拖动期已缓存景深图时跳过全树 `findViewById`。
  - 分阶段壁纸渐显模式（`IosShadeWallpaperReveal`）竖屏峰值模糊由 `28 dp` 降至 `10 dp`、两端极值（`<= 0.02` / `>= 0.94`）归零、量化步长由 `2 px` 放宽至 `4 px`。
- **优化后实测数据**：起拉首帧 `MotionEvent MOVE` 耗时由 **332 ms 降至 25.88 ms（下降 92%）**，拖动期间连续 57–78 ms 的 `traversal` 大卡顿完全消失，下拉全程丝滑跟手。

---

### 2. 清除通知自动收起防卡顿

#### 修改前
- 点击通知中心右上角「× 清除」后，系统会触发非手势收起流程；在 `settle` 动画启动首帧，由于起点仍为 `1.0f`，`IosNotificationExitState.updateProgress(1.0f, 1.0f)` 会将 `holding` 重置为 `false`，同时系统原生 `startPanelVisibleAnimation` / `startPanelExpandAnimation$default` 先触发一帧原生卡片塌缩，引起明显顿挫。

#### 修改后
- 新增 `IosNotificationCenterPresentation.prepareCollapse()` 与 `IosNotificationExitState.prepareCollapse()`。
- 在 `IosNotificationAnimationHooks` 拦截到 `startPanelVisibleAnimation(false, ...)` 与 `startPanelExpandAnimation$default(..., false, ...)` 时，提前清理入场状态并冻结已绘制卡片。
- 在 `ShadeLayer.onRawExpansion()` 创建 `1.0f -> <1.0f` 的收起 `ValueAnimator` 时，先调用 `prepareCollapse()` 并将动画起点微调为 `0.999f`，防止首帧清零冻结态。

---

### 3. iOS 18 风格「壁纸原位裁切揭示」与交互缺陷修复

当用户在设置中关闭「分阶段壁纸渐显」（即启用“壁纸随下滑”模式，`!ShadeLayer.iosStagedExpansion()`）时：

| 维度 | 修改前 | 修改后（iOS 18 原位揭示风格） |
| :--- | :--- | :--- |
| **壁纸运动方式** | 整张壁纸 `sWp` 跟随幕布容器 `sFrame` 一起从屏幕上方整体平移滑入，并伴随大半径模糊与渐显 | 幕布容器 `sFrame` 随手指下拉提供裁切窗口，壁纸 `sWp` 设置反向位移 `translationY = -offset` **固定在屏幕原位**，从顶部到底部逐步被分界线裁切揭示（“一点点把底层桌面吃掉”） |
| **下拉分界线形状** | 全程带屏幕大圆角裁切，中途观感偏“悬浮圆角卡片” | 下拉中途（`progress < 0.96`）采用贴满屏幕宽度的**平直水平分界线**（圆角 `0`），贴近底边（`0.96 -> 1.0`）线性平滑过渡回屏幕物理圆角 |
| **底层桌面模糊对称性** | 下拉时有系统模糊、上滑退出时桌面一开始就清晰，正反方向割裂 | 通过 `IosShadeBackdropBlur` 在壁纸原位揭示模式下将底层 HyperOS 全屏窗口模糊压制为 `0`，**下拉与上滑底层桌面均保持 100% 清晰对称**；水平切换至控制中心（`switching`）时自动释放还原控制中心原生模糊 |
| **前景与景深过渡** | 壁纸与前景全程按 smoothstep 半透明渐显，上滑穿帮；景深人像中途与原位壁纸错位 | 新增 `visualAlpha()`：顶边前 5% 极速柔顺淡入，随后保持 **100% 实心实体画卷**，切控制中心随 `switchFraction` 淡出；景深压字人像层 `depth` 在最后 20%（`0.80 -> 1.0`）时钟落位时丝滑淡入 |
| **中途反向回拉冻住闪现** | 下拉中途稍往上回拉一次，`IosNotificationExitState` 立刻置 `holding = true` 且下推不解冻，导致所有组件卡在错误位置直到拉满松手瞬间闪现 | 在 `IosNotificationExitState` 中新增 `wasExpanded` 标记：**仅在真正完全展开（`>= 1.0f`）过后**再上滑收起时才冻结组件，彻底消除下拉中途反复推拉引起的冻住与闪现 |
| **通知卡片透出桌面光** | 下拉首帧通知卡片局部模糊材质尚未捕获，卡片透出底层桌面亮斑，拉满瞬间「夸嚓」跳变为通知壁纸模糊 | 取消拖动期屏蔽 `IosShadeMaterial` 扫描，改为 60 ms 轻量防抖，并在 `IosNotificationCenterPresentation.update()` 起拉首帧及每 60 ms 主动局部扫描通知栈 `IosShadeMaterial.scanSubtree(stack)` |

---

### 4. 桌面原生线程 `hc-folder` 高功耗死循环 Bug 修复（上游原版代码溯源）

#### 来源溯源
- 经核查 `git log` 与 `git diff upstream/main..HEAD -- app/src/main/cpp/targets/home/folder_spacing.cpp`，该文件由上游 `ColdP/HyperChanger` 在 commit `97a4bd2` 原版提交，**并非个人 Fork / Codex 分支改动引入**。

#### 修改前问题与实机剖析数据
- 当用户开启原生 Dock（`HNDK`）或全玻璃（`HGLS`）等桌面原生特性，且系统桌面已更新至上游尚未完全适配的版本（实测设备：`com.miui.home` `RELEASE-8.01.02.9259`，`/proc/pid/maps` 达 5,079 行）时：
  1. `worker()` 每 0.5 秒无条件调用 `inventory()` 重新读取并逐行 `sscanf` 解析全部 5,079 行 `/proc/self/maps`；
  2. 由于 `Native Dock 8583/8593` 布局校验失败且 4 个扩展材质 slot 无法凑满，`update_effects()` 始终判定 `need_native == true` / `need_full == true`，每 5 秒将 `libapp.so` 几十 MB 可执行代码段完整拷贝并暴力扫描 16 套 ARM64 特征码，永不退避；
  3. 实机 `simpleperf` 热点直接命中 `__svfscanf`、`seq_put_hex_ll`、`show_map` 与 `base.apk` 扫描循环，`hc-folder` 线程静置时持续占用 **22.0% 单核 CPU（44 ticks / 2s）**，累计白白烧掉 **5,625 秒（约 1.56 小时）纯单核满载时间**。

#### 修改后方案与实测效果（`app/src/main/cpp/targets/home/folder_spacing.cpp`）
- **运行期镜像指纹去重**：引入 `generation_fingerprint()`（对映射区段 `begin/end/file_offset/inode/dev` 做 FNV-1a 哈希）以及 `scanned_effects_mask` / `scanned_folder_gens` / `scanned_multi_split_gens` 缓存。同一不可变代码镜像在某特性位完成过完整扫描后不再反复全量扫内存；若用户开启新开关或链接器加载新 `libapp.so`，立即自动重新扫描。
- **配置文件 `stat()` 变更检测 + 稳态退避**：循环优先执行微秒级 `stat(hyperpatch.bin)` 检查 `(size, mtime)`，配置变化时 500 ms 内即时生效；进入稳态后仅每 40 个周期（20 秒）做一次低频 COW 护卫检查，其余周期完全不打开 `/proc/self/maps`；取消匹配失败时的无限 50 ms 狂刷循环。
- **实机复测结果**：重新编译 16 KB 页对齐的 `libhyperchanger_folder.so` 并安装重启桌面后，已适配的 `Native Dock capability gate` 正常生效，**`hc-folder` 稳态 CPU 占用从 `44 ticks / 2s (22.0%)` 降至 `0 ticks / 5s (0.00%)`**。

---

## 三、修改文件清单与混淆类对照表

| 源码文件 | 对应发布包产物 / 混淆类 | 主要修改内容 |
| :--- | :--- | :--- |
| `app/src/main/cpp/targets/home/folder_spacing.cpp` | `lib/arm64-v8a/libhyperchanger_folder.so` | 新增运行期镜像指纹扫描去重、`hyperpatch.bin` 的 `stat()` 变更检测与稳态退避，消除 `hc-folder` 22% CPU 死循环 |
| `hypermusiccover/Xp.java` | `h45` | 新增零异常反射 `tryGetObjectField` / `tryCallNoArgMethod` |
| `hypermusiccover/ShadeLayer.java` | `n84` / `n00` / `zo1` | 壁纸原位反向位移裁切揭示、平直分界线轮廓插值、移除竖屏壁纸 84 px 模糊、接入 `IosShadeBackdropBlur` 清零底层模糊、收起动画 `0.999f` 防首帧解冻 |
| `newnotificationcenter/IosNotificationCenterPresentation.java` | `kp1` | 移除前景 3 图层与标题栏动态模糊、缓存状态栏/景深查找、`visualAlpha()`、`prepareCollapse()`、景深尾段 20% 淡入、起拉首帧与 60 ms 通知栈材质同步 |
| `newnotificationcenter/IosNotificationShortcutHost.java` | `wp1` | 移除底部快捷按钮每帧 84 px 动态模糊并同步 `visualAlpha()` |
| `newnotificationcenter/IosNotificationStackHooks.java` | `dq1` | 零异常重写 `isFocusOrMediaNotification` 并复用 `injector` |
| `newnotificationcenter/IosShadeMaterial.java` | `rq1` / `cp1` | `SCANNED_OWNERS` 去重、零异常字段扫描、`OnGlobalLayoutListener` 60 ms 防抖、新增 `scanSubtree(View)` |
| `newnotificationcenter/IosNotificationExitState.java` | `pp1` | 新增 `wasExpanded` 修复下拉中途回拉冻住闪现，新增 `prepareCollapse()` |
| `newnotificationcenter/IosNotificationAnimationHooks.java` | `ln0` / `q81` | 拦截 `startPanelVisibleAnimation` / `startPanelExpandAnimation$default` 收起时机提前调用 `prepareCollapse()` |
| `newnotificationcenter/IosShadeBackdropBlur.java` | `eq1` | 在 `!iosStagedExpansion()` 模式下恒定保持底层窗口模糊为 `0f`，释放时完整还原 |
| `newnotificationcenter/IosShadeWallpaperReveal.java` | `vq1` | 分阶段模式峰值模糊降至 `10 dp`、两端清零、步长 `4 px` |

---

## 四、实机验证环境与结果

- **测试机型**：Xiaomi 17 Pro Max（HyperOS 4 / Android 17，120 Hz）
- **实机验证项（已全部通过）**：
  - 点击「× 清除」后自动收起通知中心：无掉帧、无首帧卡片塌缩；
  - 桌面直接下拉通知栏 & 先下拉控制中心再左滑切通知栏：时钟位置、状态栏镜像、通知卡片位置均正常，右滑切回控制中心时钟正常消失；
  - 缓慢下拉并中途反复上下推拉：组件全程跟随流畅、不冻住、松手无闪现；
  - 通知卡片模糊背景：起拉首帧即对通知中心壁纸渲染，无透桌面光与拉满跳变；
  - 壁纸视觉与底层桌面：壁纸原位从上到下平直水平分界线裁切揭示、贴底恢复屏幕圆角，下拉与上滑底层桌面均 100% 清晰对称。
