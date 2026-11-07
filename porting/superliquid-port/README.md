# SuperLiquid 微信 / 小黑盒个人移植

2026-11-06，设备 Xiaomi 17 Pro Max，Android API 37，libxposed/LSPosed API 102。

这是对当前安装 APK 的 DEX 移植补丁，不是 SuperLiquid 完整源码的重新构建。输入为用户设备上的 HyperChanger 1.2.2 RC2 与 SuperLiquid 0.4.8-r19。原有 native 库、资源、manifest 及 classes2–4.dex 逐字节保留，包括此前的桌面、解锁和通知栏修改。

`prepare_port.py` 从 SuperLiquid 的 smali 提取渲染/适配器依赖闭包，统一重定位类名，避免 R8 混淆类碰撞；由于合并的合成类引用，闭包包含部分不执行的 UI 依赖。新增入口只接受微信和小黑盒主进程，跳过其他进程时不调用 detach，以免影响同 APK 的其他入口。HyperChanger 原微信导航安装入口被禁用，小黑盒加入原应用导航列表。

`configure_port.py` 将配置来源改为 HyperChanger 自己的 `hyper_system_ui_hook`，保留 SuperLiquid 的 mm_/heybox_ 参数并接入原同步路径。`fix_float_sync.py` 补齐 Float 配置同步；`fix_resume_gate.py` 防止关闭自定义底栏后 resume 回调再次安装。Java `NavigationPreferences` 连接启用、液态玻璃模糊、宽高缩放与上下偏移。另两个 Java 文件只是编译期声明，**不得打包**。

目前标签显示方式、强调色、其他导航风格等 HyperChanger 专属选项未映射到 SuperLiquid 引擎；渲染与交互保持 SuperLiquid 的实现。调整已接入的选项后重启目标应用。

## 重建

在单独工作目录放置原始 `hyperchanger-before.apk`、`superliquid.apk` 和这些脚本。用 Apktool 3.0.3 `d -f -r` 分别反汇编为 `hyper-smali`、`super-smali`。依次运行 prepare_port、configure_port、fix_float_sync、fix_resume_gate。脚本针对这些原始输入运行一次；重新制作时先重新反汇编。

使用 android.jar 编译 java 下三个声明，D8 **仅输入 NavigationPreferences.class** 输出 adapter-dex/classes.dex。Apktool 分别构建 hyper-smali 为 hyper-patched.apk、port-dex 为 port-payload.apk，再运行 package_port.py。最后 zipalign -P 16 并使用本机原有个人密钥签名。不能直接使用未重定位的 SuperLiquid classes.dex。

不要提交用户 APK、设置备份、日志或签名密钥，不推送上游。当前脚本只适用本次输入版本。

## 真机验证

- APK 签名与 16 KiB 对齐通过；证书与手机原个人修改版一致，直接覆盖安装成功。
- LSPosed 作用域：HyperChanger 同时包含微信、小黑盒；SuperLiquid 对这两个应用的作用域已移除。
- 微信 8.0.79：新入口加载，玻璃底栏安装，拖动从 0 跳转至 3，日志确认 slide done / scroll state 0。
- 小黑盒 1.3.400：新入口加载，四标签玻璃底栏安装，保留 width=0.5、pill=1.49、offset=6、offsetX=34；拖动后显示游戏库页面。
- HyperChanger 设置列表及小黑盒详情页可打开；浮点参数已同步到自己的远程设置。
- 本轮未看到目标进程的 FATAL EXCEPTION 或 VerifyError。验证覆盖当前深色模式，未穷尽所有页面、版本与主题。

回退原 APK、原本地配置及 LSPosed 作用域需一起恢复；**不要把旧 LSPosed 整库直接覆盖回去**，它可能抹除其他模块后来产生的设置。
