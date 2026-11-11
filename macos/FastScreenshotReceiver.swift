import AppKit
import Network
import ImageIO
import CryptoKit

final class FastScreenshotReceiver {
    private var listener: NWListener?
    private var discovery: NWListener?
    private let queue = DispatchQueue(label: "tablet.screenshot.receive", qos: .userInitiated)
    private var committed = Set<String>()
    private var trace: [[String: Any]] = []
    let onReceived: (String) -> Void
    private let configuration: AppConfiguration
    private var authorization = ""

    init(configuration: AppConfiguration, onReceived: @escaping (String) -> Void) {
        self.configuration = configuration
        self.onReceived = onReceived
        authorization = "Basic " + Data((configuration.username + ":" + configuration.password).utf8).base64EncodedString()
    }

    func start() throws {
        let service = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: configuration.port)!)
        service.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            let request = ScreenshotRequest(connection: connection, queue: self.queue,
                authorization: self.authorization) { [weak self] name, body, began, respond in
                self?.accept(name: name, body: body, began: began, respond: respond)
            }
            request.start()
        }
        service.stateUpdateHandler = { state in
            if case .failed(let error) = state {
                NSLog("PadPaste listener: %@", error.localizedDescription)
                exit(1)
            }
        }
        listener = service
        service.start(queue: queue)
        let udp = try NWListener(using: .udp, on: 5035)
        udp.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            connection.start(queue: self.queue)
            connection.receiveMessage { data, _, _, _ in
                guard let data, let request = String(data: data, encoding: .utf8),
                      request.hasPrefix("PADPASTE_DISCOVER ") else { connection.cancel(); return }
                let nonce = String(request.dropFirst(18))
                guard nonce.count == 32, nonce.allSatisfy({ $0.isHexDigit }) else { connection.cancel(); return }
                let port = self.configuration.port
                let message = "padpaste|\(nonce)|\(port)"
                let proof = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: SymmetricKey(data: Data(self.configuration.password.utf8)))
                let hex = proof.map { String(format: "%02x", $0) }.joined()
                let reply = Data("PADPASTE_FOUND \(nonce) \(port) \(hex)".utf8)
                connection.send(content: reply, completion: .contentProcessed { _ in connection.cancel() })
            }
        }
        discovery = udp
        udp.start(queue: queue)
    }

    private func accept(name: String, body: Data, began: Double, respond: @escaping (Int, String) -> Void) {
        if committed.contains(name) { respond(200, "duplicate"); return }
        let bodyDone = Date().timeIntervalSince1970 * 1000
        guard let source = CGImageSourceCreateWithData(body as CFData, nil),
              CGImageSourceGetStatus(source) == .statusComplete,
              let cgImage = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary),
              cgImage.width > 0, cgImage.height > 0 else {
            respond(422, "incomplete image"); return
        }
        let extensionName = (name as NSString).pathExtension.lowercased()
        let bitmap = NSBitmapImageRep(cgImage: cgImage)
        guard let tiff = bitmap.tiffRepresentation else { respond(422, "invalid image"); return }
        let prepared = Date().timeIntervalSince1970 * 1000
        DispatchQueue.main.async { [weak self] in
            guard let self else { respond(503, "stopped"); return }
            let item = NSPasteboardItem()
            item.setData(tiff, forType: .tiff)
            if ["jpg", "jpeg"].contains(extensionName) {
                item.setData(body, forType: NSPasteboard.PasteboardType("public.jpeg"))
            } else if extensionName == "png" {
                item.setData(body, forType: .png)
            }
            let pb = NSPasteboard.general
            pb.clearContents()
            guard pb.writeObjects([item]) else { respond(500, "clipboard write failed"); return }
            let published = Date().timeIntervalSince1970 * 1000
            self.onReceived(name)
            self.queue.async {
                if self.committed.count >= 1000 { self.committed.removeAll(keepingCapacity: true) }
                self.committed.insert(name)
                self.trace.append(["name": name, "bytes": body.count, "connectionMs": began,
                    "bodyDoneMs": bodyDone, "imageReadyMs": prepared, "clipboardMs": published,
                    "receiveMs": bodyDone - began, "decodeMs": prepared - bodyDone,
                    "mainQueueAndClipboardMs": published - prepared])
                self.trace = Array(self.trace.suffix(30))
                let traceURL = AppConfiguration.dataDirectory.appendingPathComponent("latency-trace.json")
                if let data = try? JSONSerialization.data(withJSONObject: self.trace, options: .sortedKeys) { try? data.write(to: traceURL, options: .atomic) }
                respond(200, "clipboard ready")
            }
        }
    }


}

