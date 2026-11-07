# 网易云音乐 Kanade 风格底栏

2026-11-07，真机网易云音乐 9.6.21（9006021），Android API 37，HyperChanger 1.2.2 RC2 个人修改版。

`NeteaseKanadeModule.java` 为实际运行源码，独立 libxposed 102 入口。使用已移入 HyperChanger 的玻璃面板，并通过原生标签与播放器监听器执行动作。原生页面及播放器没有重写。首页保留首页、搜索、关注、我的四标签；默认在 Tab 上方显示长条播放器，向上滚动时收缩成右侧唱片，向下滚动或点击 Tab 时展开。歌单与专辑详情页的原迷你播放器也替换为玻璃胶囊。

- 颜色：HyperChanger 每应用手动强调色优先，其次尝试网易云主题属性，缺少有效强调色时回退网易云红。不是固定浅绿色。当前皮肤使用红色回退；不声称所有第三方皮肤都可自动识别。
- 主胶囊横滑选择标签；右侧唱片点按打开原 PlayerActivity，长按播放/暂停，左滑下一首、右滑上一首。
- 详情页播放器左滑下一首、右滑上一首；点按歌名进入播放页，右侧为正常的播放/暂停与列表按钮。
- 读取 MainProcessPlayService.isRealPlaying 的真实状态，250ms 缓存；通过 playNextMusic/playPreMusic 控制本应用。旧版 PlayService 类在此版本已不存在。
- 唱片以 RenderNode 保留封面，播放时旋转，暂停时保持角度；切歌有封面淡入、平移、缩放，以及歌名过渡。页面暂停时停止状态刷新，销毁时移除渲染监听器。
- 设置接入 HyperChanger 的每应用 enabled、accent_color、liquid_blur_radius、宽高缩放与偏移；非所有旧导航风格/标签选项均已映射。

## 弹簧、过渡与重新进入

检查手机 Kanade APK 后确认界面为 Flutter AOT，包内可定位 `package:kanade/ui/mini_player.dart`、`home_shell.dart` 与 `package:liquid_glass_widgets/utils/glass_spring.dart`。不能把编译后的 Dart 界面直接当作 Java 源码复制。Java `SpringValue` 使用阻尼弹簧解析解并保留重定向时的速度；参数参考该组件的 bouncy 500ms、bounce 0.3 预设，阻尼比 0.7。参考来源：[glass_spring.dart](https://github.com/sdegenaar/liquid_glass_widgets/blob/main/lib/utils/glass_spring.dart)，组件注明源自 Tim Lehmann 的 motor（MIT）。不是声称恢复了 Kanade 完整 Dart 源码或其确切定制参数。

长条宽度、位置与 Tab 宽度一起随物理弹簧变化；选中滑块的位移与挤压也随弹簧速度变化。窗口触摸观察器只观察内容区纵向手势，不消费原始事件，并在销毁时恢复原 Window.Callback。

播放页路由使用原生 PlayerActivity，关闭原侧飞动画，在 350ms 内淡出当前页面快照并显露原生播放页。小封面只作不超过 10% 的轻微脉冲，不再把低分辨率小图撑满屏幕。取消额外的唱片描边，并从原 MiniDiskContainer 裁去黑胶边；不直接使用透明的封面子控件。

封面刷新检测整个原生封面子树的 Drawable 身份与脏标记；每次恢复后 10 秒内额外检查迟到的异步加载。重新绑定替换过的原生迷你播放器，避免退出重进后缓存空白图。最终版已验证两次强制停止重开和回桌面后返回，封面持续正常。

`LiquidGlassHostLayout.java`、`LiquidGlassPanel.java` 是编译声明，不能打包。实际实现来自原 classes5.dex。D8 只输入 NeteaseKanadeModule*.class；生成 classes7.dex，保留原 classes2–6.dex 和所有 native 库、资源及 manifest。`patch_ui.py` 对本机确认过的主 DEX 增加网易云入口。`package.py` 校验保留项逐字节一致。

本次准备与构建目录：`D:\Codex Workplace\.inspect\netease-kanade-port`。目录含用户私有 APK、设置与截图，不提交。工具副本的 build.ps1 依赖该构建目录内的已准备输入；重建前应将工具放回工作目录，或配置路径。安装包签名与原个人修改版一致，直接覆盖安装。

验证记录：

- 进入主页面及点击原四标签后玻璃导航可用。
- 点击右侧唱片，实际进入并停留在 PlayerActivity，保存 player-fixed.png。
- 播放时两张封面截图方向不同；暂停后稳定截图的封面区域逐像素相同。
- 右侧唱片及歌单胶囊左右滑动，日志有 media next / media previous，曲目与封面状态随之变化。
- RN 歌单详情页与原生专辑页均记录新播放器安装；歌单实图显示正常暂停图标，没有空心圆。
- 测试未观察到目标进程 FATAL EXCEPTION；APK 签名与 16 KiB 对齐校验通过。

回退输入：构建目录的 hyperchanger-before.apk 与 before-settings.tar。不要直接覆盖旧 LSPosed 整库，以免丢失其他模块后来产生的设置。之前微信和小黑盒移植的 DEX 保持不变。

最终 APK SHA256：4BD54073156BF6ABC4AC2877C4BE484954527B1B86135BF2B2FC989915B8F74A
