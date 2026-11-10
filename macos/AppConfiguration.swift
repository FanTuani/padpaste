import Foundation

struct AppConfiguration: Decodable {
    let username: String
    let password: String
    let port: UInt16
    let flashDuration: Double
    let flashEnabled: Bool

    static var dataDirectory: URL {
        if let directory = ProcessInfo.processInfo.environment["PADPASTE_HOME"] {
            return URL(fileURLWithPath: directory, isDirectory: true)
        }
        return FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/PadPaste")
    }

    enum CodingKeys: String, CodingKey {
        case username, password, port, flashDuration, flashEnabled
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        username = try values.decode(String.self, forKey: .username)
        password = try values.decode(String.self, forKey: .password)
        port = try values.decodeIfPresent(UInt16.self, forKey: .port) ?? 5034
        flashDuration = try values.decodeIfPresent(Double.self, forKey: .flashDuration) ?? 0.3
        flashEnabled = try values.decodeIfPresent(Bool.self, forKey: .flashEnabled) ?? true
        guard !username.isEmpty, !username.contains(":"), !password.isEmpty, port > 0,
              flashDuration > 0, flashDuration <= 5 else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Invalid PadPaste configuration"))
        }
    }

    static func load() throws -> AppConfiguration {
        try JSONDecoder().decode(AppConfiguration.self, from: Data(contentsOf: dataDirectory.appendingPathComponent("config.json")))
    }
}
