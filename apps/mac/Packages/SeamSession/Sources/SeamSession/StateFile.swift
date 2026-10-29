// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// The sealed state file on disk. Kept apart from the Keychain so its rules can be tested.
public struct StateFile: Sendable {
    public let url: URL

    public init(url: URL) {
        self.url = url
    }

    /// Nil only when the file doesn't exist (first launch). Any other failure throws, so a
    /// temporary read error can never look like a fresh install and get overwritten (SR-2-05).
    public func read() throws -> Data? {
        do {
            return try Data(contentsOf: url)
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            return nil
        } catch let error as POSIXError where error.code == .ENOENT {
            return nil
        }
    }

    /// Durable before it returns, power cuts included: written to a temp file, flushed to disk,
    /// renamed over the old one, then the directory flushed.
    /// A failed write leaves the old file as it was. Callers must not send anything a failed
    /// write would forget (SR-2-11).
    public func write(_ blob: Data) throws {
        let tmp = url.appendingPathExtension("tmp")
        guard FileManager.default.createFile(atPath: tmp.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let handle = try FileHandle(forWritingTo: tmp)
        do {
            try handle.write(contentsOf: blob)
            // F_FULLFSYNC also flushes the drive's cache (plain fsync doesn't on macOS, SR-2-68);
            // filesystems that don't support it get a normal fsync
            if fcntl(handle.fileDescriptor, F_FULLFSYNC) == -1 { try handle.synchronize() }
            try handle.close()
        } catch {
            try? handle.close()
            throw error
        }
        guard rename(tmp.path, url.path) == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
        // And the rename itself survives a power cut
        let dir = open(url.deletingLastPathComponent().path, O_RDONLY)
        guard dir >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        defer { close(dir) }
        if fcntl(dir, F_FULLFSYNC) == -1 && fsync(dir) != 0 && errno != EINVAL {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
    }

    /// For a reset the user chose: the file (and any half-written temp) is renamed aside, never
    /// deleted, so an unreadable state can still be recovered later (SR-2-06).
    public func moveAside(stamp: Int = Int(Date().timeIntervalSince1970)) throws {
        for file in [url, url.appendingPathExtension("tmp")] where FileManager.default.fileExists(atPath: file.path) {
            try FileManager.default.moveItem(at: file, to: file.appendingPathExtension("unreadable-\(stamp)"))
        }
    }
}
