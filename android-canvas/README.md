# 草稿

独立的 Android 原生草稿纸，面向小米平板和手写笔。

- 无边界矢量画布，单指平移、双指缩放。
- 手写笔书写，使用固定线宽的空间平滑曲线，支持系统防误触取消事件；手指不落墨。
- 小米笔下键短按撤销，按住 200ms 临时橡皮、松开回笔；上键短按在上次笔尖悬停处打开扇形工具菜单，长按进入框选待命。普通键盘翻页键不受影响。
- 四种常用笔色，笔粗细按 0.1 步进调节，橡皮大小和压感灵敏度可调，尺寸设置会自动保存。
- 圆形局部笔段橡皮，擦除断口为圆形端帽；笔压越大擦除圈越大，压感灵敏度可调，支持快速扫过和整次撤销。
- 进入框选后，用笔尖落笔拖出矩形，抬笔完成。用笔拖动选区可移动内容，轻点选区可打开复制、粘贴、删除和取消菜单。工具菜单右下可总览或清空画布，画布左下显示当前缩放比例，轻点可恢复 100%。菜单外的首次笔触或手指轻点会收起菜单。
- 自动保存一张草稿到 App 私有目录，重新打开会恢复笔迹和画布位置。

无网络、存储或无障碍权限。此版不接入电脑截图。

```sh
cd android-canvas
./gradlew testStandardDebugUnitTest lintStandardDebug assembleStandardDebug assembleStandardDebugAndroidTest
```

小米兼容构建使用系统硬编码的绘图免拦截包名 `com.miui.pen.demo`（界面名称为「草稿·小米笔」），使下键长按不会打开系统笔记。它仍是本项目自行签名的普通应用，不授予小米系统权限，不修改原笔记，不改变全局游戏模式。普通构建保持 `io.github.fantuani.draftcanvas`。该兼容行为依赖当前 HyperOS 固件，更新系统后需复测。小米平板 6 Pro 的 HyperOS 会优先将上键长按用于系统截图，在此机型上可短按上键并点扇形菜单左下的「框选」，拖动笔尖并抬笔完成框选。

Android 8+，JDK 17、SDK 35。首次连接真机时安装 debug APK 和 androidTest APK，运行：

```sh
adb shell am instrument -w io.github.fantuani.draftcanvas.debug.test/io.github.fantuani.draftcanvas.CanvasTestRunner
```

Debug 包名带 `.debug`，可与正式版共存；小米按键免拦截需用正式版验证。

测试使用合成笔/触摸事件验证 View 交互和局部擦除绘制；实际小米笔的压力与手掌检测仍需真笔体验确认。

擦除采用共享采样点、可见区间和笔段包围盒树，后台分区栅格缓存、前台即时擦除预览；设计参考 [Xournal++ 的区间式擦除](https://github.com/xournalpp/xournalpp/blob/master/src/core/model/eraser/ErasableStroke.cpp)，使用独立 Java 实现。
