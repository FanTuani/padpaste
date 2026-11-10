# 工作原理与接口

```text
截图文件完成写入
  → AutoJs6 FileObserver
  → 检查完整图片、统一临时文件名
  → 一次 HTTP 上传
  → Mac 解码、保留原图缓存
  → 写入图像剪贴板
  → 主屏边缘渐隐闪光
```

## 平板端

监听 `CREATE`、`MODIFY`、`CLOSE_WRITE` 和 `MOVED_TO`。文件关闭写入时立即检查；其他事件等待文件尺寸稳定，并确认 JPEG、PNG 或 WebP 的结束标记。

小米的 `.pending-…-Screenshot_….jpg` 与 `Screenshot_….jpg` 映射到同一个名字。第一次完整图片上传成功后，后续重命名不再重复发送。脚本启动前已有的截图会被跳过。

网络失败会保留当前进程中的待传队列并重试；重启脚本不会补传旧截图。空闲时每 5 秒兜底检查一次目录，FileObserver 不可用时改为 150 ms 扫描。脚本不持有持续唤醒锁。

## Mac 端

使用 AppKit、Network.framework 和 ImageIO，无额外运行时依赖。接收完整图片后准备 TIFF 剪贴板格式，同时提供原始 JPEG/PNG 与文件 URL。原文件直接写入缓存，不重新压缩；保留最近 `cacheLimit` 张。

闪光使用缓存的白色透明度纹理和 Core Animation，仅出现在系统主显示器上，不抢焦点或阻挡鼠标。中心完全透明。动画直接由接收完成事件触发，不扫描系统剪贴板。

只在请求处理期间申请及时处理活动，完成后释放；空闲没有剪贴板轮询。

## HTTP 接口

```http
PUT /screenshot/Screenshot_2026-01-01-12-00-00-123_com.example.app.jpg HTTP/1.1
Authorization: Basic <base64(username:password)>
Content-Type: application/octet-stream
Content-Length: <原图字节数>

<图片原始字节>
```

路径中的文件名使用 URL 编码，统一为 `Screenshot_…`，扩展名支持 `jpg/jpeg/png/webp`。

| 状态 | 含义 |
|---|---|
| `200 clipboard ready` | 图片已写入系统剪贴板 |
| `200 duplicate` | 同一文件名在本次进程中已处理 |
| `400` | 文件名或 HTTP 格式错误 |
| `401` | 配对信息不匹配 |
| `413` | 长度无效、超过 50 MB，或使用分块传输 |
| `422` | 图片未写完整或无法解码 |
| `500` | 缓存或剪贴板写入失败 |

请求必须带 `Content-Length`。单请求最多 50 MB，接收超时 20 秒。协议为带认证的明文 HTTP，适用于可信局域网，不提供传输加密。

## 开发与验证

- `node --test tests/formats.test.js`：临时名去重、图片写入完整性、Java 有符号字节。
- `python3 -m unittest discover -s tests -p 'test_*.py'`：配对、升级保留配置、配置权限。
- `python3 tests/integration.py`：在独立临时目录和随机端口启动接收器，检查认证、图片剪贴板、重试幂等和缓存保留；完成后恢复原剪贴板。

`PADPASTE_HOME` 可改变 Mac 的数据目录，用于开发和测试。程序的 `--check-config` 参数仅校验配置后退出。
