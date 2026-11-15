# vivo 双击电源键打开相机

为 vivo X300 / OriginOS 6（Android 16）制作的双击电源键相机快捷方式。无需 root、Shizuku、Key Mapper 或 Tasker。

## 下载与使用

到 [Releases](../../releases/latest) 下载 `VivoCamera-Controller-1.0-standalone.6.apk`。控制器支持 arm64-v8a，最低 Android 8.1；此前版本只在 X300 / OriginOS 6 上验证，本版退出调试开关改动已通过构建和回归测试，尚未真机验收。

1. 将系统“双击电源键”设为“无功能”，关闭其他工具的按键拦截。
2. 安装 APK，连接 Wi-Fi（无需互联网），开启开发者选项。
3. 打开 App，点“自动配对”，按提示临时开启首次配对助手无障碍服务，再开启无线调试并确认当前网络。自动配对不适配时使用“手动配对（备用）”。
4. 配对成功后点“启动 1.0”。确认正在运行后即可关闭控制界面。

首次配对助手只在主动发起后的 120 秒内接收系统设置窗口事件，成功后关闭自身；它不拦截按键。配对身份由 App 在运行时生成并保存在 App 私有目录，配对码不写日志或偏好设置。

运行期间请保留 USB 调试开关，不要求插 USB 线。手机重启后，重新连接 Wi-Fi、打开 App 并点“启动 1.0”；它不会自动开机恢复。查询、启动、停止需要本机无线调试连接，切换网络可能需要重新确认。清除 App 数据或卸载会丢失配对。

`1.0-standalone.6` 点击“关闭控制界面”时，如果 USB 调试已开启，会先关闭无线调试，回读确认后再退出；保留 USB 调试和已运行的监听器。USB 调试未开启时不改无线调试。无法确认设置时保持界面打开并提示处理。再次打开控制器查询、启动或停止时，可能重新开启无线调试。

独立监听器以 Android shell 权限只读监听物理按键，不独占输入设备，不吞掉系统按键，也不开放网络端口。相机通过系统安全相机入口启动。退出控制 App 不等于停止监听器，请用 App 的“停止 1.0”。

## 电脑 ADB 方式

也可下载并解压 `VivoCamera-Core-1.0.zip`，用自己安装的 Android platform-tools 启动：

```powershell
.\Start-VivoCamera.ps1 -AdbPath 'C:\Android\platform-tools\adb.exe'
.\Start-VivoCamera.ps1 -AdbPath 'C:\Android\platform-tools\adb.exe' -DoubleMs 350 -DelayMs 250
.\Stop-VivoCamera.ps1 -AdbPath 'C:\Android\platform-tools\adb.exe'
```

多个设备时加 `-Serial 'DEVICE_SERIAL'`。`-DryRun` 只检测双击；双击间隔支持 150–600 ms，第二次松开后等待支持 0–1000 ms，默认分别为 350 和 150 ms。看到 `READY` 后可拔线，重启后需重新启动。

## 源码与构建

`src/` 是 1.0 核心，`tests/` 是检测器回归测试，`experiments/standalone-controller/` 是已发布控制器源码。

Release 的 `VivoCamera-Controller-source-public.zip` 包含与仓库相同的源码、固定第三方 JAR/native 输入、许可证和 LGPL 对应源码，供独立重建和重新链接。仓库本身不包含第三方依赖二进制；克隆后可从该源码包取出对应的 `libs/`、`native/` 和 `assets/vivo-camera.jar`。

在解压后控制器目录运行（PowerShell 7、JDK 17、Android SDK platform android-37.0、build-tools 36.0.0）：

```powershell
.\Build-Standalone.ps1 -JdkPath 'C:\Tools\jdk-17' -SdkPath 'C:\Android\Sdk' -WorkRoot 'C:\BuildCache\vivo'
```

产物在 `<WorkRoot>/dist/`；构建只使用已有依赖，不下载工具。签名使用该工作目录中新生成的开发密钥，无法直接覆盖本 Release 的签名安装包。开发密钥不随源码包分发。最低 API 27，native ABI 为 arm64-v8a。SPAKE2 的修改和重新链接步骤见控制器 `third-party-source/README.md`。

仓库根 `Build.ps1` / `Build.cmd` 及控制器 `Build-Standalone-Local.ps1` 保留维护者共享 `_env` 构建入口；使用它们需要单独恢复该固定共享环境，不属于独立源码包的必备步骤。

## 限制与验证范围

核心检测器包含 185 项断言，退出策略另有回归测试。原版在 X300 / OriginOS 6 上验证过监听器启动、主动停止和双击打开相机。本版 APK 由修改后的源码构建，沿用既有正式签名，可作为原签名应用的更新；核心 JAR 保持原字节。尚未对本版退出行为进行真机验证。

系统升级可能改变 shell 读取输入或启动相机的权限。系统仍处理电源键亮灭屏；若刚打开相机又关屏，可调整等待时间。长时间待机、完全断网拔线组合、重启恢复及 OriginOS 7 仍需进一步验证。

第三方许可、署名和对应源码保留在控制器的 `THIRD_PARTY_NOTICES.md`、`assets/licenses/` 和 `third-party-source/`。项目自有代码尚未另行授予开源许可证；第三方组件继续适用其原许可。
