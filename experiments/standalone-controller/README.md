# 独立控制器 1.0-standalone.8（Android 12+）

安装、配对和使用见仓库根 README。独立重建使用本目录 Build-Standalone.ps1，必须显式传入 JdkPath、SdkPath 和 WorkRoot；所需二进制依赖见 Release 的完整源码包。

默认使用 R8 和 shrink-rules.pro 裁剪未使用的代码，不混淆、不优化。

关闭控制界面时，USB 调试已开启则先关闭无线调试；设置无法回读确认时保持界面打开。

第三方许可见 THIRD_PARTY_NOTICES.md，对应源码及重新链接说明见 third-party-source/README.md。
