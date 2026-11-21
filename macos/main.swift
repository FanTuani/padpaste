import AppKit
import CoreGraphics
import QuartzCore

final class EdgeGlowView: NSView {
    private var cachedImage: CGImage?
    private var cachedSize = NSSize.zero
    override var isOpaque: Bool { false }
    override var wantsUpdateLayer: Bool { true }
    override func updateLayer() {
        prepare()
        layer?.contents = cachedImage
        layer?.contentsGravity = .resize
    }

    func prepare() {
        guard bounds.width > 0, bounds.height > 0 else { return }
        if cachedImage == nil || cachedSize != bounds.size {
            cachedSize = bounds.size
            // A single distance field avoids brighter overlaps at the corners.
            let w = Int(ceil(bounds.width)), h = Int(ceil(bounds.height))
            let fadeWidth = min(180.0, Double(min(w, h)) * 0.16)
            var pixels = [UInt8](repeating: 0, count: w * h * 4)
            for y in 0..<h {
                for x in 0..<w {
                    let distance = Double(min(x, w - 1 - x, y, h - 1 - y))
                    let t = min(1.0, distance / fadeWidth)
                    let alpha = UInt8(((1 - t * t * (3 - 2 * t)) * 0.78 * 255).rounded())
                    let i = (y * w + x) * 4
                    pixels[i] = alpha; pixels[i + 1] = alpha; pixels[i + 2] = alpha; pixels[i + 3] = alpha
                }
            }
            let data = Data(pixels) as CFData
            if let provider = CGDataProvider(data: data) {
                cachedImage = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32,
                    bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                    provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
            }
        }
    }
    func updateLayerImage() {
        prepare()
        wantsLayer = true
        layer?.contents = cachedImage
        layer?.contentsGravity = .resize
    }
}

