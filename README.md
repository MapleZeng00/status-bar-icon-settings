# Status Bar Icon Settings

面向小米 15 Ultra、澎湃 OS 4、SukiSU Ultra 和官方 LSPosed 2.2.1（7912）的 Kotlin 模块。设置页提供独立的移动网络、Wi‑Fi、无 SIM 卡和电池图标开关，以及顶部电量横线和详细日志开关。所有开关默认关闭。移动网络开关针对整个蜂窝图标组，包括信号格以及由同一组件渲染的 4G／5G 标识。模块修改 SystemUI 的显示逻辑，网络连接仍由系统管理。

## 安装

1. 在手机上确认 SukiSU Ultra 和当前的 Zygisk 实现已正常运行。在 LSPosed 管理器中确认官方 LSPosed 2.2.1（7912）显示已激活。保留 LSPosed 安全模式，并确认已有可用的 USB 调试授权或可访问 SukiSU 管理器，方便恢复。
2. 按照下文构建 Debug APK，再安装 `app/build/outputs/apk/debug/app-debug.apk`。也可以使用 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
   从使用相同包名和签名的版本升级时可以覆盖安装。LSPosed 仓库版使用包名 `io.github.iammaplezeng.statusbaricons`，首次从 `dev.statusbar.networkicons` 版切换时，请在 LSPosed 停用旧模块并重新配置新版。
3. 在 LSPosed 管理器的「模块」中启用「Status Bar Icon Settings」。在作用域中只勾选「系统界面」(`com.android.systemui`)。作用域限制为这个包，实际 Hook 进一步限制为同名主进程。
4. 首次启用或升级后，在 LSPosed 重新加载系统界面一次，也可重启手机。打开桌面上的「Status Bar Icon Settings」。连接状态应显示框架版本和 API 102。如果显示等待服务，检查模块是否已启用、作用域是否已保存，以及框架重启后是否仍激活。
5. 按下表测试。调整开关或选择颜色时自动保存。SystemUI 收到新设置后会更新正在显示的状态栏。

## 真机验收

先在正常解锁的顶部状态栏确认测试基线。用可用 SIM 和稳定 Wi‑Fi 测试。如果 ROM 在连 Wi‑Fi 时自动隐藏 4G／5G 标签，暂时断开 Wi‑Fi 并连接蜂窝数据，单独核对移动网络图标。

| 隐藏移动网络 | 隐藏 Wi‑Fi | 预期 |
| --- | --- | --- |
| 关 | 关 | 与启用模块前一致 |
| 关 | 开 | Wi‑Fi 图标消失，蜂窝图标按系统规则显示 |
| 开 | 关 | 蜂窝图标消失，Wi‑Fi 图标按系统规则显示 |
| 开 | 开 | 两组图标均消失 |
| 关 | 关 | 两组恢复到系统原有行为 |

测试移动网络和 Wi‑Fi 时，先关闭另外两个隐藏开关。无 SIM 卡时可以单独测试新增功能。

| 隐藏无 SIM 卡 | 隐藏电池 | 预期 |
| --- | --- | --- |
| 关 | 关 | 截图中的叉号和带数字的电池图标均显示 |
| 开 | 关 | 只隐藏叉号 |
| 关 | 开 | 只隐藏电池图标及其电量数字 |
| 开 | 开 | 两个图标均隐藏 |

每组测试时检查截图左侧的网速显示。开启隐藏电池后，插拔充电线并观察电量更新，检查图标是否重新出现。最后关闭两个开关，确认图标恢复。

逐项确认 Wi‑Fi 与移动数据仍可连接，切换网络后图标仍符合所选组合。有双卡时检查两个蜂窝图标。观察锁屏、通知面板、控制中心，记录是否有连带变化。网络图标的设置面向顶部状态栏。隐藏 Wi-Fi 或无 SIM 卡图标后，检查右侧是否还留有空位，再关掉开关检查图标是否恢复。电池设置也可能影响使用同一电量控件的锁屏或控制中心。

### 顶部电量横线

1. 开启「横线显示电量」，点「横线颜色」选择颜色。默认颜色为青绿色。调色盘可拖动选择色相、饱和度和亮度，也可输入 #RRGGBB 色值。
2. 在顶部状态栏观察从屏幕左侧延伸的 2 dp 横线。约 57% 电量时应占状态栏宽度约 57%。充电或放电导致电量变化时，横线会自动更新。
3. 可以同时开启「隐藏电池图标」，用横线表示电量。再换一种颜色，检查颜色是否生效。
4. 测试横竖屏切换和全屏应用。横线随状态栏显示，状态栏隐藏时横线也会隐藏。关闭横线，检查横线是否消失。

