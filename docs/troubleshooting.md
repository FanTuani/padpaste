# 故障排查

## 界面显示未连接

确认两台设备在同一局域网、Mac 已唤醒，点击「检测连接」。配对错误时重新导入 Mac 生成的 JSON。若 `.local` 无法解析，可把地址改为 Mac 当前局域网 IP；更换网络后需同步更新。

## 已连接但截图没上传

「Mac 连接」和「截图监控」是两个独立状态。检查自动同步是否开启、文件访问是否授权、截图实际目录是否一致，以及文件名是否为 `Screenshot_…` 或小米 `.pending-…-Screenshot_…`。

## 清理后台后停止

重新打开 PadPaste，之前启用的监控会自动恢复。开启后台自启动和省电「无限制」，小米设备可锁定最近任务。系统强行停止与部分厂商清理行为会阻止后台重启；可查看系统的应用退出原因，而不只看通知图标。

## 截图排队或上传失败

App 显示待传数量和上次错误。网络恢复后会自动重试。队列在应用私有数据库中持久保存；不要清除应用数据。

## 修改 Mac 设置

编辑 `~/Library/Application Support/PadPaste/config.json` 后重启接收器。改了密码或端口，还需要重新生成配对文件并导入平板。

Mac 日志：`~/Library/Application Support/PadPaste/receiver.log`。分段计时：同目录 `latency-trace.json`。Android 调试日志可通过 `adb logcat` 查看。
