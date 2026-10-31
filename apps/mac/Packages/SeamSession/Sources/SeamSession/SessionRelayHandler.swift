// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// The session takes the relay's events itself: nothing in between to forward them.
extension Session: RelayHandler {
    public func relayConnected(members: [Data]) async {
        await relayConnected()
    }

    public func relayPeerJoined(_ deviceKey: Data) async {}
    public func relayBundle(deviceKey: Data, bundle: Data) async {}
}
