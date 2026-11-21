import AppKit
import CryptoKit
import Darwin
import ImageIO
import Network
import SystemConfiguration
import UniformTypeIdentifiers

final class QuestionLink {
    static let port: UInt16 = 5036
    static let byteLimit = 6 * 1024 * 1024
    private let keyData: Data
    private let key: SymmetricKey
    private let listener: NWListener
    private let queue = DispatchQueue(label: "padpaste.questions")
    private var tablet: QuestionPeer?
    var statusChanged: ((Bool) -> Void)?
    var receiveScreenshot: ((String, Data, @escaping (Bool) -> Void) -> Void)?

    init() throws {
        let path = AppConfiguration.dataDirectory.appendingPathComponent("question.key")
        if FileManager.default.fileExists(atPath: path.path) {
            keyData = try Data(contentsOf: path)
            guard keyData.count == 32 else { throw CocoaError(.fileReadCorruptFile) }
        } else {
            keyData = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
            try keyData.write(to: path, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path.path)
        }
        key = SymmetricKey(data: keyData)
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 15
        tcp.keepaliveInterval = 5
        tcp.keepaliveCount = 3
        listener = try NWListener(using: NWParameters(tls: nil, tcp: tcp), on: NWEndpoint.Port(rawValue: Self.port)!)
        listener.service = .init(name: serviceName, type: "_padquestion._tcp")
    }

    private var serviceName: String {
        "PadPaste-" + SHA256.hash(data: keyData).prefix(6).map { String(format: "%02x", $0) }.joined()
    }

    func pairingCode() -> String {
        let host = (SCDynamicStoreCopyLocalHostName(nil) as String?).map { $0 + ".local" } ?? ""
        var addresses: [String] = []
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&interfaces) == 0 {
            defer { freeifaddrs(interfaces) }
            var current = interfaces
            while let pointer = current {
                let interface = pointer.pointee
                current = interface.ifa_next
                guard let address = interface.ifa_addr, address.pointee.sa_family == AF_INET,
                      interface.ifa_flags & UInt32(IFF_UP) != 0,
                      interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count),
                               nil, 0, NI_NUMERICHOST) == 0 {
                    let value = String(cString: host)
                    if !addresses.contains(value) { addresses.append(value) }
                }
            }
        }
        let body: [String: Any] = [
            "host": host, "addresses": addresses, "key": keyData.base64EncodedString()
        ]
        return (try? JSONSerialization.data(withJSONObject: body))?.base64EncodedString() ?? ""
    }

    func start() {
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            let peer = QuestionPeer(connection, key: self.key, queue: self.queue)
            peer.start(lifecycle: { [weak self, weak peer] active in
                guard let self, let peer else { return }
                if active {
                    let previous = self.tablet
                    self.tablet = peer
                    if previous !== peer { previous?.close() }
                } else if self.tablet === peer {
                    self.tablet = nil
                } else { return }
                self.publishAvailability()
            }, availability: { [weak self, weak peer] in
                guard let self, let peer, self.tablet === peer else { return }
                self.publishAvailability()
            }, receive: receiveScreenshot)
        }
        listener.stateUpdateHandler = { state in
            if case .failed(let error) = state { NSLog("PadPaste questions: %@", error.localizedDescription) }
        }
        listener.start(queue: queue)
    }

    private func publishAvailability() {
        let available = tablet?.acceptsQuestions == true
        DispatchQueue.main.async { self.statusChanged?(available) }
    }

    func send(_ image: Data, completion: @escaping (Bool) -> Void) {
        queue.async {
            let finish: (Bool) -> Void = { ok in DispatchQueue.main.async { completion(ok) } }
            guard let tablet = self.tablet, tablet.acceptsQuestions else { finish(false); return }
            tablet.send(image, finish: finish)
        }
    }

    static func prepare(_ data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 2400,
                kCGImageSourceCreateThumbnailWithTransform: true
              ] as CFDictionary) else { return nil }
        return encode(image, type: .png, options: nil)
            ?? encode(image, type: .jpeg, options: [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
    }

    private static func encode(_ image: CGImage, type: UTType, options: CFDictionary?) -> Data? {
        let portable = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(portable, type.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, options)
        guard CGImageDestinationFinalize(destination), portable.length <= byteLimit
        else { return nil }
        return portable as Data
    }
}

private final class QuestionPeer {
    private let connection: NWConnection
    private let key: SymmetricKey
    private let queue: DispatchQueue
    private static let version = Data("PADSYNC2".utf8)
    private static let maxCaptureFrame = 50 * 1024 * 1024 + 285
    private var context = Data()
    private var duplex = false
    private var heartbeat: DispatchSourceTimer?
    private var availabilityChanged: (() -> Void)?
    private var receiveScreenshot: ((String, Data, @escaping (Bool) -> Void) -> Void)?
    private var receivingCapture = false
    private(set) var acceptsQuestions = false
    private var notice: ((Bool) -> Void)?
    private var awaiting: ((Bool) -> Void)?
    private var deadline: DispatchWorkItem?
    private var expectedAck = Data()
    private var closed = false

    init(_ connection: NWConnection, key: SymmetricKey, queue: DispatchQueue) {
        self.connection = connection
        self.key = key
        self.queue = queue
    }

