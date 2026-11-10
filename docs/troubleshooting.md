# 故障排查

## 截图没有到 Mac

1. 确认两台设备在同一局域网，Mac 没有睡眠。
2. 检查 `/sdcard/PadPaste/status.json`。`watching` 表示已启动，`uploaded` 表示成功，`retrying` 附有失败原因。
3. 确认实际截图目录与 `screenshotDirectory` 相同，文件名为 `Screenshot_…` 或小米 `.pending-…-Screenshot_…`。
4. Mac 日志位于 `~/Library/Application Support/PadPaste/receiver.log`。如端口已被其他接收程序使用，可修改端口并重新生成平板配对文件。

## `.local` 地址无法连接

部分网络会隔离无线设备或不转发 mDNS。把平板配置的 `server` 改为 Mac 当前局域网 IP；Mac 地址变化后需同步更新。

```sh
python3 scripts/configure.py --host 192.168.1.10
```

## 平板过一会就停止上传

给 AutoJs6 开启后台自启动、允许前台通知，并把省电策略设为「无限制」。不要强制停止 AutoJs6。恢复时重新运行脚本即可。

## 配置修改后不生效

Mac 和平板各自读取本地配置，修改后需要重启。改了 Mac 端口或配对密码，还需重新生成并复制平板配置。

## 如何定位延迟

将平板配置中的 `diagnostics` 设为 `true`，重启脚本。随后查看：

- 平板：`/sdcard/PadPaste/latency-trace.json`。
- Mac：`~/Library/Application Support/PadPaste/latency-trace.json`。

计时覆盖文件完成、读取、上传、解码与剪贴板写入。不同设备时钟不能直接相减；比较跨设备总耗时前先校准时钟。日常使用可关闭平板详细诊断。
