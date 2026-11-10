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
    private var panel: NSPanel!
    private var configuration: AppConfiguration!
    private var receiver: FastScreenshotReceiver?
    private var finishTask: DispatchWorkItem?
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        do { configuration = try AppConfiguration.load() }
        catch { fputs("PadPaste: \(error.localizedDescription)\n", stderr); exit(1) }
        if CommandLine.arguments.contains("--check-config") { print("Configuration valid"); exit(0) }
        makePanel()
        let nativeReceiver = FastScreenshotReceiver(configuration: configuration) { [weak self] name in
            guard let self else { return }
            self.show()
        }
        do { try nativeReceiver.start(); receiver = nativeReceiver }
        catch { fputs("PadPaste receiver: \(error.localizedDescription)\n", stderr); exit(1) }
        DistributedNotificationCenter.default().addObserver(self, selector: #selector(preview),
            name: Notification.Name("io.github.fantuani.padpaste.preview"), object: nil)
        if CommandLine.arguments.contains("--preview") { show() }
    }

    private func makePanel() {
        panel = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
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
        if let screen = NSScreen.screens.first(where: { ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == CGMainDisplayID() }) {
            panel.setFrame(screen.frame, display: false)
            let glow = EdgeGlowView(frame: NSRect(origin: .zero, size: screen.frame.size))
            glow.updateLayerImage()
            panel.contentView = glow
        } else {
            panel.contentView = EdgeGlowView(frame: .zero)
        }
    }

    @objc private func preview() { show() }

    private func show() {
        guard configuration.flashEnabled else { return }
        let primaryID = CGMainDisplayID()
        guard let screen = NSScreen.screens.first(where: {
            ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == primaryID
        }) else { return }
        finishTask?.cancel()
        panel.setFrame(screen.frame, display: false)
        (panel.contentView as? EdgeGlowView)?.updateLayerImage()
        panel.contentView?.needsDisplay = true
        guard let layer = panel.contentView?.layer else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.opacity = 0
        CATransaction.commit()
        panel.alphaValue = 1
        panel.orderFrontRegardless()
        let flash = CAKeyframeAnimation(keyPath: "opacity")
        flash.values = [0, 1, 0]
        flash.keyTimes = [0, 0.16, 1]
        flash.timingFunctions = [CAMediaTimingFunction(name: .easeOut), CAMediaTimingFunction(name: .easeInEaseOut)]
        flash.duration = configuration.flashDuration
        layer.add(flash, forKey: "screenshotEdgeFlash")
        let finish = DispatchWorkItem { [weak self] in self?.panel.orderOut(nil) }
        finishTask = finish
        DispatchQueue.main.asyncAfter(deadline: .now() + configuration.flashDuration, execute: finish)
    }
}

let app = NSApplication.shared
let delegate = ScreenshotHUD()
app.delegate = delegate
app.run()
