import AppKit

final class TransferActivity {
    private static let wifiDelay: TimeInterval = 0.5
    private static let expandedWidth: CGFloat = 69
    private let item: NSStatusItem
    private let originalIcon: NSImage?
    private let badge = TransferBadge()
    private var direction: TransferDirection?
    private var delayedAppearance: DispatchWorkItem?
    private var animation: Timer?
    private var visibleSince: TimeInterval?
    private var exitAt: TimeInterval?
    private var outcome: Bool?
    private var value: Double = 0
    private var target: Double = 0
    private var from: Double = 0
    private var progressAt: TimeInterval = 0

    init(item: NSStatusItem) {
        self.item = item
        originalIcon = item.button?.image
        if let button = item.button {
            badge.frame = button.bounds
            badge.autoresizingMask = [.width, .height]
            badge.isHidden = true
            button.addSubview(badge)
        }
    }

    func update(_ direction: TransferDirection, _ change: TransferUpdate) {
        let now = ProcessInfo.processInfo.systemUptime
        switch change {
        case .started(let bluetooth):
            hide()
            self.direction = direction
            badge.direction = direction
            value = 0
            target = 0
            from = 0
            progressAt = now
            if bluetooth {
                reveal()
            } else {
                let task = DispatchWorkItem { [weak self] in self?.reveal() }
                delayedAppearance = task
                DispatchQueue.main.asyncAfter(deadline: .now() + Self.wifiDelay, execute: task)
            }
        case .progress(let fraction):
            guard self.direction == direction, outcome == nil else { return }
            adjustTarget(fraction, now: now)
            if visibleSince != nil { animate() }
        case .finished(let success):
            guard self.direction == direction, outcome == nil else { return }
            guard let visibleSince else {
                hide()
                return
            }
            outcome = success
            if success { adjustTarget(1, now: now) }
            exitAt = max(now + 0.18, visibleSince + 0.36)
            animate()
        }
    }

    private func reveal() {
        delayedAppearance = nil
        guard let direction, visibleSince == nil else { return }
        visibleSince = ProcessInfo.processInfo.systemUptime
        item.length = Self.expandedWidth
        item.button?.image = nil
        let label = direction == .sending ? "发送题图到平板" : "接收平板截图"
        item.button?.setAccessibilityLabel(label)
        badge.isHidden = false
        animate()
    }

    private func hide() {
        delayedAppearance?.cancel()
        delayedAppearance = nil
        animation?.invalidate()
        animation = nil
        visibleSince = nil
        exitAt = nil
        outcome = nil
        direction = nil
        badge.isHidden = true
        item.button?.image = originalIcon
        item.button?.setAccessibilityLabel("PadPaste 题图")
        item.length = NSStatusItem.squareLength
    }

    private func adjustTarget(_ fraction: Double, now: TimeInterval) {
        let fraction = min(1, max(target, fraction))
        guard fraction != target else { return }
        value = displayedProgress(now)
        from = value
        target = fraction
        progressAt = now
    }

    private func displayedProgress(_ now: TimeInterval) -> Double {
        let t = min(1, (now - progressAt) / 0.16)
        return from + (target - from) * (1 - pow(1 - t, 3))
    }

    private func animate() {
        if animation == nil {
            let timer = Timer(timeInterval: 1 / 60, repeats: true) { [weak self] _ in self?.tick() }
            animation = timer
            RunLoop.main.add(timer, forMode: .common)
        }
        tick()
    }

    private func tick() {
        guard let shown = visibleSince else { return }
        let now = ProcessInfo.processInfo.systemUptime
        let entering = min(1, (now - shown) / 0.24)
        let tail = entering - 1
        let spring = 1 + 2.2 * tail * tail * tail + 1.2 * tail * tail
        let leaving = max(0, (now - (exitAt ?? now)) / 0.22)
        if leaving >= 1 { hide(); return }
        let exitEase = leaving * leaving
        value = displayedProgress(now)
        badge.update(fraction: value, outcome: outcome,
                     opacity: (1 - pow(1 - entering, 3)) * (1 - exitEase),
                     scale: (0.76 + 0.24 * spring) * (1 - 0.15 * exitEase))
        if entering == 1, exitAt == nil, value == target {
            animation?.invalidate()
            animation = nil
        }
    }
}

private final class TransferBadge: NSView {
    var direction: TransferDirection = .sending
    private var fraction = 0.0
    private var outcome: Bool?
    private var opacity = 0.0
    private var scale = 1.0

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    func update(fraction: Double, outcome: Bool?, opacity: Double, scale: Double) {
        self.fraction = fraction
        self.outcome = outcome
        self.opacity = opacity
        self.scale = scale
        needsDisplay = true
    }

    override func draw(_ dirtyRect: NSRect) {
        guard let context = NSGraphicsContext.current?.cgContext else { return }
        context.saveGState()
        defer { context.restoreGState() }
        context.setAlpha(opacity)
        context.translateBy(x: bounds.midX, y: bounds.midY)
        context.scaleBy(x: scale, y: scale)
        context.translateBy(x: -bounds.midX, y: -bounds.midY)

        let y = bounds.midY
        let tint = outcome == false ? NSColor.systemRed : NSColor.systemTeal
        NSColor.labelColor.withAlphaComponent(0.08).setFill()
        NSBezierPath(roundedRect: NSRect(x: 2, y: y - 10.5, width: bounds.width - 4, height: 21),
                     xRadius: 10.5, yRadius: 10.5).fill()
        let symbol = outcome.map { $0 ? "checkmark" : "xmark" }
            ?? (direction == .sending ? "arrow.up" : "arrow.down")
        let config = NSImage.SymbolConfiguration(pointSize: 9, weight: .bold)
            .applying(.init(paletteColors: [tint]))
        NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?
            .withSymbolConfiguration(config)?
            .draw(in: NSRect(x: 10, y: y - 5.5, width: 11, height: 11))

        let track = NSRect(x: 27, y: y - 2, width: 32, height: 4)
        NSColor.labelColor.withAlphaComponent(0.17).setFill()
        NSBezierPath(roundedRect: track, xRadius: 2, yRadius: 2).fill()
        if fraction > 0 {
            tint.setFill()
            let fill = NSRect(x: track.minX, y: track.minY, width: track.width * fraction, height: track.height)
            NSBezierPath(roundedRect: fill, xRadius: 2, yRadius: 2).fill()
        }
    }
}
