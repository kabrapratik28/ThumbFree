import Foundation
import Testing
@testable import ThumbFree

@Test func appGroupContainerIsReachableOnTheSimulator() throws {
    let url = try AppGroup.containerURL()
    #expect(FileManager.default.fileExists(atPath: url.path))
}