final class ScreenshotHUD: NSObject, NSApplicationDelegate {
    private var panels: [CGDirectDisplayID: NSPanel] = [:]
    private var configuration: AppConfiguration!
    private var receiver: FastScreenshotReceiver?
    private var questions: QuestionLink?
    private var statusItem: NSStatusItem?
    private var questionStatus: NSMenuItem?
    private var feedback: NSPanel?
    private var lastClipboardChange = 0
    private var questionConnected = false
    private var sendingQuestion = false
    private var clipboardTimer: Timer?
    private var watchClipboard = !UserDefaults.standard.bool(forKey: "pauseQuestionClipboard")
    private var finishTask: DispatchWorkItem?
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        do { configuration = try AppConfiguration.load() }
        catch { fputs("PadPaste: \(error.localizedDescription)\n", stderr); exit(1) }
        if CommandLine.arguments.contains("--check-config") { print("Configuration valid"); exit(0) }
        if configuration.flashEnabled { updatePanels() }
        let nativeReceiver = FastScreenshotReceiver(configuration: configuration) { [weak self] name in
            guard let self else { return }
            self.show()
        }
        do { try nativeReceiver.start(); receiver = nativeReceiver }
        catch { fputs("PadPaste receiver: \(error.localizedDescription)\n", stderr); exit(1) }
        DistributedNotificationCenter.default().addObserver(self, selector: #selector(preview),
            name: Notification.Name("io.github.fantuani.padpaste.preview"), object: nil)
        if CommandLine.arguments.contains("--preview") { show() }
        do {
            let link = try QuestionLink()
            link.receiveScreenshot = { [weak nativeReceiver] name, data, completion in
                guard let nativeReceiver else { completion(false); return }
                nativeReceiver.receive(name: name, body: data, completion: completion)
            }
            questions = link
            setupQuestionMenu()
            lastClipboardChange = NSPasteboard.general.changeCount
            let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in self?.checkClipboard() }
            RunLoop.main.add(timer, forMode: .common)
            clipboardTimer = timer
            link.statusChanged = { [weak self] connected in
                self?.questionConnected = connected
                self?.questionStatus?.title = connected ? "草稿纸已连接" : "等待草稿纸连接"
            }
            link.start()
        } catch {
            NSLog("PadPaste questions: %@", error.localizedDescription)
        }
    }

    private func setupQuestionMenu() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName: "rectangle.and.pencil.and.ellipsis",
                                    accessibilityDescription: "PadPaste 题图")
        if item.button?.image == nil {
            item.button?.image = NSImage(systemSymbolName: "rectangle.and.pencil", accessibilityDescription: "PadPaste 题图")
        }
        let menu = NSMenu()
        let status = menu.addItem(withTitle: "等待草稿纸连接", action: nil, keyEquivalent: "")
        questionStatus = status
        let clipboard = menu.addItem(withTitle: "监听剪贴板图片", action: #selector(toggleClipboard), keyEquivalent: "")
        clipboard.target = self
        clipboard.state = watchClipboard ? .on : .off
        menu.addItem(withTitle: "复制同步配对码", action: #selector(copyQuestionPairing), keyEquivalent: "").target = self
        menu.addItem(withTitle: "导出同步配对文件…", action: #selector(saveQuestionPairing), keyEquivalent: "").target = self
        item.menu = menu
        statusItem = item
    }

    @objc private func copyQuestionPairing() {
        guard let code = questions?.pairingCode() else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(code, forType: .string)
        showFeedback("已复制配对码")
    }

    @objc private func saveQuestionPairing() {
        guard let code = questions?.pairingCode(), !code.isEmpty else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "PadPaste-同步配对.txt"
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try Data(code.utf8).write(to: url, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            showFeedback("已导出同步配对文件")
        } catch {
            showFeedback("无法保存配对文件")
        }
    }

    @objc private func toggleClipboard(_ item: NSMenuItem) {
        watchClipboard.toggle()
        item.state = watchClipboard ? .on : .off
        lastClipboardChange = NSPasteboard.general.changeCount
        UserDefaults.standard.set(!watchClipboard, forKey: "pauseQuestionClipboard")
    }

    private func checkClipboard() {
        guard watchClipboard, questionConnected, !sendingQuestion, let questions else { return }
        let board = NSPasteboard.general
        let change = board.changeCount
        guard change != lastClipboardChange else { return }
        lastClipboardChange = change
        guard let item = board.pasteboardItems?.first,
              !item.types.contains(FastScreenshotReceiver.tabletOrigin) else { return }
        let formats: [NSPasteboard.PasteboardType] = [.png, .tiff, .init("public.jpeg"), .init("public.heic")]
        guard let type = formats.first(where: { item.types.contains($0) }),
              let data = item.data(forType: type) else { return }
        sendingQuestion = true
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let image = QuestionLink.prepare(data)
            DispatchQueue.main.async {
                guard let self else { return }
                guard let image, self.watchClipboard, self.questionConnected else {
                    self.sendingQuestion = false
                    return
                }
                questions.send(image) { [weak self] ok in
                    self?.sendingQuestion = false
                    if !ok { self?.showFeedback("题图发送失败，重新复制图片可重试") }
                }
            }
        }
    }

    private func showFeedback(_ message: String) {
        guard let screen = NSScreen.screens.first else { return }
        feedback?.orderOut(nil)
        let label = NSTextField(labelWithString: message)
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.textColor = .white
        label.sizeToFit()
        let frame = NSRect(x: screen.visibleFrame.midX - (label.frame.width + 28) / 2,
                          y: screen.visibleFrame.minY + 64, width: label.frame.width + 28, height: 38)
        let panel = NSPanel(contentRect: frame, styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
        panel.isReleasedWhenClosed = false
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.level = .statusBar
        panel.hidesOnDeactivate = false
        panel.ignoresMouseEvents = true
        let background = NSView(frame: NSRect(origin: .zero, size: frame.size))
        background.wantsLayer = true
        background.layer?.backgroundColor = NSColor.black.withAlphaComponent(0.85).cgColor
        background.layer?.cornerRadius = 12
        label.frame.origin = NSPoint(x: 14, y: (38 - label.frame.height) / 2)
        background.addSubview(label)
        panel.contentView = background
        panel.orderFrontRegardless()
        feedback = panel
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.4) { [weak panel] in panel?.orderOut(nil) }
    }

    private func displayID(for screen: NSScreen) -> CGDirectDisplayID? {
        (screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value
    }

    private func updatePanels() {
        var current: [CGDirectDisplayID: NSPanel] = [:]
        for screen in NSScreen.screens {
            guard let id = displayID(for: screen) else { continue }
            if let panel = panels.removeValue(forKey: id) {
                if panel.frame == screen.frame {
                    current[id] = panel
                    continue
                }
                panel.orderOut(nil)
            }
            current[id] = makePanel(for: screen)
        }
        for panel in panels.values { panel.orderOut(nil) }
        panels = current
    }

    private func makePanel(for screen: NSScreen) -> NSPanel {
        let bounds = NSRect(origin: .zero, size: screen.frame.size)
        let panel = NSPanel(contentRect: bounds, styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false, screen: screen)
        panel.setFrame(screen.frame, display: false)
        panel.title = "平板截图边缘提示"
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.animationBehavior = .none
        panel.level = .screenSaver
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        panel.hidesOnDeactivate = false
        panel.ignoresMouseEvents = true
        panel.isReleasedWhenClosed = false
        let glow = EdgeGlowView(frame: bounds)
        glow.updateLayerImage()
        glow.layer?.opacity = 0
        panel.contentView = glow
        return panel
    }

    func applicationDidChangeScreenParameters(_ notification: Notification) {
        guard configuration?.flashEnabled == true else { return }
        finishTask?.cancel()
        hidePanels()
        updatePanels()
    }

    private func hidePanels() {
        for panel in panels.values {
            panel.orderOut(nil)
            panel.contentView?.layer?.removeAnimation(forKey: "screenshotEdgeFlash")
        }
    }

    @objc private func preview() { show() }

    private func show() {
        guard configuration.flashEnabled else { return }
        finishTask?.cancel()
        updatePanels()
        let flash = CAKeyframeAnimation(keyPath: "opacity")
        flash.values = [0, 1, 0]
        flash.keyTimes = [0, 0.16, 1]
        flash.timingFunctions = [CAMediaTimingFunction(name: .easeOut), CAMediaTimingFunction(name: .easeInEaseOut)]
        flash.duration = configuration.flashDuration
        CATransaction.begin()
        for (id, panel) in panels {
            panel.orderFrontRegardless()
            if let screen = panel.screen, displayID(for: screen) == id, panel.frame == screen.frame {
                panel.contentView?.layer?.add(flash, forKey: "screenshotEdgeFlash")
            } else {
                panel.orderOut(nil)
            }
        }
        CATransaction.commit()
        let finish = DispatchWorkItem { [weak self] in self?.hidePanels() }
        finishTask = finish
        DispatchQueue.main.asyncAfter(deadline: .now() + configuration.flashDuration, execute: finish)
    }
}

if CommandLine.arguments.contains("--export-question-pairing") {
    do { print(try QuestionLink().pairingCode()); exit(0) }
    catch { fputs("Unable to export pairing.\n", stderr); exit(1) }
}

let app = NSApplication.shared
let delegate = ScreenshotHUD()
app.delegate = delegate
app.run()
