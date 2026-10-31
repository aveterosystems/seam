// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore

/// Builds before the Hello state was sealed kept it in UserDefaults. It moves into the app data
/// once; the caller saves the app data, then removes the old keys.
public enum LegacyHelloState {
    static let keys = ["peerTransports", "helloSeqSent", "helloSeqApplied", "peerTransportsAt"]

    public static func present(in d: UserDefaults) -> Bool {
        keys.contains { d.object(forKey: $0) != nil }
    }

    /// True if `data` changed. Never overwrites Hello state the app data already has, and never
    /// steps a sent counter back.
    public static func migrate(_ data: inout AppData, from d: UserDefaults) -> Bool {
        guard data.peerAllowed == nil, data.helloSeqApplied == 0, let raw = d.array(forKey: "peerTransports") as? [Int]
        else { return false }
        // An empty list was an older phone's: everything, as the core reads it. Values this build
        // can't use are dropped, so a list of only those means nothing in common
        data.peerAllowed = raw.isEmpty ? nil : Array(Set(raw.compactMap { $0 == 1 ? PeerPath.lan : $0 == 2 ? .relay : nil }))
        data.helloSeqSent = max(data.helloSeqSent, (d.object(forKey: "helloSeqSent") as? NSNumber)?.uint64Value ?? 0)
        data.helloSeqApplied = (d.object(forKey: "helloSeqApplied") as? NSNumber)?.uint64Value ?? 0
        return true
    }

    public static func remove(from d: UserDefaults) {
        keys.forEach(d.removeObject(forKey:))
    }
}
