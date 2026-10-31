// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import Testing

@testable import SeamSession

/// Builds before the Hello state was sealed kept it in UserDefaults; it moves into the app data once.
struct LegacyHelloStateTests {
    private func defaults(_ values: [String: Any]) -> UserDefaults {
        let name = "seam-legacy-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        values.forEach { d.set($1, forKey: $0) }
        return d
    }

    private var fresh: AppData {
        AppData(groupId: Data(repeating: 1, count: 16), relayUrl: "wss://relay.avetero.com", ownerKey: nil,
                helloSeqSent: 0, helloSeqApplied: 0, peerAllowed: nil)
    }

    @Test func movesTheOldHelloStateIn() {
        var data = fresh
        let d = defaults(["peerTransports": [1], "helloSeqSent": 4, "helloSeqApplied": 7])
        #expect(LegacyHelloState.migrate(&data, from: d))
        #expect(data.peerAllowed == [.lan])
        #expect((data.helloSeqSent, data.helloSeqApplied) == (4, 7))
    }

    @Test func anOlderPhonesEmptyListMeansEverything() {
        var data = fresh
        #expect(LegacyHelloState.migrate(&data, from: defaults(["peerTransports": [Int](), "helloSeqApplied": 2])))
        #expect(data.peerAllowed == nil)
    }

    @Test func onlyUnusablePathsMeanNothingInCommon() {
        var data = fresh
        #expect(LegacyHelloState.migrate(&data, from: defaults(["peerTransports": [3], "helloSeqApplied": 2])))
        #expect(data.peerAllowed == [])
    }

    @Test func neverOverwritesHelloStateTheAppDataAlreadyHas() {
        var data = fresh
        data.helloSeqApplied = 9
        data.peerAllowed = [.relay]
        #expect(!LegacyHelloState.migrate(&data, from: defaults(["peerTransports": [1], "helloSeqApplied": 3])))
        #expect(data.peerAllowed == [.relay])
        #expect(data.helloSeqApplied == 9)
    }

    @Test func aCounterAlreadySentNeverStepsBack() {
        var data = fresh
        data.helloSeqSent = 10
        #expect(LegacyHelloState.migrate(&data, from: defaults(["peerTransports": [2], "helloSeqSent": 4])))
        #expect(data.helloSeqSent == 10)
    }

    @Test func knowsWhichKeysToRemove() {
        #expect(LegacyHelloState.present(in: defaults(["helloSeqSent": 1])))
        #expect(!LegacyHelloState.present(in: defaults([:])))
        let d = defaults(["peerTransports": [1], "helloSeqSent": 1, "helloSeqApplied": 1, "peerTransportsAt": 5])
        LegacyHelloState.remove(from: d)
        #expect(!LegacyHelloState.present(in: d))
    }
}
