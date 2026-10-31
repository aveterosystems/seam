// SPDX-License-Identifier: AGPL-3.0-only
import Testing

@testable import SeamSession

struct BackoffTests {
    @Test func doublesFromOneSecondAndCapsAtSixty() {
        let b = Backoff()
        #expect(b.delay(attempt: 0, jitter: 0) == 0.75)
        #expect(b.delay(attempt: 1, jitter: 0) == 1.5)
        #expect(b.delay(attempt: 0, jitter: 0.999) < 1)
        #expect(b.delay(attempt: 6, jitter: 0.999) <= 60)
        #expect(b.delay(attempt: 100, jitter: 0) == 45) // capped, still jittered
    }
}
