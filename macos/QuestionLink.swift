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
            peer.start { [weak self, weak peer] active in
                guard let self, let peer else { return }
                if active {
                    if self.tablet !== peer { self.tablet?.close(); self.tablet = peer }
                } else if self.tablet === peer {
                    self.tablet = nil
                } else { return }
                let connected = self.tablet != nil
                DispatchQueue.main.async { self.statusChanged?(connected) }
            }
        }
        listener.stateUpdateHandler = { state in
            if case .failed(let error) = state { NSLog("PadPaste questions: %@", error.localizedDescription) }
        }
        listener.start(queue: queue)
    }

    func send(_ image: Data, completion: @escaping (Bool) -> Void) {
        queue.async {
            let finish: (Bool) -> Void = { ok in DispatchQueue.main.async { completion(ok) } }
            guard let tablet = self.tablet else { finish(false); return }
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
    private var context = Data()
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

    func start(_ notice: @escaping (Bool) -> Void) {
        self.notice = notice
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
            let serverNonce = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
            self.context = clientNonce + serverNonce
            self.write(serverNonce + self.proof("server"))
            self.read(32) { answer in
                guard HMAC<SHA256>.isValidAuthenticationCode(answer, authenticating: self.message("tablet"),
                                                             using: self.key) else { self.close(); return }
                self.deadline?.cancel()
                self.notice?(true)
                self.readAcknowledgement()
            }
        }
    }

    func send(_ image: Data, finish: @escaping (Bool) -> Void) {
        guard !closed, awaiting == nil, image.count <= QuestionLink.byteLimit,
              let box = try? AES.GCM.seal(image, using: key, authenticating: message("image")),
              let payload = box.combined else { finish(false); return }
        var length = UInt32(payload.count).bigEndian
        awaiting = finish
        expectedAck = box.tag
        timeOut()
        write(withUnsafeBytes(of: &length) { Data($0) } + payload)
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