private final class ScreenshotRequest {
    let connection: NWConnection
    let queue: DispatchQueue
    let authorization: String
    let accept: (String, Data, Double, @escaping (Int, String) -> Void) -> Void
    var buffer = Data()
    var name: String?
    var length: Int?
    let began = Date().timeIntervalSince1970 * 1000
    var done = false
    var timeout: DispatchWorkItem?
    var activity: NSObjectProtocol?

    init(connection: NWConnection, queue: DispatchQueue, authorization: String,
         accept: @escaping (String, Data, Double, @escaping (Int, String) -> Void) -> Void) {
        self.connection = connection; self.queue = queue; self.authorization = authorization; self.accept = accept
    }
    deinit { if let activity { ProcessInfo.processInfo.endActivity(activity) } }
    func endActivity() {
        if let token=activity { ProcessInfo.processInfo.endActivity(token);activity=nil }
    }
    func start() {
        activity=ProcessInfo.processInfo.beginActivity(options:.userInitiatedAllowingIdleSystemSleep,reason:"Receive tablet screenshot")
        connection.start(queue: queue)
        let deadline = DispatchWorkItem { [weak self] in self?.respond(408, "timeout") }
        timeout = deadline
        queue.asyncAfter(deadline: .now() + 20, execute: deadline)
        read()
    }
    func read() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 131072) { [self] data, _, complete, error in
            guard !done else { return }
            if let data { buffer.append(data) }
            if length == nil {
                if let split = buffer.range(of: Data([13,10,13,10])) {
                    guard split.lowerBound <= 8192,
                          let header = String(data: buffer[..<split.lowerBound], encoding: .utf8) else { respond(400, "bad header"); return }
                    let lines = header.components(separatedBy: "\r\n")
                    let request = lines[0].split(separator: " ")
                    guard request.count == 3 else { respond(400, "bad request"); return }
                    var headers: [String:String] = [:]
                    for line in lines.dropFirst() {
                        if let colon=line.firstIndex(of: ":") {
                            headers[String(line[..<colon]).lowercased()] = String(line[line.index(after:colon)...]).trimmingCharacters(in:.whitespaces)
                        }
                    }
                    guard headers["authorization"] == authorization else { respond(401, "unauthorized"); return }
                    if request[0] == "GET" && request[1] == "/health" { respond(200, "PadPaste ready"); return }
                    guard request[0] == "PUT" else { respond(405, "PUT required"); return }
                    guard headers["transfer-encoding"] == nil,
                          let size = Int(headers["content-length"] ?? ""), size > 0, size <= 50*1024*1024 else { respond(413, "invalid size"); return }
                    let path = String(request[1])
                    guard path.hasPrefix("/screenshot/"), let filename=String(path.dropFirst(12)).removingPercentEncoding,
                          filename.range(of: "^Screenshot_[A-Za-z0-9._-]{1,220}\\.(jpg|jpeg|png|webp)$", options: .regularExpression) != nil else { respond(400, "invalid filename"); return }
                    name=filename;length=size;buffer.removeSubrange(..<split.upperBound)
                } else if buffer.count > 8192 { respond(431, "header too large"); return }
            }
            if let size=length, let filename=name, buffer.count >= size {
                guard buffer.count == size else { respond(400, "invalid body"); return }
                timeout?.cancel()
                accept(filename, buffer, began) { [self] status, message in respond(status, message) }
            } else if complete || error != nil {
                done=true;timeout?.cancel();endActivity();connection.cancel()
            } else { read() }
        }
    }
    func respond(_ status: Int, _ message: String) {
        guard !done else { return }
        done=true;timeout?.cancel();endActivity()
        let body=Data(message.utf8)
        let header=Data("HTTP/1.1 \(status) \(status == 200 ? "OK" : "Error")\r\nContent-Type: text/plain\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n".utf8)
        connection.send(content: header+body, completion: .contentProcessed { [connection] _ in connection.cancel() })
    }
}
