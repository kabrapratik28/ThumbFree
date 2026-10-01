import SwiftUI
import Testing
@testable import ThumbFree

/// The keyboard's mic key follows its status line.
@MainActor @Suite struct BubbleArtTests {
    @Test func theKeyShowsListeningAndTranscribing() {
        #expect(BubbleArt.Mode(.listening) == .stop)
        #expect(BubbleArt.Mode(.transcribing) == .busy)
        #expect(BubbleArt.Mode(.gettingReady) == .busy)
        let idle: [KeyState] = [.needsFullAccess, .needsModel, .startDictation, .opening, .openFailed, .starting, .ready,
                                .message("No speech heard.")]
        #expect(idle.allSatisfy { BubbleArt.Mode($0) == .idle })
    }

    // While it listens the key is a stop key: the disc red (#FF3B30) with a white square in its middle, where the other
    // keys show the yellow disc and the navy key.
    @Test func theListeningKeyIsARedDiscWithAWhiteSquare() throws {
        let stop = try colors(.stop)
        #expect(stop.middle == [255, 255, 255])
        #expect(zip(stop.disc, [255, 59, 48]).allSatisfy { abs(Int($0) - $1) <= 3 })
        for mode in [BubbleArt.Mode.idle, .busy] {
            let other = try colors(mode)
            #expect(other.middle != [255, 255, 255])
            #expect(other.disc[2] < 100 && other.disc[1] > 150) // the sunflower disc
        }
    }

    /// The key drawn 100 points wide at 1 pixel a point: the color at its middle, and on the disc 28 points above it.
    private func colors(_ mode: BubbleArt.Mode) throws -> (middle: [UInt8], disc: [UInt8]) {
        let image = try #require(ImageRenderer(content: BubbleArt(mode: mode).frame(width: 100, height: 100)).cgImage)
        let sRGB = try #require(CGColorSpace(name: CGColorSpace.sRGB))
        let context = try #require(CGContext(data: nil, width: 100, height: 100, bitsPerComponent: 8, bytesPerRow: 400, space: sRGB,
                                             bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(image, in: CGRect(x: 0, y: 0, width: 100, height: 100))
        let bytes = try #require(context.data).bindMemory(to: UInt8.self, capacity: 100 * 400)
        func rgb(_ x: Int, _ y: Int) -> [UInt8] { (0..<3).map { bytes[y * 400 + x * 4 + $0] } } // rows top down
        return (rgb(50, 50), rgb(50, 22))
    }
}
