# PadPaste

平板三指下滑截图，Mac 直接 `⌘V` 粘贴。

同一 Wi-Fi 下，原生 Android App 监听新截图，Mac 接收原图并写入图片剪贴板，同时在**主屏边缘闪白 0.3 秒**。无需云服务、数据线、Root 或 AutoJs。

- 平板界面显示连接状态、监控状态和最近同步记录。
- 文件事件触发；每张截图只尝试传输一次，失败时平板弹出提示。
- Mac 登录启动，图片直接写入剪贴板，不保存接收文件。
- 监控开启后，平板新截图在传输结束时自动删除；成功与失败均不保留。
- Mac 端仅支持 **Apple 芯片、macOS 12+**；平板端支持 Android 8+。

## 开始使用

### Mac

下载 [Release](https://github.com/FanTuani/padpaste/releases/latest) 中的 Apple 芯片安装包，解压并运行：

```sh
bash scripts/install-macos.sh
```

从源码安装需要 Python 3 和 Xcode Command Line Tools：

```sh
git clone https://github.com/FanTuani/padpaste.git
cd padpaste
bash scripts/install-macos.sh
```

### 平板

1. 安装同一 Release 中的 Android APK。
2. 把 Mac 生成的 `~/Library/Application Support/PadPaste/android-config.json` 传到平板，在 App 中点击「导入配对文件」。
3. 授权文件访问，打开截图监控开关。
4. 按界面提示设置后台自启动与省电「无限制」。

随后在平板截图，在 Mac 粘贴即可。

[安装与配置](docs/setup.md) · [工作原理与接口](docs/architecture.md) · [故障排查](docs/troubleshooting.md)

## 开发

```sh
bash scripts/build-macos.sh
cd android-native && ./gradlew assembleDebug testDebugUnitTest
```

Android 构建需要 JDK 17 与 Android SDK 35。旧 AutoJs 客户端保留在 `android/`，原生 App 与它共用接收协议。

MIT License。依赖与致谢见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
