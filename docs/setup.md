# 安装与配置

## Mac

支持 Apple 芯片与 macOS 12+。源码安装需要 Xcode Command Line Tools 和 Python 3；Release 已包含编译好的应用。

```sh
# 仅从源码构建时需要
xcode-select --install
bash scripts/install-macos.sh
```

安装位置为 `~/Applications/PadPaste.app`，登录启动项为 `io.github.fantuani.padpaste`。应用没有 Dock 图标。系统询问本地网络权限时允许访问；Release 为本地签名版本，如被拦截，可在「隐私与安全性」允许打开或从源码构建。

默认使用 `<Mac LocalHostName>.local:5034`。也可以指定地址和端口：

```sh
bash scripts/install-macos.sh --host 192.168.1.10 --port 5034
```

安装器保留现有配对信息。生成的平板配对文件位于：

```text
~/Library/Application Support/PadPaste/android-config.json
```

## 原生 Android App

1. 从 Release 下载并安装 Android APK。
2. 将 Mac 生成的配对文件传到平板。在 PadPaste 右上角「设置」中点击「导入配对文件」并选中 JSON。
3. 点击「重新检测」，确认首页显示「已连接」。
4. 在「设置」中点击「文件访问权限」，允许 PadPaste 访问文件，再打开截图监控开关。
5. 在系统应用设置中开启后台自启动，将省电策略设为「无限制」。小米设备可在最近任务里锁定 PadPaste，避免清理任务时移除它。

为及时读取尚处于临时文件名的新截图，需要全部文件访问权限。App 只监控配置的截图目录；不读取通知内容，不使用无障碍或 Root。

三指截图由系统短暂写入文件。监控开启时，App 对每张新截图只上传一次，传输结束后清理平板原图和媒体索引；失败时弹出提示，不保留待传图片，也不自动重传。Mac 只写入图像剪贴板，不保存图片文件。

成功与失败均使用屏幕底部的短暂气泡反馈，不更新通知栏中的传输结果。后台监控常驻通知中提供「暂停」按钮。关闭监控后，截图按系统默认方式保存；不会清理启用前的旧截图。应用重新打开、系统重建服务、开机或升级后，会按此前的开启状态恢复监听。

### 界面状态

- **Mac 连接**：根据带配对认证的请求确认，首页显示实际连接地址。App 界面打开时定期检查；后台不持续探测网络。
- **截图监控**：直接反映当前服务是否运行，不以通知是否存在作为依据。
- **最近同步**：显示 Mac 确认写入剪贴板的时间，以及该次上传与接收耗时。
- **同步失败**：仅在发生错误时显示失败原因。

私有数据库只记录文件名、路径与处理状态，用于中断后清理与去重，不保存图片内容或待重传任务。

## 设置

Mac 配置：`~/Library/Application Support/PadPaste/config.json`。

| 字段 | 默认值 | 用途 |
|---|---|---|
| `username` / `password` | 安装时生成 | 两端配对 |
| `port` | `5034` | 接收端口 |
| `flashEnabled` | `true` | 主屏边缘闪光 |
| `flashDuration` | `0.3` | 动画总时长，秒 |

Android App 可编辑服务器地址、用户名、配对密码和截图目录，或通过 JSON 导入。默认目录为 `/storage/emulated/0/DCIM/Screenshots`。

修改 Mac 设置后重启接收器；更改端口或密码后需重新生成配对文件并导入平板：

```sh
python3 scripts/configure.py
launchctl kickstart -k gui/$(id -u)/io.github.fantuani.padpaste
```

重置配对密码：`python3 scripts/configure.py --reset-token`。

## 从 AutoJs 迁移

原生 App 可使用原来的 `android-config.json`。确认原生 App 上传成功后，停止旧脚本，并在 AutoJs6「任务」中删除旧脚本的开机任务，避免同时上传。

Mac v0.1.x 可继续接收原生 App 的截图；v0.2.0 增加独立的连接检测接口。

## 停止与卸载

Mac 暂停或恢复：

```sh
launchctl bootout gui/$(id -u)/io.github.fantuani.padpaste
launchctl bootstrap gui/$(id -u) "$HOME/Library/LaunchAgents/io.github.fantuani.padpaste.plist"
```

卸载 Mac 应用：`bash scripts/uninstall-macos.sh`；同时删除配置和日志：`bash scripts/uninstall-macos.sh --purge`。

Android 先关闭截图监控，再卸载 PadPaste。关闭后的截图和启用前的旧截图不受影响。Release APK 使用持久签名，可覆盖升级；本地 debug APK 与发行版签名不同。
