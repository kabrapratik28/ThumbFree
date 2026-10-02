import Foundation
import TFCore

/// `thumbfree://dictate?take=<uuid>`: the keyboard opens the app with it when no session is live, and the app starts
/// the session and that take together. `thumbfree://model` opens the app where it offers the speech model.
enum DictateLink {
    /// `host` is the trusted host app's bundle id (automatic return). It is left out when the keyboard does not trust it or
    /// the setting is off, so the string is byte-for-byte the old link in that case.
    static func url(take: UUID, host: String? = nil) -> URL? {
        var string = "\(Brand.urlScheme)://dictate?take=\(take.uuidString)"
        if let host, let encoded = host.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
            string += "&host=\(encoded)"
        }
        return URL(string: string)
    }

    /// The trusted host app's bundle id in a dictate link, or nil.
    static func host(from url: URL) -> String? {
        guard url.scheme == Brand.urlScheme, url.host() == "dictate" else { return nil }
        return URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "host" }?.value
    }

    /// `thumbfree://model`: the keyboard opens the app on Home, whose setup card offers the speech model's download.
    static let model = URL(string: "\(Brand.urlScheme)://model")

    static func isModel(_ url: URL) -> Bool { url.scheme == Brand.urlScheme && url.host() == "model" }

    static func take(from url: URL) -> UUID? {
        guard url.scheme == Brand.urlScheme, url.host() == "dictate",
              let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "take" })?.value
        else { return nil }
        return UUID(uuidString: value)
    }
}
