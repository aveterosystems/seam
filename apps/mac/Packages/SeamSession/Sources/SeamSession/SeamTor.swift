// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore

/// The core's Tor (seam-ffi `Tor`) as `Paths` runs it. One per app: Tor keeps its state in
/// `<appDir>/tor`.
public final class SeamTor: TorRunner, @unchecked Sendable {
    private let tor: Tor

    public init(appDir: URL) {
        tor = Tor(appDir: appDir.path)
    }

    public func start(onState: @escaping @Sendable (TorState) -> Void) {
        tor.start(listener: StateListener(onState))
    }

    public func host(_ spec: TorServiceSpec, onStream: @escaping @Sendable (TorStream) -> Void) -> (any TorHosted)? {
        tor.host(serviceSecret: spec.secret, clients: spec.clients, listener: StreamListener(onStream)).map(Hosted.init)
    }

    private final class Hosted: TorHosted, @unchecked Sendable {
        let service: TorService
        init(_ service: TorService) { self.service = service }
        func stop() { service.stop() }
    }

    private final class StateListener: TorStateListener, @unchecked Sendable {
        let report: @Sendable (TorState) -> Void
        init(_ report: @escaping @Sendable (TorState) -> Void) { self.report = report }
        func onState(state: TorState) { report(state) }
    }

    private final class StreamListener: TorConnectListener, @unchecked Sendable {
        let onStream: @Sendable (TorStream) -> Void
        init(_ onStream: @escaping @Sendable (TorStream) -> Void) { self.onStream = onStream }
        func onOpen(stream: TorStream) { onStream(stream) }
        // The service stopped by itself; Paths republishes after wake or the next change
        func onFailed() {}
    }
}
