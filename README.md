# PadPaste

平板三指下滑截图，Mac 直接 `⌘V` 粘贴。

同一 Wi-Fi 下，AutoJs6 监听新截图，Mac 原生接收程序写入图片剪贴板，并在**主屏边缘闪白 0.3 秒**。保留原图，不需要云服务、数据线或日常 ADB 连接。

- 事件触发，空闲不持续轮询剪贴板。
- 小米临时截图与正式文件自动去重。
- 失败重试、登录启动、最近 20 张接收缓存。
- 仅支持 **Apple 芯片 Mac**；平板端使用 AutoJs6，无需 Root。

## 开始使用

Mac 需要 macOS 12+、Python 3；从源码构建还需要 Xcode Command Line Tools。

```sh
git clone https://github.com/FanTuani/padpaste.git
cd padpaste
bash scripts/install-macos.sh
```

也可以下载 [Release](https://github.com/FanTuani/padpaste/releases/latest) 中的 Apple 芯片安装包，解压后运行同一安装命令。

安装器会生成 `~/Library/Application Support/PadPaste/android-config.json`。

1. 平板安装 [AutoJs6](https://github.com/SuperMonster003/AutoJs6/releases)。
2. 将生成的配对文件复制到平板 `/sdcard/PadPaste/config.json`。
3. 将仓库 `android/` 内两个脚本放到 `/sdcard/Scripts/PadPaste/`。
4. 给 AutoJs6 文件访问、后台自启动和省电「无限制」权限，运行 `PadPaste.js`。

随后在平板截图，在 Mac 粘贴即可。

[安装与配置](docs/setup.md) · [工作原理与接口](docs/architecture.md) · [故障排查](docs/troubleshooting.md)

## 开发

```sh
bash scripts/build-macos.sh
node --test tests/formats.test.js
python3 -m unittest discover -s tests -p 'test_*.py'
python3 tests/integration.py
```

MIT License。依赖与致谢见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
