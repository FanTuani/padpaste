import CoreBluetooth
import AppKit

// CoreBluetooth and its streams run on the main run loop. The session callbacks run on the sync queue.
final class BluetoothLink: NSObject, CBPeripheralManagerDelegate {
    private static let channelID = CBUUID(string: "D79D0B38-622A-4FEC-A9DC-BA8916A20C73")
    private let serviceID: CBUUID
    private let opened: (BluetoothStream) -> Void
    private let status: (String) -> Void
    private var manager: CBPeripheralManager?
    private var powerObservers: [NSObjectProtocol] = []
    private var sleeping = false
    private var stopped = false
    private var publishing = false
    private var interrupted = false
    private var retiring = false
    private var cleanupDeadline: DispatchWorkItem?
    private var retry: DispatchWorkItem?
    private var retryDelay: TimeInterval = 2
    private var characteristic: CBMutableCharacteristic?
    private var psm: CBL2CAPPSM?
    private var streams: [ObjectIdentifier: BluetoothStream] = [:]

    init(identity: Data, opened: @escaping (BluetoothStream) -> Void, status: @escaping (String) -> Void) {
        let bytes = [UInt8](identity.prefix(16))
        serviceID = CBUUID(nsuuid: UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15])))
        self.opened = opened
        self.status = status
        super.init()
        let center = NSWorkspace.shared.notificationCenter
        powerObservers = [
            center.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
                self?.willSleep()
            },
            center.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
                self?.didWake()
            }
        ]
        startManagerIfNeeded()
    }

    private func startManagerIfNeeded() {
        guard !sleeping, !stopped, retry == nil, manager == nil else { return }
        manager = CBPeripheralManager(delegate: self, queue: nil)
    }

    private func willSleep() {
        sleeping = true
        retry?.cancel()
        retry = nil
        // If CoreBluetooth reported power loss first, keep the PSM for cleanup when power returns.
        if manager?.state == .poweredOn { retireManager() }
    }

    private func didWake() {
        sleeping = false
        startManagerIfNeeded()
    }

    func stop() {
        stopped = true
        let center = NSWorkspace.shared.notificationCenter
        powerObservers.forEach { center.removeObserver($0) }
        powerObservers.removeAll()
        retry?.cancel()
        retry = nil
        retireManager()
    }

    private func retireManager() {
        guard let manager, !retiring else { return }
        retiring = true
        closeStreams()
        characteristic = nil
        if manager.state == .poweredOn {
            manager.stopAdvertising()
            manager.removeAllServices()
            if publishing || psm != nil {
                // Keep the delegate alive for a late publish/unpublish acknowledgement, even after stop().
                let deadline = DispatchWorkItem { [self] in
                    NSLog("PadPaste BLE cleanup timed out (PSM %u)", psm ?? 0)
                    finishRetirement()
                }
                cleanupDeadline = deadline
                DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: deadline)
                if let psm { manager.unpublishL2CAPChannel(psm) }
                return
            }
        }
        finishRetirement()
    }

    private func finishRetirement() {
        cleanupDeadline?.cancel()
        cleanupDeadline = nil
        manager?.delegate = nil
        manager = nil
        publishing = false
        retiring = false
        interrupted = false
        psm = nil
        startManagerIfNeeded()
    }

    private func retrySetup(_ message: String, error: Error?) {
        status(message)
        NSLog("PadPaste BLE setup: %@ (%@)", message, error?.localizedDescription ?? "no channel")
        guard retry == nil else { return }
        let work = DispatchWorkItem { [weak self] in
            self?.retry = nil
            self?.startManagerIfNeeded()
        }
        retry = work
        DispatchQueue.main.asyncAfter(deadline: .now() + retryDelay, execute: work)
        retryDelay = min(60, retryDelay * 2)
        retireManager()
    }

    private func closeStreams() {
        let active = streams.values
        streams.removeAll()
        active.forEach { $0.stop() }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard manager === peripheral, !retiring else { return }
        switch peripheral.state {
        case .poweredOn:
            if interrupted || sleeping {
                retireManager()
                return
            }
            guard !publishing, psm == nil else { return }
            publishing = true
            status("蓝牙备用 · 正在启动")
            peripheral.publishL2CAPChannel(withEncryption: true)
        case .unauthorized: status("蓝牙备用 · 需要授权")
        case .poweredOff: status("蓝牙备用 · 蓝牙已关闭")
        case .unsupported: status("蓝牙备用 · 此 Mac 不支持")
        default: status("蓝牙备用 · 等待蓝牙")
        }
        if peripheral.state != .poweredOn {
            interrupted = interrupted || publishing || psm != nil
            closeStreams()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel psm: CBL2CAPPSM, error: Error?) {
        guard peripheral === manager, publishing else { return }
        publishing = false
        if error == nil && psm != 0 { self.psm = psm }
        if retiring {
            if let channel = self.psm, peripheral.state == .poweredOn {
                peripheral.unpublishL2CAPChannel(channel)
            } else {
                finishRetirement()
            }
            return
        }
        guard peripheral.state == .poweredOn else { return }
        if interrupted || sleeping {
            retireManager()
            return
        }
        guard error == nil, psm != 0 else {
            retrySetup("蓝牙备用 · 无法开启数据通道", error: error)
            return
        }
        NSLog("PadPaste BLE published PSM %u", psm)
        let characteristic = CBMutableCharacteristic(type: Self.channelID, properties: [.read],
                                                       value: nil, permissions: [.readable])
        self.characteristic = characteristic
        let service = CBMutableService(type: serviceID, primary: true)
        service.characteristics = [characteristic]
        peripheral.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didUnpublishL2CAPChannel psm: CBL2CAPPSM, error: Error?) {
        guard peripheral === manager, retiring, self.psm == psm else { return }
        if let error { NSLog("PadPaste BLE unpublish PSM %u: %@", psm, error.localizedDescription) }
        else { NSLog("PadPaste BLE unpublished PSM %u", psm) }
        finishRetirement()
    }

    private func serving(_ peripheral: CBPeripheralManager) -> Bool {
        peripheral === manager && peripheral.state == .poweredOn && !sleeping && !interrupted && !retiring && psm != nil
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard serving(peripheral) else { return }
        if let error { retrySetup("蓝牙备用 · 无法注册服务", error: error); return }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceID]])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        guard serving(peripheral) else { return }
        if let error { retrySetup("蓝牙备用 · 无法广播", error: error); return }
        retryDelay = 2
        status("蓝牙备用 · 可连接")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard peripheral.state == .poweredOn else { return }
        guard serving(peripheral), request.characteristic.uuid == characteristic?.uuid, let psm else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        let value = Data([UInt8(psm >> 8), UInt8(truncatingIfNeeded: psm)])
        guard request.offset <= value.count else { peripheral.respond(to: request, withResult: .invalidOffset); return }
        request.value = value.dropFirst(request.offset)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard error == nil, let channel else { return }
        guard serving(peripheral), streams.count < 4 else {
            channel.inputStream.close()
            channel.outputStream.close()
            return
        }
        let link = BluetoothStream(channel)
        let identity = ObjectIdentifier(link)
        streams[identity] = link
        link.onClose = { [weak self] in self?.streams.removeValue(forKey: identity) }
        NSLog("PadPaste BLE channel opened")
        opened(link)
    }
}

