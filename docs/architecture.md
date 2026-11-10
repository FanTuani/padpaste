# 工作原理与接口

```text
新截图写入 → Android FileObserver → 完整性检查与去重
         → HTTP 原图上传 → Mac 图像剪贴板 → 主屏边缘闪光
```

## 原生 Android 客户端

使用 Java、系统 View、FileObserver、HandlerThread 和 SQLite，没有脚本引擎或第三方运行时依赖。

前台服务每次创建都会重建文件监听；返回 `START_STICKY`，系统重新创建服务时也会恢复。界面重新打开、开机和升级后，按用户此前的启用状态恢复服务。系统明确停止应用或厂商限制后台启动时，仍遵守系统行为。

`CREATE/MODIFY/CLOSE_WRITE/MOVED_TO` 触发工作线程处理，不依赖 JavaScript 回调或心跳。关闭写入时立即检查；其余事件短暂等待文件稳定。每 5 秒的目录扫描仅作漏事件兜底，不持有持续唤醒锁。

小米 `.pending-…-Screenshot_….jpg` 与正式文件名归一为同一个任务。待传任务、重试时间和已发送记录保存在 SQLite 中；进程重建后仍可恢复，网络失败时按 1–30 秒退避。首次开启前和手动暂停期间的旧截图不会批量导入。

连接检测使用认证请求。App 界面可见时每 20 秒检测一次；后台仅在网络变化和传图时更新连接状态。密码与配置保存在应用私有存储中，不写入公共目录。

## Mac 接收器

使用 AppKit、Network.framework 和 ImageIO。原文件写入缓存，不重新压缩。准备 TIFF 图像格式，以及原始 JPEG/PNG 和文件 URL，写入系统剪贴板后才返回成功。

边缘闪光是缓存纹理上的 0.3 秒 Core Animation，仅显示在系统主屏，中心透明，不抢焦点或阻挡鼠标。接收完成直接触发，不轮询剪贴板。

## HTTP 接口

所有请求使用 HTTP Basic 配对认证。

```http
GET /health HTTP/1.1
Authorization: Basic <base64(username:password)>
```

成功返回 `200 PadPaste ready`。不改动剪贴板。原生 App 对 v0.1.x 接收器提供兼容检测。

```http
PUT /screenshot/Screenshot_2026-01-01-12-00-00-123_com.example.app.jpg HTTP/1.1
Authorization: Basic <base64(username:password)>
Content-Type: application/octet-stream
Content-Length: <原图字节数>

<图片原始字节>
```

文件名按 URL 编码，归一为 `Screenshot_…`，扩展名支持 `jpg/jpeg/png/webp`。

| 响应 | 含义 |
|---|---|
| `200 clipboard ready` | 已写入图像剪贴板 |
| `200 duplicate` | 本进程已接收同名任务 |
| `400` | 文件名或请求格式错误 |
| `401` | 配对信息不匹配 |
| `413` | 长度无效、超过 50 MB 或使用分块传输 |
| `422` | 图片不完整或无法解码 |
| `500` | 缓存或剪贴板写入失败 |

单请求最多 50 MB，要求 `Content-Length`，接收超时 20 秒。协议为带认证的明文 HTTP，适用于可信局域网，不提供传输加密。

## 构建与测试

```sh
bash scripts/build-macos.sh
python3 tests/integration.py
cd android-native && ./gradlew assembleDebug testDebugUnitTest lintDebug
```

Mac 集成测试在独立目录和随机端口运行，结束后恢复原剪贴板。`PADPASTE_HOME` 可改变测试数据目录。Android 单元测试覆盖临时文件去重与图片写入完整性。

旧 AutoJs6 客户端保留在 `android/`，其使用与诊断方法见历史版本文档。
