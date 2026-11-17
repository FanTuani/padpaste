# 草稿

独立的 Android 原生草稿纸，面向小米平板和手写笔。

- 无边界矢量画布，单指平移、双指缩放。
- 手写笔书写，使用固定线宽的空间平滑曲线，支持系统防误触取消事件；手指不落墨。
- 小米笔下键短按撤销，按住 200ms 临时橡皮、松开回笔；上键短按切换墨黑/红色。普通键盘翻页键不受影响。
- 六种笔色，笔粗细按 0.1 步进调节，橡皮大小可调，均有预览。
- 圆形局部橡皮，只擦掉经过的部分；笔压越大擦除圈越大，支持快速扫过和整次撤销。
- 用笔矩形框选后拖动，复制、粘贴或删除选区。
- 会话仅在内存中：旋转屏幕保留画布，进程结束不保存。

无网络、存储或无障碍权限。此版不接入电脑截图。

```sh
cd android-canvas
./gradlew testStandardDebugUnitTest lintStandardDebug assembleStandardDebug assembleStandardDebugAndroidTest
```

小米兼容构建使用系统硬编码的绘图免拦截包名 `com.miui.pen.demo`（界面名称为「草稿·小米笔」），使下键长按不会打开系统笔记。它仍是本项目自行签名的普通应用，不授予小米系统权限，不修改原笔记，不改变全局游戏模式。普通构建保持 `io.github.fantuani.draftcanvas`。该兼容行为依赖当前 HyperOS 固件，更新系统后需复测。

Android 8+，JDK 17、SDK 35。首次连接真机时安装 debug APK 和 androidTest APK，运行：

```sh
adb shell am instrument -w io.github.fantuani.draftcanvas.debug.test/io.github.fantuani.draftcanvas.CanvasTestRunner
```

Debug 包名带 `.debug`，可与正式版共存；小米按键免拦截需用正式版验证。

测试使用合成笔/触摸事件验证 View 交互和局部擦除绘制；实际小米笔的压力与手掌检测仍需真笔体验确认。
