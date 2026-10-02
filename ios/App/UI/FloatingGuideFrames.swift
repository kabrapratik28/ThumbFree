import AVFoundation
import SwiftUI

extension FloatingGuide {
    /// The videos being made now, by file, so a second call waits for the first rather than making another.
    private static var making: [URL: Task<URL, Error>] = [:]

    /// The video for `scheme`, made now from the frames, or found in `folder` from before: its name carries the scheme,
    /// `version` and the app's build number. Silent H.264, `size` at `scale`, as long as the beats. A call while the same
    /// video is being made waits for it: the welcome starts it on step 1, and the keyboard step's view joins in. The
    /// making goes on if the caller is cancelled, so leaving step 1 never wastes it.
    static func video(_ scheme: ColorScheme, in folder: URL = .cachesDirectory) async throws -> URL {
        let url = folder.appendingPathComponent(fileName(scheme))
        if FileManager.default.fileExists(atPath: url.path) { return url }
        if let making = making[url] { return try await making.value }
        let task = Task {
            // Written beside it, then moved into place, so an interrupted write never leaves a broken video to reuse.
            let part = folder.appendingPathComponent("setup-guide-\(UUID().uuidString).mp4")
            defer { try? FileManager.default.removeItem(at: part) }
            try await write(scheme, to: part)
            try? FileManager.default.moveItem(at: part, to: url) // fails only if another call put it there first
            guard FileManager.default.fileExists(atPath: url.path) else { throw CocoaError(.fileWriteUnknown) }
            return url
        }
        making[url] = task
        defer { making[url] = nil }
        return try await task.value
    }

    /// The video's name in its folder: "setup-guide-dark-v4-build4.mp4".
    static func fileName(_ scheme: ColorScheme,
                         build: String = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") -> String {
        "setup-guide-\(scheme == .dark ? "dark" : "light")-v\(version)-build\(build).mp4"
    }

    /// A frame drawn in `scheme`, at `scale`.
    static func image(_ shot: Shot, _ scheme: ColorScheme) -> CGImage? {
        let renderer = ImageRenderer(content: GuideFrame(shot: shot).environment(\.colorScheme, scheme))
        renderer.scale = scale
        renderer.isOpaque = true
        return renderer.cgImage
    }

    /// Draws each frame in `scheme` and writes it, shown from its start until the next, as an H.264 MP4 with no sound
    /// track. One frame at a time: at 3x, all of them would take about 250 MB.
    static func write(_ scheme: ColorScheme, to url: URL) async throws {
        let width = Int(size.width * scale), height = Int(size.height * scale)
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
        ])
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
        writer.startSession(atSourceTime: .zero)
        var ticks: Int64 = 0 // in 1/600 s, so every hold is a whole number of ticks
        do {
            for shot in shots {
                while !input.isReadyForMoreMediaData {
                    guard writer.status == .writing else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
                    try await Task.sleep(for: .milliseconds(10))
                }
                guard let image = image(shot, scheme), let buffer = pixelBuffer(image, from: adaptor.pixelBufferPool),
                      adaptor.append(buffer, withPresentationTime: CMTime(value: ticks, timescale: 600)) else {
                    throw writer.error ?? CocoaError(.fileWriteUnknown)
                }
                ticks += Int64((shot.hold * 600).rounded())
                await Task.yield() // the page stays responsive while the frames draw
            }
        } catch {
            writer.cancelWriting()
            throw error
        }
        input.markAsFinished()
        writer.endSession(atSourceTime: CMTime(value: ticks, timescale: 600)) // the last frame holds to the end
        await withCheckedContinuation { done in writer.finishWriting { done.resume() } }
        guard writer.status == .completed else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
    }

    private static func pixelBuffer(_ image: CGImage, from pool: CVPixelBufferPool?) -> CVPixelBuffer? {
        var made: CVPixelBuffer?
        guard let pool, CVPixelBufferPoolCreatePixelBuffer(nil, pool, &made) == kCVReturnSuccess, let buffer = made else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return nil }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height)) // the buffer's own size, whatever the image's
        return buffer
    }
}

/// One frame of the guide's video, 322 x 274 points: the five beats' numbers with this one lit in sunflower, its caption
/// in type large enough to read in the small floating window, and its drawing, made of the step's own pieces: the beat's
/// Settings row with the tap cue on what it taps, a shield under iOS's question (named in words, never drawn), or the way
/// back at the top left. On the page's paper, as the step's other pictures are.
struct GuideFrame: View {
    let shot: FloatingGuide.Shot

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                ForEach(FloatingGuide.captions.indices, id: \.self) { index in
                    let lit = index == shot.beat
                    Text("\(index + 1)")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(lit ? Theme.onSunflower : Theme.inkSoft)
                        .frame(width: 36, height: 36)
                        .background(lit ? Theme.sunflower : Theme.muted, in: .circle)
                }
            }
            Text(FloatingGuide.captions[shot.beat].keepingLastWordsTogether)
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(Theme.ink)
                .lineLimit(2, reservesSpace: true) // two lines' room in every beat: the drawing holds still
                .frame(maxWidth: .infinity, alignment: .topLeading)
            drawing.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(16)
        .frame(width: FloatingGuide.size.width, height: FloatingGuide.size.height, alignment: .top)
        .background(Theme.paper)
        .dynamicTypeSize(.large)
    }

    @ViewBuilder private var drawing: some View {
        switch shot.beat {
        case 0: row(SettingsRowDiagram(title: "Keyboards", time: shot.time))
        case 1: row(SettingsRowDiagram(title: "ThumbFree", end: .toggle, time: shot.time))
        case 2: row(SettingsRowDiagram(title: "Allow Full Access", end: .toggle, time: shot.time))
        case FloatingGuide.allowBeat:
            // iOS's own question comes here: the picture only names it, with no drawn prompt and no ringed button.
            Image(systemName: "checkmark.shield").font(.system(size: 56)).foregroundStyle(Theme.primary)
        default: BackLinkDiagram(time: shot.time)
        }
    }

    /// A beat's row under the room for its cue's pill, at the same place in every beat.
    private func row(_ row: SettingsRowDiagram) -> some View {
        row.padding(.top, SettingsRowDiagram.pillRoom).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The top left of Settings after ThumbFree opened it: the way back, "◀ ThumbFree", ringed and pressed `time` seconds
/// into its tap, over a hint of the page's title. No clock, island or battery: only what to tap.
private struct BackLinkDiagram: View {
    let time: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 4) {
                Image(systemName: "arrowtriangle.backward.fill").font(.system(size: 12))
                Text("ThumbFree").font(.system(size: 19, weight: .semibold))
            }
            .foregroundStyle(Theme.ink)
            .opacity(1 - 0.5 * TapTimeline.press(at: time))
            .padding(.horizontal, 12)
            .frame(height: 36)
            .overlay { TapCue(outline: Capsule(), ripple: TapTimeline.ripple(at: time), pillEdge: .trailing) }
            Text("Keyboards")
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(Theme.ink.opacity(0.3))
                .padding(.leading, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
