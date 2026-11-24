import AppKit
import CryptoKit
import Darwin
import ImageIO
import Network
import SystemConfiguration
import UniformTypeIdentifiers

enum TransferDirection { case sending, receiving }

enum TransferUpdate {
    case started(bluetooth: Bool)
    case progress(Double)
    case finished(Bool)
}

final class QuestionLink {
    static let port: UInt16 = 5036
    static let byteLimit = 6 * 1024 * 1024
    private let keyData: Data
    private let key: SymmetricKey
    private let listener: NWListener
    private let queue = DispatchQueue(label: "padpaste.questions")
    private var tablet: QuestionPeer?
    private final class OutboundQuestion {
        let source: Data
        let id = withUnsafeBytes(of: UUID().uuid) { Data($0) }
        let finish: (Bool) -> Void
        var peer: QuestionPeer?
        var recovery: DispatchWorkItem?
        init(_ source: Data, finish: @escaping (Bool) -> Void) {
            self.source = source
            self.finish = finish
        }
    }
    private var outgoing: OutboundQuestion?
    private var bluetooth: BluetoothLink?
    var statusChanged: ((Bool, String?) -> Void)?
    var transferChanged: ((TransferDirection, TransferUpdate) -> Void)?
    var bluetoothStatus: ((String) -> Void)?
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
            self.accept(.network(connection))
        }
        listener.stateUpdateHandler = { state in
            if case .failed(let error) = state { NSLog("PadPaste questions: %@", error.localizedDescription) }
        }
        listener.start(queue: queue)
    }

    func setBluetoothEnabled(_ enabled: Bool) {
        if enabled {
            guard bluetooth == nil else { return }
            bluetooth = BluetoothLink(identity: Data(SHA256.hash(data: keyData)), opened: { [weak self] stream in
                guard let self else { stream.stop(); return }
                self.queue.async { self.accept(.bluetooth(stream)) }
            }, status: { [weak self] status in self?.bluetoothStatus?(status) })
        } else {
            bluetooth?.stop()
            bluetooth = nil
            bluetoothStatus?("蓝牙备用 · 已关闭")
        }
    }

    private func accept(_ transport: QuestionPeer.Transport) {
        let peer = QuestionPeer(transport, key: key, queue: queue)
        peer.start(lifecycle: { [weak self, weak peer] active in
            guard let self, let peer else { return }
            if active {
                let previous = self.tablet
                if previous?.hasIncomingTransfer == true { self.publishTransfer(.receiving, .finished(false)) }
                self.tablet = peer
                if previous !== peer { previous?.close() }
            } else if self.tablet === peer {
                self.tablet = nil
            } else { return }
            self.publishAvailability()
            self.sendOnCurrentRoute()
        }, availability: { [weak self, weak peer] in
            guard let self, let peer, self.tablet === peer else { return }
            self.publishAvailability()
            self.sendOnCurrentRoute()
        }, receive: receiveScreenshot, progress: { [weak self, weak peer] direction, update in
            guard let self, let peer, self.tablet === peer else { return }
            self.publishTransfer(direction, update)
        })
    }

    private func publishTransfer(_ direction: TransferDirection, _ update: TransferUpdate) {
        DispatchQueue.main.async { self.transferChanged?(direction, update) }
    }

    private func publishAvailability() {
        let available = tablet?.acceptsQuestions == true
        let route = tablet.map { $0.bluetooth ? "蓝牙" : "Wi-Fi" }
        DispatchQueue.main.async { self.statusChanged?(available, route) }
    }

    func send(_ source: Data, completion: @escaping (Bool) -> Void) {
        queue.async {
            guard self.outgoing == nil, self.tablet?.acceptsQuestions == true else {
                DispatchQueue.main.async { completion(false) }
                return
            }
            self.outgoing = OutboundQuestion(source) { ok in DispatchQueue.main.async { completion(ok) } }
            self.sendOnCurrentRoute()
        }
    }

    private func sendOnCurrentRoute() {
        guard let question = outgoing, let peer = tablet, peer.acceptsQuestions, question.peer !== peer else { return }
        if let previous = question.peer, previous.bluetooth == peer.bluetooth {
            complete(question, false)
            return
        }
        question.recovery?.cancel()
        question.peer = peer
        let bluetooth = peer.bluetooth
        DispatchQueue.global(qos: .userInitiated).async { [self, question, peer] in
            let image = Self.prepare(question.source, bluetooth: bluetooth)
            self.queue.async { [self, question, peer] in
                guard self.outgoing === question, question.peer === peer else { return }
                guard self.tablet === peer, peer.acceptsQuestions else {
                    self.awaitRoute(question)
                    return
                }
                guard let image else { self.complete(question, false); return }
                peer.send(image, id: question.id) { [weak self, weak question, weak peer] ok in
                    guard let self, let question, let peer, self.outgoing === question,
                          question.peer === peer else { return }
                    if !ok && peer.isClosed {
                        self.awaitRoute(question)
                    } else {
                        self.complete(question, ok)
                    }
                }
            }
        }
    }

    private func awaitRoute(_ question: OutboundQuestion) {
        question.recovery?.cancel()
        let timeout = DispatchWorkItem { [weak self, weak question] in
            if let self, let question { self.complete(question, false) }
        }
        question.recovery = timeout
        queue.asyncAfter(deadline: .now() + 16, execute: timeout)
        sendOnCurrentRoute()
    }

    private func complete(_ question: OutboundQuestion, _ ok: Bool) {
        guard outgoing === question else { return }
        question.recovery?.cancel()
        outgoing = nil
        publishTransfer(.sending, .finished(ok))
        question.finish(ok)
    }

    static func prepare(_ data: Data, bluetooth: Bool = false) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: bluetooth ? 1280 : 2400,
                kCGImageSourceCreateThumbnailWithTransform: true
              ] as CFDictionary) else { return nil }
        let jpeg = [kCGImageDestinationLossyCompressionQuality: bluetooth ? 0.75 : 0.9] as CFDictionary
        if bluetooth { return encode(image, type: .jpeg, options: jpeg) }
        return encode(image, type: .png, options: nil) ?? encode(image, type: .jpeg, options: jpeg)
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
    enum Transport {
        case network(NWConnection)
        case bluetooth(BluetoothStream)
    }

    private let transport: Transport
    var bluetooth: Bool {
        if case .bluetooth = transport { return true }
        return false
    }
    private let key: SymmetricKey
    private let queue: DispatchQueue
    private static let version = Data("PADSYNC2".utf8)
    private static let transferFeature = Data("XFERLOCK".utf8)
    private static let chunkFeature = Data("XFERPRT3".utf8)
    private static let captureChunk: UInt8 = 16
    private static let questionChunk: UInt8 = 17
    private static let cancelTransfer: UInt8 = 18
    private static let cancelAck: UInt8 = 19
    private static let liveFeature = Data("XFERLIVE".utf8)
    private static let captureProgress: UInt8 = 13
    private static let captureReady: UInt8 = 14
    private static let captureCommit: UInt8 = 15
    private static let questionStart: UInt8 = 9
    private static let questionPermission: UInt8 = 10
    private static let captureStart: UInt8 = 11
    private static let transferConflict: UInt8 = 12
    private static let maxCaptureFrame = 50 * 1024 * 1024 + 285
    private var context = Data()
    private var duplex = false
    private var transferLock = false
    private var liveProgress = false
    private var chunked = false
    private var questionGeneration = 0
    private var heartbeat: DispatchSourceTimer?
    private var availabilityChanged: (() -> Void)?
    private var receiveScreenshot: ((String, Data, @escaping (Bool) -> Void) -> Void)?
    private var progressChanged: ((TransferDirection, TransferUpdate) -> Void)?
    var hasIncomingTransfer: Bool { captureAnnounced || receivingCapture }
    private var receivingCapture = false
    private var captureAnnounced = false
    private var captureLength = 0
    private var capturePayload = Data()
    private var lastProgressReport: TimeInterval = 0
    private var lastQuestionReport: TimeInterval = 0
    private var commitCapture: (() -> Void)?
    private var pendingQuestion: Data?
    private var switchingToNetwork = false
    private(set) var acceptsQuestions = false
    private var notice: ((Bool) -> Void)?
    private var awaiting: ((Bool) -> Void)?
    private var deadline: DispatchWorkItem?
    private var expectedAck = Data()
    private var closed = false
    var isClosed: Bool { closed }

    init(_ transport: Transport, key: SymmetricKey, queue: DispatchQueue) {
        self.transport = transport
        self.key = key
        self.queue = queue
    }

    func start(lifecycle: @escaping (Bool) -> Void, availability: @escaping () -> Void,
               receive: ((String, Data, @escaping (Bool) -> Void) -> Void)?,
               progress: @escaping (TransferDirection, TransferUpdate) -> Void) {
        notice = lifecycle
        progressChanged = progress
        availabilityChanged = availability
        receiveScreenshot = receive
        // Keep the session alive until the transport closes, including the handshake.
        switch transport {
        case .network(let connection):
            connection.stateUpdateHandler = { state in
                switch state {
                case .failed, .cancelled: self.close()
                default: break
                }
            }
            connection.start(queue: queue)
        case .bluetooth(let stream):
            DispatchQueue.main.async { stream.start { self.queue.async { self.close() } } }
        }
        timeOut()
        read(32) { [weak self] clientNonce in
            guard let self else { return }
            self.duplex = clientNonce.starts(with: Self.version)
            var serverNonce = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
            if self.duplex {
                serverNonce.replaceSubrange(..<Self.version.count, with: Self.version)
                let capability = Self.version.count..<(Self.version.count + Self.transferFeature.count)
                self.transferLock = clientNonce[capability] == Self.transferFeature
                if self.transferLock {
                    serverNonce.replaceSubrange(capability, with: Self.transferFeature)
                    let progress = capability.upperBound..<(capability.upperBound + Self.liveFeature.count)
                    self.chunked = clientNonce[progress] == Self.chunkFeature
                    self.liveProgress = self.chunked || clientNonce[progress] == Self.liveFeature
                    if self.liveProgress {
                        serverNonce.replaceSubrange(progress, with: self.chunked ? Self.chunkFeature : Self.liveFeature)
                    }
                }
            }
            self.context = clientNonce + serverNonce
            self.write(serverNonce + self.proof("server"))
            self.read(32) { answer in
                guard HMAC<SHA256>.isValidAuthenticationCode(answer, authenticating: self.message("tablet"),
                                                             using: self.key) else { self.close(); return }
                self.deadline?.cancel()
                if self.bluetooth { NSLog("PadPaste BLE peer authenticated") }
                self.acceptsQuestions = !self.duplex
                self.notice?(true)
                guard !self.closed else { return }
                if self.duplex {
                    self.startHeartbeat()
                    self.readFrame()
                } else {
                    self.readAcknowledgement()
                }
            }
        }
    }

    func send(_ image: Data, id: Data, finish: @escaping (Bool) -> Void) {
        guard !closed, !switchingToNetwork, awaiting == nil, image.count <= QuestionLink.byteLimit else { finish(false); return }
        if duplex {
            if transferLock && (captureAnnounced || receivingCapture) {
                writeFrame(Self.transferConflict)
                finish(false)
                return
            }
            awaiting = finish
            if transferLock {
                pendingQuestion = image
                var count = UInt32(image.count).bigEndian
                timeOut()
                var request = withUnsafeBytes(of: &count) { Data($0) }
                if chunked { request.append(id) }
                writeFrame(Self.questionStart, request)
            } else {
                sendQuestion(image)
            }
            return
        }
        guard let box = try? AES.GCM.seal(image, using: key, authenticating: message("image")),
              let payload = box.combined else { finish(false); return }
        var length = UInt32(payload.count).bigEndian
        awaiting = finish
        expectedAck = box.tag
        timeOut()
        progressChanged?(.sending, .started(bluetooth: bluetooth))
        write(withUnsafeBytes(of: &length) { Data($0) } + payload) { [weak self] in
            self?.progressChanged?(.sending, .progress(0.94))
        }
    }

    private func sendQuestion(_ image: Data) {
        deadline?.cancel()
        progressChanged?(.sending, .started(bluetooth: bluetooth))
        lastQuestionReport = 0
        if chunked {
            questionGeneration += 1
            sendQuestionChunk(image, from: 0, generation: questionGeneration)
            return
        }
        if !bluetooth { timeOut() }
        writeFrame(1, image) { [weak self] in
            guard let self, self.awaiting != nil else { return }
            self.progressChanged?(.sending, .progress(0.94))
            if self.bluetooth { self.timeOut() }
        }
    }

    private func sendQuestionChunk(_ image: Data, from offset: Int, generation: Int) {
        guard !closed, awaiting != nil, generation == questionGeneration else { return }
        if offset == image.count {
            timeOut(seconds: bluetooth ? 15 : 7)
            return
        }
        let end = min(image.count, offset + (bluetooth ? 8192 : 65536))
        let chunk = image.dropFirst(offset).prefix(end - offset)
        writeFrame(Self.questionChunk, chunk) { [weak self] in
            guard let self, generation == self.questionGeneration, self.awaiting != nil else { return }
            let now = ProcessInfo.processInfo.systemUptime
            if end == image.count || now - self.lastQuestionReport >= 0.08 {
                self.lastQuestionReport = now
                self.progressChanged?(.sending, .progress(0.94 * Double(end) / Double(image.count)))
            }
            self.sendQuestionChunk(image, from: end, generation: generation)
        }
    }

    private func finishQuestion(_ accepted: Bool) {
        guard let finish = awaiting else { close(); return }
        pendingQuestion = nil
        awaiting = nil
        questionGeneration += 1
        deadline?.cancel()
        finish(accepted)
    }

    private func startHeartbeat() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 8, repeating: 8, leeway: .seconds(1))
        timer.setEventHandler { [weak self] in self?.writeFrame(5) }
        heartbeat = timer
        timer.resume()
    }

    private func writeFrame(_ kind: UInt8, _ content: Data = Data(), finish: @escaping () -> Void = {}) {
        var message = Data(capacity: 1 + content.count)
        message.append(kind)
        message.append(content)
        guard let payload = try? AES.GCM.seal(message, using: key, authenticating: self.message("mac-frame")).combined
        else { close(); return }
        var size = UInt32(payload.count).bigEndian
        write(withUnsafeBytes(of: &size) { Data($0) } + payload, finish: finish)
    }

    private func readFrame() {
        read(4) { [weak self] header in
            guard let self else { return }
            let length = header.reduce(0) { ($0 << 8) | Int($1) }
            let limit = self.bluetooth ? QuestionLink.byteLimit + 285 : Self.maxCaptureFrame
            guard length > 28, length <= limit else { self.close(); return }
            if self.liveProgress && !self.chunked && self.captureAnnounced && length == self.captureLength {
                self.capturePayload = Data(capacity: length)
                self.lastProgressReport = 0
                self.readCapture(length)
            } else {
                self.read(length) { [weak self] payload in self?.receiveFrame(payload) }
            }
        }
    }

    private func readCapture(_ length: Int) {
        let chunk = bluetooth ? 8192 : 65536
        read(min(chunk, length - capturePayload.count)) { [weak self] data in
            guard let self else { return }
            self.capturePayload.append(data)
            if self.capturePayload.count == length {
                let payload = self.capturePayload
                self.capturePayload = Data()
                self.receiveFrame(payload)
                return
            }
            self.reportCaptureProgress()
            self.readCapture(length)
        }
    }

    private func reportCaptureProgress() {
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastProgressReport >= 0.16 else { return }
        lastProgressReport = now
        let size = captureLength - (chunked ? 29 : 0)
        if size > 0 { progressChanged?(.receiving, .progress(0.94 * Double(capturePayload.count) / Double(size))) }
        var confirmed = UInt32(capturePayload.count).bigEndian
        writeFrame(Self.captureProgress, withUnsafeBytes(of: &confirmed) { Data($0) })
    }

    private func receiveFrame(_ payload: Data) {
        guard let box = try? AES.GCM.SealedBox(combined: payload),
              let frame = try? AES.GCM.open(box, using: key, authenticating: message("tablet-frame")),
              let kind = frame.first else { close(); return }
        handleFrame(kind, frame.dropFirst())
        if !closed { readFrame() }
    }

    private func handleFrame(_ kind: UInt8, _ content: Data) {
        switch kind {
        case Self.questionPermission:
            guard transferLock, let image = pendingQuestion, content.count == 1 else { close(); return }
            if content.first == 1 {
                guard !captureAnnounced && !receivingCapture else { close(); return }
                pendingQuestion = nil
                sendQuestion(image)
            } else {
                finishQuestion(chunked && content.first == 2)
            }
        case Self.captureStart:
            guard transferLock, content.count == (liveProgress ? 4 : 0),
                  !captureAnnounced, !receivingCapture,
                  awaiting == nil || pendingQuestion != nil else { close(); return }
            if liveProgress {
                let length = content.reduce(0) { ($0 << 8) | Int($1) }
                let limit = bluetooth ? QuestionLink.byteLimit + 285 : Self.maxCaptureFrame
                guard length >= 32, length <= limit else { close(); return }
                captureLength = length
                if chunked {
                    capturePayload = Data(capacity: length - 29)
                    lastProgressReport = 0
                }
            }
            captureAnnounced = true
            progressChanged?(.receiving, .started(bluetooth: bluetooth))
            // A refusal queued behind this upload can arrive after the usual handshake deadline.
            if pendingQuestion != nil { deadline?.cancel() }
        case Self.captureChunk:
            guard chunked, captureAnnounced, !receivingCapture, !content.isEmpty,
                  capturePayload.count + content.count <= captureLength - 29 else { close(); return }
            capturePayload.append(content)
            if capturePayload.count == captureLength - 29 {
                let image = capturePayload
                capturePayload = Data()
                stageCapture(image)
            } else {
                reportCaptureProgress()
            }
        case Self.cancelTransfer:
            guard chunked, content.count == 1, let direction = content.first else { close(); return }
            if direction == 2 {
                if hasIncomingTransfer { progressChanged?(.receiving, .finished(false)) }
                capturePayload = Data()
                captureAnnounced = false
                receivingCapture = false
                captureLength = 0
                commitCapture = nil
                if pendingQuestion != nil { timeOut() } else { deadline?.cancel() }
            } else if direction == 1 {
                if awaiting != nil { finishQuestion(false) }
            } else { close(); return }
            writeFrame(Self.cancelAck, Data([direction]))
        case 2:
            guard !chunked else { close(); return }
            stageCapture(content)
        case Self.captureCommit:
            guard liveProgress, content.isEmpty, let commit = commitCapture else { close(); return }
            commitCapture = nil
            deadline?.cancel()
            commit()
        case 3:
            guard content.count == 1, pendingQuestion == nil else { close(); return }
            finishQuestion(content.first == 1)
        case 5 where bluetooth:
            if !content.isEmpty { close() }
        case 7 where bluetooth:
            guard content.isEmpty, !switchingToNetwork else { close(); return }
            switchingToNetwork = awaiting == nil && !receivingCapture && !captureAnnounced
            if switchingToNetwork {
                acceptsQuestions = false
                availabilityChanged?()
                timeOut()
            }
            writeFrame(8, Data([switchingToNetwork ? 1 : 0]))
        case 6:
            guard !switchingToNetwork else { return }
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

    private func stageCapture(_ content: Data) {
        guard (!transferLock || captureAnnounced), !receivingCapture,
              (!liveProgress || content.count + 29 == captureLength) else { close(); return }
        guard let count = content.first, count > 0,
              content.count > 1 + Int(count), content.count - 1 - Int(count) <= 50 * 1024 * 1024,
              let filename = String(data: content.dropFirst().prefix(Int(count)), encoding: .utf8),
              let receiveScreenshot else { finishCapture(false); return }
        if !captureAnnounced { progressChanged?(.receiving, .started(bluetooth: bluetooth)) }
        receivingCapture = true
        progressChanged?(.receiving, .progress(0.94))
        let body = content.dropFirst(1 + Int(count))
        let commit = { [weak self] in
            receiveScreenshot(filename, body) { [weak self] ok in
                guard let self else { return }
                self.queue.async { if !self.closed { self.finishCapture(ok) } }
            }
        }
        if liveProgress {
            commitCapture = commit
            writeFrame(Self.captureReady)
            timeOut(seconds: bluetooth ? 24 : 12)
        } else {
            commit()
        }
    }

    private func finishCapture(_ accepted: Bool) {
        if hasIncomingTransfer { progressChanged?(.receiving, .finished(accepted)) }
        receivingCapture = false
        captureAnnounced = false
        captureLength = 0
        writeFrame(4, Data([accepted ? 1 : 0]))
        if pendingQuestion != nil { timeOut() }
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
        if hasIncomingTransfer { progressChanged?(.receiving, .finished(false)) }
        heartbeat?.cancel()
        heartbeat = nil
        deadline?.cancel()
        switch transport {
        case .network(let connection):
            connection.stateUpdateHandler = nil
            connection.cancel()
        case .bluetooth(let stream):
            DispatchQueue.main.async { stream.stop() }
        }
        awaiting?(false)
        awaiting = nil
        pendingQuestion = nil
        commitCapture = nil
        capturePayload = Data()
        notice?(false)
        notice = nil
    }

    private func message(_ purpose: String) -> Data { Data(("padpaste-question-1/" + purpose).utf8) + context }
    private func proof(_ purpose: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message(purpose), using: key))
    }

    private func timeOut(seconds: Double = 7) {
        deadline?.cancel()
        let task = DispatchWorkItem { [weak self] in self?.close() }
        deadline = task
        queue.asyncAfter(deadline: .now() + seconds, execute: task)
    }

    private func write(_ data: Data, finish: @escaping () -> Void = {}) {
        switch transport {
        case .network(let connection):
            connection.send(content: data, completion: .contentProcessed { [weak self] error in
                if error != nil { self?.close() }
                else { finish() }
            })
        case .bluetooth(let stream):
            DispatchQueue.main.async { stream.write(data) { self.queue.async { finish() } } }
        }
    }

    private func read(_ count: Int, _ received: @escaping (Data) -> Void) {
        switch transport {
        case .network(let connection):
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { [weak self] data, _, _, error in
                guard let self, !self.closed else { return }
                if let data, data.count == count, error == nil { received(data) }
                else { self.close() }
            }
        case .bluetooth(let stream):
            DispatchQueue.main.async { [weak self] in
                stream.read(count) { [weak self] data in
                    self?.queue.async { [weak self] in
                        if self?.closed == false { received(data) }
                    }
                }
            }
        }
    }
}
