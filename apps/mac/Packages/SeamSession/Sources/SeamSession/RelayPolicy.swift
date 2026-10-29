// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// Which relay URLs a build accepts (SR-2-07). Release builds only talk TLS (`wss://`); plain
/// `ws://` is for Debug builds against a local `wrangler dev`.
public enum RelayPolicy {
    public static let developmentURL = URL(string: "ws://localhost:8787")!
    /// The production relay (server/README.md, "Deploying"). Changes when a custom domain is added.
    public static let productionURL = URL(string: "wss://relay.avetero.com")!

    /// The first acceptable candidate, else the build's default. Never crashes on odd input.
    public static func resolve(_ candidates: [String?], allowInsecure: Bool) -> URL {
        for c in candidates {
            if let url = acceptable(c, allowInsecure: allowInsecure) { return url }
        }
        return allowInsecure ? developmentURL : productionURL
    }

    /// The relay a new group is created on (first launch, or the one-time migration). Only a Debug
    /// build takes the UserDefaults override; Release always uses its own relay (SR-2-60).
    public static func forNewGroup(override: String?, allowInsecure: Bool) -> URL {
        resolve(allowInsecure ? [override] : [], allowInsecure: allowInsecure)
    }

    /// A ws/wss URL with a host and nothing that doesn't belong in a relay address.
    public static func acceptable(_ text: String?, allowInsecure: Bool) -> URL? {
        guard let text, text.utf8.count <= 2048, let c = URLComponents(string: text),
              let scheme = c.scheme?.lowercased(), let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil
        else { return nil }
        guard scheme == "wss" || (allowInsecure && scheme == "ws") else { return nil }
        return c.url
    }
}
