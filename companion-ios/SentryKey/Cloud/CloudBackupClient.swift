import Foundation

struct CloudError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Talks to the SentryKey zero-knowledge backup server. Sends only the derived
/// authKey and the already-encrypted vault envelope — never the master password.
enum CloudBackupClient {
    /// Default backup server (overridable in the app). Matches the Android default.
    static let defaultServerURL = "https://sentrykey.app"

    struct BackupMeta: Identifiable {
        let id = UUID()
        let filename: String
        let timestamp: String
        let sizeBytes: Int
    }

    static func register(baseURL: String, username: String, authKey: String, inviteCode: String) async throws {
        _ = try await request(baseURL, "/api/auth/register", method: "POST",
                              body: ["username": username, "authKey": authKey, "inviteCode": inviteCode])
    }

    static func login(baseURL: String, username: String, authKey: String) async throws -> String {
        let json = try await request(baseURL, "/api/auth/login", method: "POST",
                                     body: ["username": username, "authKey": authKey])
        guard let token = json["token"] as? String, !token.isEmpty else {
            throw CloudError(message: "Login did not return a session token.")
        }
        return token
    }

    static func listBackups(baseURL: String, token: String) async throws -> [BackupMeta] {
        let json = try await request(baseURL, "/api/backups", method: "GET", token: token)
        let arr = (json["backups"] as? [[String: Any]]) ?? []
        return arr.compactMap { o in
            guard let filename = o["filename"] as? String else { return nil }
            return BackupMeta(
                filename: filename,
                timestamp: (o["timestamp"] as? String) ?? filename,
                sizeBytes: (o["sizeBytes"] as? Int) ?? 0
            )
        }
    }

    static func uploadBackup(baseURL: String, token: String, envelopeJson: String) async throws -> String {
        let json = try await request(baseURL, "/api/backups/upload", method: "POST", token: token, rawBody: envelopeJson)
        return (json["filename"] as? String) ?? ""
    }

    static func downloadBackup(baseURL: String, token: String, filename: String) async throws -> String {
        try await requestRaw(baseURL, "/api/backups/file/\(filename)", method: "GET", token: token)
    }

    // MARK: - HTTP

    private static func normalize(_ baseURL: String) -> String {
        var s = baseURL.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    private static func request(_ baseURL: String, _ path: String, method: String,
                                token: String? = nil, body: [String: Any]? = nil,
                                rawBody: String? = nil) async throws -> [String: Any] {
        let payload: String?
        if let body = body, let data = try? JSONSerialization.data(withJSONObject: body) {
            payload = String(decoding: data, as: UTF8.self)
        } else {
            payload = rawBody
        }
        let text = try await requestRaw(baseURL, path, method: method, token: token, payload: payload)
        if text.isEmpty { return [:] }
        return (try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]) ?? [:]
    }

    private static func requestRaw(_ baseURL: String, _ path: String, method: String,
                                   token: String? = nil, payload: String? = nil) async throws -> String {
        guard let url = URL(string: normalize(baseURL) + path) else {
            throw CloudError(message: "Invalid server URL.")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 30
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = token { req.setValue(token, forHTTPHeaderField: "X-Session-Token") }
        if let payload = payload {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = Data(payload.utf8)
        }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw CloudError(message: "Couldn't reach the server. Check the URL and your connection.")
        }
        let text = String(decoding: data, as: UTF8.self)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let serverMsg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw CloudError(message: serverMsg ?? "Server error (\(code)).")
        }
        return text
    }
}
