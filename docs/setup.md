# 安装与配置

## Mac

支持 Apple 芯片与 macOS 12+。源码安装需要 Xcode Command Line Tools 和 Python 3；Release 安装包已经包含编译好的应用。

```sh
# 仅从源码构建时需要
xcode-select --install

bash scripts/install-macos.sh
```

应用安装到 `~/Applications/PadPaste.app`，通过 `io.github.fantuani.padpaste` 登录启动。它没有 Dock 图标。

首次连接时，如果 macOS 询问本地网络权限，允许 PadPaste。Release 为本地签名版本；如被系统拦截，可在「隐私与安全性」允许打开，或从源码构建。

默认使用 `<Mac 的 LocalHostName>.local:5034`。也可以指定局域网地址、端口：

```sh
bash scripts/install-macos.sh --host 192.168.1.10 --port 5034
```

安装器重复运行时保留现有密码和设置。需要重新配对时：

```sh
python3 scripts/configure.py --reset-token
```

重新配对或修改服务端配置后，重启接收程序，并把新的 `android-config.json` 复制到平板。

## 平板

安装 [AutoJs6](https://github.com/SuperMonster003/AutoJs6/releases)，并准备：

```text
/sdcard/PadPaste/config.json          ← Mac 生成的 android-config.json
/sdcard/Scripts/PadPaste/PadPaste.js  ← 仓库 android/PadPaste.js
/sdcard/Scripts/PadPaste/formats.js   ← 仓库 android/formats.js
```

可通过文件传输工具复制。已经配置 ADB 时，也可以：

```sh
adb shell mkdir -p /sdcard/PadPaste /sdcard/Scripts/PadPaste
adb push "$HOME/Library/Application Support/PadPaste/android-config.json" /sdcard/PadPaste/config.json
adb push android/PadPaste.js /sdcard/Scripts/PadPaste/PadPaste.js
adb push android/formats.js /sdcard/Scripts/PadPaste/formats.js
```

给 AutoJs6 开启全部文件访问、后台自启动和省电「无限制」，允许前台运行通知。在 AutoJs6 中找到 `PadPaste.js` 并运行。脚本不需要无障碍、Root、通知读取或剪贴板读取权限。

默认截图目录为 `/sdcard/DCIM/Screenshots`。若图片实际保存在其他目录，修改平板配置中的 `screenshotDirectory`。

## 设置

Mac 配置：`~/Library/Application Support/PadPaste/config.json`。

| 字段 | 默认值 | 用途 |
|---|---|---|
| `username` / `password` | 安装时生成 | 与平板配对 |
| `port` | `5034` | 接收端口 |
| `flashEnabled` | `true` | 显示主屏边缘闪光 |
| `flashDuration` | `0.3` | 动画总时长，秒 |
| `cacheLimit` | `20` | 保留的接收文件数量 |

平板配置：`/sdcard/PadPaste/config.json`。

| 字段 | 用途 |
|---|---|
| `server` | Mac 的 HTTP 地址，包含端口 |
| `username` / `password` | 与 Mac 一致 |
| `screenshotDirectory` | 截图文件夹 |
| `startOnBoot` | 注册 AutoJs6 开机任务，默认开启 |
| `diagnostics` | 保存最近的分段计时，默认关闭 |

修改后重启对应程序。Mac 重启命令：

```sh
launchctl kickstart -k gui/$(id -u)/io.github.fantuani.padpaste
```

## 停止与卸载

暂时停止或恢复：

```sh
launchctl bootout gui/$(id -u)/io.github.fantuani.padpaste
launchctl bootstrap gui/$(id -u) "$HOME/Library/LaunchAgents/io.github.fantuani.padpaste.plist"
```

卸载 Mac 应用，保留配置与缓存：

```sh
bash scripts/uninstall-macos.sh
```

同时删除配置与接收缓存：

```sh
bash scripts/uninstall-macos.sh --purge
```

平板端停止脚本后，在 AutoJs6「任务」中删除它的开机任务，再删除脚本和 `/sdcard/PadPaste`。原始截图不会被脚本删除。
