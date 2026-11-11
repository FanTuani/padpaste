# 工作原理与接口

```text
新截图临时写入 → Android FileObserver → 完整性检查
             → 单次 HTTP 原图上传 → Mac 图像剪贴板 → 主屏边缘闪光
             → 无论成功或失败，清理平板截图；失败时平板弹出提示
```

## 原生 Android 客户端

使用 Java、系统 View、FileObserver、HandlerThread 和 SQLite，没有脚本引擎或第三方运行时依赖。

前台服务每次创建都会重建文件监听；返回 `START_STICKY`，系统重新创建服务时也会恢复。界面重新打开、开机和升级后，按用户此前的启用状态恢复服务。系统明确停止应用或厂商限制后台启动时，仍遵守系统行为。

`CREATE/MODIFY/CLOSE_WRITE/MOVED_TO` 触发工作线程处理，不依赖 JavaScript 回调或心跳。关闭写入时立即检查；其余事件短暂等待文件稳定。每 5 秒的目录扫描仅作漏事件兜底，不持有持续唤醒锁。

小米 `.pending-…-Screenshot_….jpg` 与正式文件名归一为同一张截图。仅在内存中短暂等待系统写入完成，每张截图只进行一次网络传输。无论成功或失败，均直接删除平板临时文件并刷新媒体索引；失败显示文字 Toast。没有离线队列或自动重传。

SQLite 只记录文件名、路径和处理状态，不保存图像内容。服务重建时清理已登记但中断的截图，不重新发送。升级到此模式时从新的时间基线开始，保留此前的个人截图。

连接检测使用认证请求。App 界面可见时每 20 秒检测一次；后台仅在网络变化和传图时更新连接状态。密码与配置保存在应用私有存储中，不写入公共目录。

## 地址自动发现

连接失败时，Android 在局域网 UDP 5035 端口广播随机 nonce。Mac 用配对密码对 nonce 与接收端口计算 HMAC-SHA256；平板验证后缓存响应来源 IP。广播不包含密码。该流程在发送图片前完成，图片 PUT 仍只有一次，不保存失败图片或重传。

## Mac 接收器

使用 AppKit、Network.framework 和 ImageIO。上传内容在内存中解码，不保存接收文件、不重新压缩。准备 TIFF 图像格式与原始 JPEG/PNG 字节，直接写入系统剪贴板后返回成功；不提供依赖磁盘文件的 URL 格式。

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
| `500` | 剪贴板写入失败 |

单请求最多 50 MB，要求 `Content-Length`，接收超时 20 秒。协议为带认证的明文 HTTP，适用于可信局域网，不提供传输加密。

## 构建与测试

```sh
bash scripts/build-macos.sh
python3 tests/integration.py
cd android-native && ./gradlew assembleDebug testDebugUnitTest lintDebug
```

Mac 集成测试在独立目录和随机端口运行，结束后恢复原剪贴板。`PADPASTE_HOME` 可改变测试数据目录。Android 单元测试覆盖临时文件去重与图片写入完整性。

旧 AutoJs6 客户端保留在 `android/`，其使用与诊断方法见历史版本文档。