### 充电动画

在「横线显示电量」开启时，点「充电动画」，可选择「关闭」「渐变流光」「波浪」「彗星拖尾」「双向扫光」或「呼吸光」。默认关闭。

- 渐变流光会在 2 dp 横线上移动一段更亮、更粗的高亮，最粗约 5 dp。
- 波浪会让圆滑的波峰和波谷沿横线移动。旧版选中的心电图会自动切换为波浪。
- 彗星拖尾会用一串逐渐变淡的光点扫过电量线。
- 双向扫光会让两束高亮从两端对向移动。
- 呼吸光会让电量线周期性变粗、变细。
- 所有动画都以当前电量长度为边界。拔线、充满或暂停充电后恢复纯色横线。

在电量低于 100% 时插电并确认系统显示正在充电，观察动画，拔线后检查横线是否恢复静止。保持充电并切换另一种动画，检查样式是否直接变化。

### 状态栏时钟

「时间显示秒」默认关闭。「时间颜色」默认跟随系统，也可独立打开调色盘选色。切换后，在顶部状态栏检查秒数是否每秒更新，以及切换浅色和深色背景后自定义颜色是否仍生效。关闭秒数或选择「跟随系统」即可恢复对应的系统显示。

## 网速显示门槛

先在 HyperOS 系统设置中启用实时网速。点「网速显示门槛」，输入整数 KB/s 后确定。例如填 50 时，高于 50 KB/s 显示，等于或低于 50 KB/s 隐藏。选择「跟随系统」可取消门槛。该设置使用系统已显示的网速读数。

## 状态栏进阶设置

从「高级选项」打开「状态栏进阶设置」，调整后点「确定」。升级后先重新加载一次系统界面。

### 0.9.8 新增开关

| 开关 | 测试方法与预期 |
| --- | --- |
| 降雨提醒 | 同时打开「显示天气」。小米天气最近一小时内更新过数据，且未来一小时降雨概率达到 60% 时，最右侧切换为降雨概率。文案跟随天气语言。其余时间显示原天气。当前验证的天气来源提供小时概率。 |
| 网速走势图 | 在系统设置打开实时网速。下载文件观察近 10 秒的曲线和最高点。达到现有隐藏门槛，或最近 10 秒没有非零样本时，走势图也会隐藏。 |
| 耳机播放曲名 | 戴耳机并切换到一首新歌。播放应用提供曲名且媒体路由指向耳机时，在时钟旁显示约 4 秒。锁屏时隐藏曲名。 |
| 系统计时器进度线 | 在小米时钟启动一个 30 秒以上的计时器，退出到桌面。顶部蓝线按剩余时间缩短，最后 10 秒变橙。取消或结束后恢复原电量线设置。依赖系统时钟的倒计时通知。 |
| 白天整点星光 | 在 07:00 至 21:00 的任一整点保持亮屏，观察一颗星从顶部扫过。 |
| 充电粒子 | 保持亮屏并充电跨过 80% 或到达 100%，观察电量位置的短粒子动画。已到达该电量后才开启开关不会重播。 |
| 第二时区 | 选择伦敦或其他时区，核对时钟旁的第二组时间。选择应用后，打开其中一个应用再退出，核对显示范围。 |
| 隐私文字 | 打开正在访问摄像头或麦克风的应用，核对原有系统指示器与旁边的文字。不同 ROM 的隐私指示器位置和数据入口可能变化。 |

八个开关默认关闭。保存设置后，SystemUI 已载入本版模块时，开关通常在一秒内生效。

- 「显示天气」默认关闭。开启后在顶部状态栏最右侧显示小米天气的当前温度，无数据时隐藏。长按显示中的天气可打开小米天气。请先在小米天气中添加城市并刷新。「显示天气状况」默认关闭，开启后可选择跟随系统、简体中文或 English。温度沿用小米天气的单位。阅读和游戏样式下隐藏天气。
- 网速按 50 和 1024 KB/s 分档变色。开启防抖后，超过门槛即显示，降到门槛的 60% 或以下并持续至少 3 秒才隐藏，时间受系统网速采样间隔影响。
- 电量线在 15% 及以下用红色，16%–35% 用橙色，其余用绿色。按电量变色优先于自定义横线颜色，充电动画也使用当前电量对应的底色。关闭按电量变色后恢复原先选定的颜色。
- 时钟可加星期、淡化秒数、启用等宽数字。等宽数字由系统字体支持。开启手势后，单击切换是否显示秒数，长按打开闹钟。单击切换临时生效，秒数淡化可在设置中单独调整。
- 可排序网速、Wi-Fi、信号和蓝牙，电池可放在整组两端。间距可设 0–12 dp，网速也可显示在时钟旁。
- 通知图标白名单只影响顶部状态栏。启用后，空白名单隐藏所有顶部通知图标。
- 阅读样式保留时钟和电量线，游戏样式保留系统网速。相同应用被选入两种样式时优先使用游戏样式。切回其他应用时恢复常用设置。实时网速需在系统里开启。

