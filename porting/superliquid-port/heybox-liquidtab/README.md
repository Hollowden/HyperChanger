# 小黑盒 LiquidTab 底栏补丁

这组补丁基于个人 HyperChanger APK 中已重定位的 SuperLiquid 适配。补丁保留小黑盒底栏的布局、图标和交互，仅调整小黑盒的玻璃材质和安装时的重应用行为。白天模式关闭额外的方向性边缘高光。深色模式继续使用双光源边缘高光。

材质辅助类的双光源逻辑改编自 LiquidTab v2.1.0 的 `GlassHostLayout`，使用 GPL-3.0-only 授权。原项目与许可证见 [LiquidTab v2.1.0](https://github.com/big-dictator/LiquidTab/tree/v2.1.0) 和 [GPL 许可证](LICENSE-GPL-3.0.txt)。

## 重建个人 APK

使用独立构建目录。以下流程仅适用于 `package.py` 能识别的个人版输入包。所需工具为 JDK 17、Android SDK Platform 36、Build Tools 36 和 Apktool 3.0.3。

1. 用 `python package.py personal.apk secondary.apk` 提取第二个 DEX。用 `apktool d -r --no-debug-info secondary.apk -o secondary-smali` 反汇编。
2. 脚本如果报告输入仍是「stable Heybox port」，在解包目录应用本目录中的 `heybox-liquidtab.patch`。在独立 Git 工作区可用 `git apply --no-index --unidiff-zero <补丁路径>`。已应用 LiquidTab 材质的个人版跳过这一步。
3. 用 `javac --release 17 -cp <android.jar> -d helper-classes HeyboxGlassMaterial.java` 编译辅助类，用 D8 的 `--min-api 35` 将该类单独转成 DEX，再反汇编出 `HeyboxGlassMaterial.smali`。将它复制到解包目录的 `smali/btm/m/ported/superliquid/io/github/liuran001/mmliquidglass/`。
4. 用 `apktool b secondary-smali -o rebuilt-secondary.apk` 重建，并用 `python package.py personal.apk unsigned.apk --rebuilt-secondary rebuilt-secondary.apk` 装回个人 APK。脚本校验除 `classes2.dex` 之外的 APK 内容保持一致。
5. 用 `zipalign -P 16` 对齐，再使用与已安装个人版相同的密钥签名。安装后重开小黑盒。