final class BluetoothStream: NSObject, StreamDelegate {
    private struct Read {
        var data = Data()
        let count: Int
        let finish: (Data) -> Void
    }

    private struct Write {
        let data: Data
        var offset = 0
        let finish: () -> Void
    }

    private let channel: CBL2CAPChannel
    private var failure: (() -> Void)?
    private var pendingRead: Read?
    private var writes: [Write] = []
    private var lastRead = ProcessInfo.processInfo.systemUptime
    private var lastWrite = ProcessInfo.processInfo.systemUptime
    private var readBuffer = [UInt8](repeating: 0, count: 16_384)
    private var watch: Timer?
    private var closed = false
    var onClose: (() -> Void)?

    init(_ channel: CBL2CAPChannel) { self.channel = channel }

    func start(failure: @escaping () -> Void) {
        guard !closed else { failure(); return }
        self.failure = failure
        for stream in [channel.inputStream as Stream, channel.outputStream as Stream] {
            stream.delegate = self
            stream.schedule(in: .main, forMode: .common)
            stream.open()
        }
        let watch = Timer(timeInterval: 4, repeats: true) { [weak self] _ in
            guard let self else { return }
            let now = ProcessInfo.processInfo.systemUptime
            if (self.pendingRead != nil && now - self.lastRead >= 24)
                || (!self.writes.isEmpty && now - self.lastWrite >= 24) { self.stop() }
        }
        self.watch = watch
        RunLoop.main.add(watch, forMode: .common)
    }

    func read(_ count: Int, finish: @escaping (Data) -> Void) {
        guard !closed else { return }
        lastRead = ProcessInfo.processInfo.systemUptime
        pendingRead = Read(count: count, finish: finish)
        readAvailable()
    }

    func write(_ data: Data, finish: @escaping () -> Void) {
        guard !closed else { return }
        if writes.isEmpty { lastWrite = ProcessInfo.processInfo.systemUptime }
        writes.append(Write(data: data, finish: finish))
        writeAvailable()
    }

    func stream(_ stream: Stream, handle event: Stream.Event) {
        switch event {
        case .hasBytesAvailable: readAvailable()
        case .hasSpaceAvailable: writeAvailable()
        case .errorOccurred, .endEncountered:
            NSLog("PadPaste BLE stream ended: %@", stream.streamError?.localizedDescription ?? "EOF")
            stop()
        default: break
        }
    }

    private func readAvailable() {
        while let count = pendingRead?.count, let received = pendingRead?.data.count,
              channel.inputStream.hasBytesAvailable {
            let limit = min(readBuffer.count, count - received)
            let size = channel.inputStream.read(&readBuffer, maxLength: limit)
            guard size > 0 else { stop(); return }
            lastRead = ProcessInfo.processInfo.systemUptime
            pendingRead?.data.append(contentsOf: readBuffer.prefix(size))
            if received + size == count, let complete = pendingRead {
                pendingRead = nil
                complete.finish(complete.data)
            }
        }
    }

    private func writeAvailable() {
        while !writes.isEmpty, channel.outputStream.hasSpaceAvailable {
            let data = writes[0].data
            let offset = writes[0].offset
            let size = data.withUnsafeBytes { bytes in
                channel.outputStream.write(bytes.bindMemory(to: UInt8.self).baseAddress!.advanced(by: offset),
                                           maxLength: min(16_384, data.count - offset))
            }
            guard size > 0 else { if size < 0 { stop() }; return }
            lastWrite = ProcessInfo.processInfo.systemUptime
            writes[0].offset += size
            if writes[0].offset == data.count { writes.removeFirst().finish() }
        }
    }

    func stop() {
        guard !closed else { return }
        closed = true
        watch?.invalidate()
        watch = nil
        for stream in [channel.inputStream as Stream, channel.outputStream as Stream] {
            stream.delegate = nil
            stream.remove(from: .main, forMode: .common)
            stream.close()
        }
        pendingRead = nil
        writes.removeAll()
        failure?()
        failure = nil
        onClose?()
        onClose = nil
    }
}