## 日志与恢复

可在 LSPosed 的模块日志中搜索 `NetworkStatusIcons`。也可以在授权的电脑上运行：

```text
adb logcat -d -s NetworkStatusIcons
adb logcat -b crash -d
adb shell getprop ro.build.fingerprint
adb shell dumpsys package com.android.systemui
```

- `SystemUI loaded` 表示已注入。`Live settings received` 表示 SystemUI 收到了新的设置。
- `Battery line attached` 表示横线已附着到状态栏，可通过 `adb logcat -d -s NetworkStatusIcons` 查看。
- 若出现 `path unavailable` 或 `path incompatible`，保留 ROM 版本、地区、SystemUI 版本和相关日志。

- 回退时，在 LSPosed 管理器停用本模块并重启。若 SystemUI 反复崩溃而无法打开管理器，可从已授权的电脑执行 `adb uninstall io.github.iammaplezeng.statusbaricons`，卸载成功后执行 `adb reboot`。如果手机端只能访问 SukiSU 管理器，也可以暂时停用 LSPosed 并重启。该操作会暂停其他 LSPosed 模块。

## 构建

使用 JDK 17、Android SDK Platform 37.0（`platforms;android-37.0`）和 Build Tools 36.0.0。Android Studio 可以打开工程根目录。命令行可设置 `ANDROID_HOME` 指向 SDK，或在工程根目录创建包含 `sdk.dir` 的 `local.properties`。

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

macOS 或 Linux 使用 `./gradlew`。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。工程包含 Gradle 9.5.1 Wrapper，使用 AGP 9.2.1 的内置 Kotlin 支持。Android 的编译 SDK 和 LSPosed API 是两套版本号。应用的 Android `minSdk` 为 28，`targetSdk` 为 34。

## 兼容性边界

- 从 LSPosed 官方站下载的框架包声明 `v2.2.1 (7912)`，其 `framework.dex` 的 `getApiVersion()` 返回 102。工程采用稳定版 `io.github.libxposed:api:102.0.0`（仅编译）和 `service:102.0.0`（随应用打包）。模块入口、最低 API、目标 API 和固定作用域在 `META-INF/xposed/` 中声明。设置通过框架的远程首选项共享。
- 设置页写入 LSPosed 远程首选项，SystemUI 监听变化并在主线程更新视图。网络图标通过 SystemUI 的图标管理器识别，电池通过小米电量控件识别。模块记录原有可见性，并让隐藏的网络图标退出小米状态栏的测量。取消隐藏时恢复对应的系统状态。
- 电量横线附着在手机状态栏视图的绘制层，监听系统电量广播。它不接收触摸事件。
- 这些入口参考了社区针对 HyperOS SystemUI 的实现。小米可在地区版、OTA 或不同 SystemUI 版本中修改图标路径。APK 构建和 API 核验在电脑完成，小米 15 Ultra 真机注入、配置跨进程传递和实际图标效果需要按上表确认。

### 参考

- [LSPosed 官方站及下载](https://lsposed.org/)
- [LSPosed 官方发布频道](https://t.me/s/LSPosed)
- [Modern Xposed API 模块文档](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [libxposed 稳定构件与源码包](https://repo.maven.apache.org/maven2/io/github/libxposed/)
- [HyperCeiler 的 HyperOS Wi-Fi 屏蔽列表实现](https://github.com/ReChronoRain/HyperCeiler/blob/0830c5875570e66ae576b9276b0e57033cb6601a/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemui/statusbar/icon/all/StatusBarIcon.java)
- [HyperCeiler 的 HyperOS 4 蜂窝绑定器实现](https://github.com/ReChronoRain/HyperCeiler/blob/0830c5875570e66ae576b9276b0e57033cb6601a/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemui/statusbar/mobile/MobilePublicHookV.kt)
- [HyperCeiler 的小米电池控件参考](https://github.com/ReChronoRain/HyperCeiler/blob/0830c5875570e66ae576b9276b0e57033cb6601a/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemui/statusbar/icon/all/HideBatteryIcon.kt)
- [AOSP PhoneStatusBarView](https://android.googlesource.com/platform/frameworks/base/+/main/packages/SystemUI/src/com/android/systemui/statusbar/phone/PhoneStatusBarView.java)
- [ClockSeconds 即时更新参考](https://github.com/kelebao/ClockSeconds)
