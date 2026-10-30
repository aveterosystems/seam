// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// Builders for the frames a device sends the relay (protocol/seam/v1/relay.proto).
public enum Frames {
    /// What the relay checks the login signature over (the Rust core signs it).
    public static func loginMessage(nonce: Data, groupID: Data) -> Data {
        Data("seam-relay-login-v1".utf8) + nonce + groupID
    }

    public static func connectURL(relay: URL, groupID: Data) -> URL {
        var c = URLComponents(url: relay.appendingPathComponent("v1/connect"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "group", value: groupID.base64URL)]
        return c.url!
    }

    /// `createGroup` lets the first device create its group; the relay ignores it for existing members.
    /// `ownerKey`: a private relay's, sent only with `createGroup` (it's needed only to create).
    public static func login(device: SeamDevice, nonce: Data, groupID: Data, joinToken: Data? = nil, createGroup: Bool = false, ownerKey: Data? = nil) -> Seam_V1_ClientFrame {
        .with {
            $0.login = .with {
                $0.groupID = groupID
                $0.deviceKey = device.relayKey()
                $0.signature = device.signRelayLogin(nonce: nonce, groupId: groupID)
                if let joinToken { $0.joinToken = joinToken }
                $0.createGroup = createGroup
                if createGroup, let ownerKey { $0.ownerKey = ownerKey }
            }
        }
    }

    public static func send(_ envelope: Seam_V1_Envelope) -> Seam_V1_ClientFrame {
        .with { $0.send = envelope }
    }

    public static func delivered(_ msgID: Data) -> Seam_V1_ClientFrame {
        .with { $0.delivered = .with { $0.msgID = msgID } }
    }

    public static func putBundles(_ bundles: [Data]) -> Seam_V1_ClientFrame {
        .with { $0.putBundles = .with { $0.bundles = bundles } }
    }

    public static func getBundle(_ deviceKey: Data) -> Seam_V1_ClientFrame {
        .with { $0.getBundle = .with { $0.deviceKey = deviceKey } }
    }

    /// The relay clamps the slot to 5 minutes, matching the core's own pairing expiry.
    public static func openPairing(tokenHash: Data, ttl: UInt32 = 300) -> Seam_V1_ClientFrame {
        .with { $0.openPairing = .with { $0.tokenHash = tokenHash; $0.ttlS = ttl } }
    }

    /// Closes this device's open pairing slot (cancel, or the code expired).
    public static func closePairing() -> Seam_V1_ClientFrame {
        .with { $0.closePairing = .init() }
    }

    /// Takes a device out of the group, with its queue and bundles (a rejected phone that joined).
    public static func removeMember(_ deviceKey: Data) -> Seam_V1_ClientFrame {
        .with { $0.removeMember = .with { $0.deviceKey = deviceKey } }
    }
}

/// Reconnect delays: doubling from 1 s up to 60 s, scaled by 75–100% so many clients don't retry in step.
public struct Backoff: Sendable {
    public var base: Double = 1
    public var cap: Double = 60

    /// A connection that lasted this long was stable: the next retry starts from 1 s again.
    public static let stableAfter: TimeInterval = 30

    public init() {}

    /// The attempt number after a connection ends; nil `connectedFor` means login never succeeded.
    public static func nextAttempt(after attempt: Int, connectedFor: TimeInterval?) -> Int {
        if let connectedFor, connectedFor >= stableAfter { return 0 }
        return attempt + 1
    }

    /// `jitter` in 0..<1.
    public func delay(attempt: Int, jitter: Double) -> Double {
        min(cap, base * pow(2, Double(min(attempt, 30)))) * (0.75 + 0.25 * jitter)
    }
}
