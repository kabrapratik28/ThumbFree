import CryptoKit
import Foundation
import Testing
@testable import TFCore

@Suite struct CommonWordsTests {
    @Test func isTheAndroidList() {
        #expect(CommonWords.all.count == 5_000)
        // SHA-256 of the 5,000 keys sorted and joined with "\n", computed from the Android app's CommonWords.kt.
        let digest = SHA256.hash(data: Data(CommonWords.all.sorted().joined(separator: "\n").utf8))
        #expect(digest.map { String(format: "%02x", $0) }.joined()
            == "74480be1b6b6ac1b9ef8b571f4a8594396857df43c1cfcee2b370da007619e5f")
        #expect(CommonWords.all.isSuperset(of: ["the", "dont", "iphone", "will", "fred"]))
        #expect(!CommonWords.all.contains("don't") && !CommonWords.all.contains("ios"))
    }
}
