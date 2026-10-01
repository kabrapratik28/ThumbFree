import Foundation
import Testing
@testable import ThumbFree

@MainActor @Suite struct SharedTests {
    @Test func aDarwinPostArrivesInThisProcessOnTheMainThread() async throws {
        let name = "io.github.kabrapratik28.thumbfree.test.\(UUID().uuidString)"
        var count = 0
        let observer = DarwinObserver(name) { count += 1 } // the action runs on the main actor, hopping there first if delivery lands elsewhere
        Task.detached { DarwinObserver.post(name) }
        for _ in 0..<100 where count == 0 { try await Task.sleep(for: .milliseconds(10)) }
        #expect(count == 1)
        withExtendedLifetime(observer) {}
    }

    // The observer removes itself when it goes away, so a later post never calls a freed object.
    @Test func aReleasedObserverHearsNothing() async throws {
        let name = "io.github.kabrapratik28.thumbfree.test.\(UUID().uuidString)"
        var count = 0
        var observer: DarwinObserver? = DarwinObserver(name) { count += 1 }
        #expect(observer != nil)
        observer = nil
        DarwinObserver.post(name)
        try await Task.sleep(for: .milliseconds(200))
        #expect(count == 0)
    }

    @Test func theDictateLinkRoundTrips() throws {
        let take = UUID()
        let url = try #require(DictateLink.url(take: take))
        #expect(url.absoluteString == "thumbfree://dictate?take=\(take.uuidString)")
        #expect(DictateLink.take(from: url) == take)
    }

    @Test func otherLinksAreNotDictateLinks() throws {
        for text in ["thumbfree://settings", "thumbfree://dictate", "thumbfree://dictate?take=nope",
                     "https://dictate?take=\(UUID().uuidString)"] {
            #expect(DictateLink.take(from: try #require(URL(string: text))) == nil, "\(text)")
        }
    }

    // The dictate link carries the trusted host's bundle id for automatic return, and reads the same take back.
    @Test func theDictateLinkCarriesTheHost() throws {
        let take = UUID()
        let url = try #require(DictateLink.url(take: take, host: "net.whatsapp.WhatsApp"))
        #expect(url.absoluteString == "thumbfree://dictate?take=\(take.uuidString)&host=net.whatsapp.WhatsApp")
        #expect(DictateLink.take(from: url) == take)
        #expect(DictateLink.host(from: url) == "net.whatsapp.WhatsApp")
    }

    // With no host the link is byte-for-byte the old one, and reads back no host.
    @Test func aLinkWithoutAHostIsUnchanged() throws {
        let take = UUID()
        let url = try #require(DictateLink.url(take: take))
        #expect(url.absoluteString == "thumbfree://dictate?take=\(take.uuidString)")
        #expect(DictateLink.host(from: url) == nil)
        #expect(DictateLink.host(from: try #require(DictateLink.model)) == nil)
    }
}
