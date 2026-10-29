// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Testing

@testable import SeamSession

struct StateFileTests {
    private func tempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// SR-2-05: only a missing file means first launch.
    @Test func onlyAMissingFileLooksLikeFirstLaunch() throws {
        let dir = try tempDir()
        #expect(try StateFile(url: dir.appendingPathComponent("state.bin")).read() == nil)

        let unreadable = dir.appendingPathComponent("locked.bin")
        try Data([1, 2, 3]).write(to: unreadable)
        try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: unreadable.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: unreadable.path) }
        #expect(throws: (any Error).self) { try StateFile(url: unreadable).read() }

        // A directory where the file should be is also an error, not a fresh install
        #expect(throws: (any Error).self) { try StateFile(url: dir).read() }
    }

    /// SR-2-11: a write is complete when it returns, and a failed write keeps the old state.
    @Test func aFailedWriteKeepsTheOldState() throws {
        let dir = try tempDir()
        let file = StateFile(url: dir.appendingPathComponent("state.bin"))
        try file.write(Data([1]))
        try file.write(Data([2]))
        #expect(try file.read() == Data([2]))
        #expect(!FileManager.default.fileExists(atPath: file.url.appendingPathExtension("tmp").path))

        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path) }
        #expect(throws: (any Error).self) { try file.write(Data([3])) }
        #expect(try file.read() == Data([2]))
    }

    /// SR-2-06: a reset moves the old state aside; it never deletes it.
    @Test func aResetKeepsTheOldFiles() throws {
        let dir = try tempDir()
        let file = StateFile(url: dir.appendingPathComponent("state.bin"))
        try file.write(Data([9]))
        try Data([8]).write(to: file.url.appendingPathExtension("tmp"))
        try file.moveAside(stamp: 42)
        #expect(try file.read() == nil)
        #expect(try Data(contentsOf: dir.appendingPathComponent("state.bin.unreadable-42")) == Data([9]))
        #expect(try Data(contentsOf: dir.appendingPathComponent("state.bin.tmp.unreadable-42")) == Data([8]))
    }
}