    func start(lifecycle: @escaping (Bool) -> Void, availability: @escaping () -> Void,
               receive: ((String, Data, @escaping (Bool) -> Void) -> Void)?) {
        notice = lifecycle
        availabilityChanged = availability
        receiveScreenshot = receive
        // Retain the peer until close, including while the handshake is in progress.
        connection.stateUpdateHandler = { state in
            switch state {
            case .failed, .cancelled: self.close()
            default: break
            }
        }
        connection.start(queue: queue)
        timeOut()
        read(32) { [weak self] clientNonce in
            guard let self else { return }
            self.duplex = clientNonce.starts(with: Self.version)
            var serverNonce = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
            if self.duplex { serverNonce.replaceSubrange(..<Self.version.count, with: Self.version) }
            self.context = clientNonce + serverNonce
            self.write(serverNonce + self.proof("server"))
            self.read(32) { answer in
                guard HMAC<SHA256>.isValidAuthenticationCode(answer, authenticating: self.message("tablet"),
                                                             using: self.key) else { self.close(); return }
                self.deadline?.cancel()
                self.acceptsQuestions = !self.duplex
                self.notice?(true)
                if self.duplex {
                    self.startHeartbeat()
                    self.readFrame()
                } else {
                    self.readAcknowledgement()
                }
            }
        }
    }

    func send(_ image: Data, finish: @escaping (Bool) -> Void) {
        guard !closed, awaiting == nil, image.count <= QuestionLink.byteLimit else { finish(false); return }
        if duplex {
            awaiting = finish
            timeOut()
            writeFrame(1, image)
            return
        }
        guard let box = try? AES.GCM.seal(image, using: key, authenticating: message("image")),
              let payload = box.combined else { finish(false); return }
        var length = UInt32(payload.count).bigEndian
        awaiting = finish
        expectedAck = box.tag
        timeOut()
        write(withUnsafeBytes(of: &length) { Data($0) } + payload)
    }

    private func startHeartbeat() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 8, repeating: 8, leeway: .seconds(1))
        timer.setEventHandler { [weak self] in self?.writeFrame(5) }
        heartbeat = timer
        timer.resume()
    }

    private func writeFrame(_ kind: UInt8, _ content: Data = Data()) {
        var message = Data([kind])
        message.append(content)
        guard let payload = try? AES.GCM.seal(message, using: key, authenticating: self.message("mac-frame")).combined
        else { close(); return }
        var size = UInt32(payload.count).bigEndian
        write(withUnsafeBytes(of: &size) { Data($0) } + payload)
    }

    private func readFrame() {
        read(4) { [weak self] header in
            guard let self else { return }
            let length = header.reduce(0) { ($0 << 8) | Int($1) }
            guard length > 28, length <= Self.maxCaptureFrame else { self.close(); return }
            self.read(length) { [weak self] payload in
                guard let self else { return }
                guard let box = try? AES.GCM.SealedBox(combined: payload),
                      let frame = try? AES.GCM.open(box, using: self.key, authenticating: self.message("tablet-frame")),
                      let kind = frame.first else { self.close(); return }
                self.handleFrame(kind, frame.dropFirst())
                if !self.closed { self.readFrame() }
            }
        }
    }

    private func handleFrame(_ kind: UInt8, _ content: Data) {
        switch kind {
        case 2:
            guard !receivingCapture, let count = content.first, count > 0,
                  content.count > 1 + Int(count), content.count - 1 - Int(count) <= 50 * 1024 * 1024,
                  let filename = String(data: content.dropFirst().prefix(Int(count)), encoding: .utf8),
                  let receiveScreenshot else { writeFrame(4, Data([0])); return }
            receivingCapture = true
            receiveScreenshot(filename, content.dropFirst(1 + Int(count))) { [weak self] ok in
                guard let self else { return }
                self.queue.async {
                    guard !self.closed else { return }
                    self.receivingCapture = false
                    self.writeFrame(4, Data([ok ? 1 : 0]))
                }
            }
        case 3:
            guard content.count == 1, let finish = awaiting else { close(); return }
            awaiting = nil
            deadline?.cancel()
            finish(content.first == 1)
        case 6:
            guard content.count == 1 else { close(); return }
            let available = content.first == 1
            if acceptsQuestions != available {
                acceptsQuestions = available
                availabilityChanged?()
            }
        default:
            close()
        }
    }

    private func readAcknowledgement() {
        read(32) { [weak self] ack in
            guard let self else { return }
            guard let finish = self.awaiting,
                  HMAC<SHA256>.isValidAuthenticationCode(ack,
                    authenticating: self.message("accepted") + self.expectedAck, using: self.key)
            else { self.close(); return }
            self.awaiting = nil
            self.deadline?.cancel()
            finish(true)
            self.readAcknowledgement()
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        heartbeat?.cancel()
        heartbeat = nil
        deadline?.cancel()
        connection.stateUpdateHandler = nil
        connection.cancel()
        awaiting?(false)
        awaiting = nil
        notice?(false)
        notice = nil
    }

    private func message(_ purpose: String) -> Data { Data(("padpaste-question-1/" + purpose).utf8) + context }
    private func proof(_ purpose: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message(purpose), using: key))
    }

    private func timeOut() {
        deadline?.cancel()
        let task = DispatchWorkItem { [weak self] in self?.close() }
        deadline = task
        queue.asyncAfter(deadline: .now() + 7, execute: task)
    }

    private func write(_ data: Data) {
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            if error != nil { self?.close() }
        })
    }

    private func read(_ count: Int, _ received: @escaping (Data) -> Void) {
        connection.receive(minimumIncompleteLength: count, maximumLength: count) { [weak self] data, _, _, error in
            guard let self, !self.closed else { return }
            if let data, data.count == count, error == nil { received(data) }
            else { self.close() }
        }
    }
}
