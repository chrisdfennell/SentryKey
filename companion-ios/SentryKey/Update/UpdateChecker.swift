import Foundation
import Combine

/// Polls GitHub Releases for a newer version.
///
/// NOTE: iOS apps cannot download and install another app or self-update outside
/// the App Store / TestFlight — there is no API for it. So this is **notify-only**:
/// it surfaces a banner linking to the release. For real over-the-air test builds,
/// use TestFlight.
final class UpdateChecker: ObservableObject {
    static let shared = UpdateChecker()

    /// Bump to the shipped release tag (or wire to Bundle version) so the check
    /// knows what "current" is.
    private let currentTag = "v1.0.0-beta.14"
    private let releasesAPI = URL(string: "https://api.github.com/repos/chrisdfennell/SentryKey/releases/latest")!

    /// TEMP 30s polling for testing. Note GitHub's unauthenticated limit is
    /// 60 req/hour, so this throttles after ~30 minutes.
    private let pollInterval: TimeInterval = 30

    @Published var newerTag: String?
    @Published var releaseURL: URL?

    private var timer: Timer?

    func start() {
        guard timer == nil else { return }
        check()
        timer = Timer.scheduledTimer(withTimeInterval: pollInterval, repeats: true) { [weak self] _ in
            self?.check()
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func check() {
        var request = URLRequest(url: releasesAPI)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: request) { [weak self] data, _, _ in
            guard let self,
                  let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tag = json["tag_name"] as? String,
                  tag != self.currentTag else { return }

            let url = (json["html_url"] as? String).flatMap(URL.init)
            DispatchQueue.main.async {
                self.newerTag = tag
                self.releaseURL = url
            }
        }.resume()
    }
}
