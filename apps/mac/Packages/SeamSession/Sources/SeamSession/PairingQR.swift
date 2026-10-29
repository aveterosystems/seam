// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// The pairing QR code, exactly as protocol/README.md defines it:
/// `seam://pair?v=1&r=<relay>&g=<group>&t=<token>&i=<identity key>&k=<relay key>&n=<name>`
/// Binary values are base64url without padding; text values are percent-encoded.
public struct PairingQR: Equatable, Sendable {
    public var relayURL: String
    public var groupID: Data
    public var token: Data
    public var identityKey: Data
    public var relayKey: Data
    public var name: String

    public init(relayURL: String, groupID: Data, token: Data, identityKey: Data, relayKey: Data, name: String) {
        self.relayURL = relayURL
        self.groupID = groupID
        self.token = token
        self.identityKey = identityKey
        self.relayKey = relayKey
        self.name = name
    }

    public enum ParseError: Error, Equatable {
        case notSeamPairing, unsupportedVersion, missing(String), badLength(String)
    }

    public var url: String {
        let items: [(String, String)] = [
            ("v", "1"), ("r", Self.percentEncode(relayURL)), ("g", groupID.base64URL),
            ("t", token.base64URL), ("i", identityKey.base64URL), ("k", relayKey.base64URL),
            ("n", Self.percentEncode(name)),
        ]
        return "seam://pair?" + items.map { "\($0.0)=\($0.1)" }.joined(separator: "&")
    }

    public static func parse(_ text: String) throws -> PairingQR {
        guard let c = URLComponents(string: text), c.scheme == "seam", c.host == "pair" else {
            throw ParseError.notSeamPairing
        }
        let q = Dictionary((c.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        guard q["v"] == "1" else { throw ParseError.unsupportedVersion }
        // Not named `text`: that would shadow the `text` parameter above
        func field(_ k: String) throws -> String {
            guard let v = q[k], !v.isEmpty else { throw ParseError.missing(k) }
            return v
        }
        func bytes(_ k: String, count: Int) throws -> Data {
            guard let d = Data(base64URL: try field(k)) else { throw ParseError.missing(k) }
            guard d.count == count else { throw ParseError.badLength(k) }
            return d
        }
        return PairingQR(
            relayURL: try field("r"), groupID: try bytes("g", count: 16), token: try bytes("t", count: 32),
            identityKey: try bytes("i", count: 33), relayKey: try bytes("k", count: 32), name: try field("n")
        )
    }

    /// RFC 3986 unreserved characters only, so every QR reader decodes it the same way ('+' included).
    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    static func percentEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? s
    }
}

extension Data {
    /// base64url without padding.
    public var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public init?(base64URL s: String) {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b += String(repeating: "=", count: (4 - b.count % 4) % 4)
        self.init(base64Encoded: b)
    }
}
