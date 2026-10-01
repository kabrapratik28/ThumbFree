// Draws the App Store art in ThumbFree's colors (sunflower #FFD35A to #FFB61E, navy #1F1B3A) with the system font:
// the video's captions and tap mark (tools/store-video.sh), the captioned screenshots (tools/store-screenshots.sh) and
// the review page's contact sheet (tools/store-review.sh). Run with `swift tools/store-compose.swift <command> ...`:
//   caption <out.png> <width> <text>      a caption for the video, on a transparent PNG; "|" starts a new line
//   tap <out.png> <diameter>              the tap mark for the video, on a transparent PNG
//   frame <screen.png> <out.png> <text>   a store screenshot at the screen's own size, no alpha: the caption above the
//                                         screen in a navy frame, on the sunflower gradient; "|" starts a new line
//   sheet <out.png> <frame.png>...        the screenshots side by side, numbered, for the review page
import AppKit
import ImageIO
import UniformTypeIdentifiers

let navy = CGColor(srgbRed: 0x1F / 255, green: 0x1B / 255, blue: 0x3A / 255, alpha: 1)
let sunTop = CGColor(srgbRed: 1, green: 0xD3 / 255, blue: 0x5A / 255, alpha: 1)
let sunBottom = CGColor(srgbRed: 1, green: 0xB6 / 255, blue: 0x1E / 255, alpha: 1)
let space = CGColorSpace(name: CGColorSpace.sRGB)!

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

/// A context `width` by `height` pixels, y up; opaque ones have no alpha channel (App Store Connect refuses alpha).
func canvas(_ width: Int, _ height: Int, opaque: Bool) -> CGContext {
    let info = opaque ? CGImageAlphaInfo.noneSkipLast.rawValue : CGImageAlphaInfo.premultipliedLast.rawValue
    guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: space, bitmapInfo: info) else { fail("no \(width) x \(height) canvas") }
    context.interpolationQuality = .high
    return context
}

func save(_ context: CGContext, _ path: String) {
    guard let image = context.makeImage(),
          let file = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)
    else { fail("cannot write \(path)") }
    CGImageDestinationAddImage(file, image, nil)
    guard CGImageDestinationFinalize(file) else { fail("cannot write \(path)") }
}

func load(_ path: String) -> CGImage {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fail("cannot read \(path)") }
    return image
}

/// Bold system-font text in `color`, centered, one line per "|" (and wrapped at `width`).
func text(_ string: String, size: CGFloat, color: CGColor) -> NSAttributedString {
    let paragraph = NSMutableParagraphStyle()
    paragraph.alignment = .center
    paragraph.lineHeightMultiple = 1.02
    return NSAttributedString(string: string.replacingOccurrences(of: "|", with: "\n"), attributes: [
        .font: NSFont.systemFont(ofSize: size, weight: .bold), .foregroundColor: NSColor(cgColor: color)!,
        .paragraphStyle: paragraph, .kern: -0.01 * size,
    ])
}

func measure(_ string: NSAttributedString, width: CGFloat) -> CGSize {
    let box = string.boundingRect(with: CGSize(width: width, height: 10_000), options: [.usesLineFragmentOrigin, .usesFontLeading])
    return CGSize(width: ceil(box.width), height: ceil(box.height))
}

func draw(_ string: NSAttributedString, in rect: CGRect, on context: CGContext) {
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
    string.draw(with: rect, options: [.usesLineFragmentOrigin, .usesFontLeading])
    NSGraphicsContext.restoreGraphicsState()
}

func sunflower(_ context: CGContext, _ rect: CGRect) {
    let gradient = CGGradient(colorsSpace: space, colors: [sunTop, sunBottom] as CFArray, locations: [0, 1])!
    context.saveGState()
    context.clip(to: rect)
    context.drawLinearGradient(gradient, start: CGPoint(x: rect.midX, y: rect.maxY), end: CGPoint(x: rect.midX, y: rect.minY), options: [])
    context.restoreGState()
}

/// The video's caption: navy words on a sunflower card with a soft shadow, sized to the words.
func caption(out: String, width: Int, words: String) {
    let size = CGFloat(width) * 0.058
    let string = text(words, size: size, color: navy)
    let padX = size * 0.75, padY = size * 0.42, shadow = size * 0.5
    let box = measure(string, width: CGFloat(width) - 2 * (padX + shadow + size))
    let card = CGRect(x: shadow, y: shadow * 1.4, width: box.width + 2 * padX, height: box.height + 2 * padY)
    let context = canvas(Int(card.width + 2 * shadow), Int(card.height + shadow * 2.2), opaque: false)
    let radius = min(card.height / 2, size * 0.9)
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -size * 0.12), blur: shadow, color: CGColor(gray: 0, alpha: 0.28))
    context.addPath(CGPath(roundedRect: card, cornerWidth: radius, cornerHeight: radius, transform: nil))
    context.setFillColor(sunTop)
    context.fillPath()
    context.restoreGState()
    context.addPath(CGPath(roundedRect: card, cornerWidth: radius, cornerHeight: radius, transform: nil))
    context.clip()
    sunflower(context, card)
    context.resetClip()
    draw(string, in: CGRect(x: 0, y: card.minY + padY - 2, width: card.maxX + shadow, height: box.height + 4), on: context)
    save(context, out)
}

/// The tap mark: a white ring around a light gray disc, which the preview video shows on the mic at each tap and stop.
func tap(out: String, diameter: Int) {
    let d = CGFloat(diameter), line = d * 0.05
    let context = canvas(diameter, diameter, opaque: false)
    let disc = CGRect(x: line * 1.5, y: line * 1.5, width: d - line * 3, height: d - line * 3)
    context.setFillColor(CGColor(gray: 0, alpha: 0.08))
    context.fillEllipse(in: disc)
    context.setStrokeColor(CGColor(gray: 1, alpha: 0.95))
    context.setLineWidth(line)
    context.strokeEllipse(in: disc)
    context.setStrokeColor(CGColor(gray: 0, alpha: 0.35))
    context.setLineWidth(line * 0.35)
    context.strokeEllipse(in: disc.insetBy(dx: -line * 0.65, dy: -line * 0.65))
    save(context, out)
}

/// A store screenshot at the screen's size: the caption, then the whole screen scaled into a navy frame with Apple's
/// rounded corners and the Dynamic Island, on the sunflower gradient.
func frame(screen path: String, out: String, words: String) {
    let screen = load(path)
    let w = CGFloat(screen.width), h = CGFloat(screen.height), u = w / 1320 // sizes are for 1320 x 2868
    let context = canvas(screen.width, screen.height, opaque: true)
    sunflower(context, CGRect(x: 0, y: 0, width: w, height: h))
    let top = 540 * u, bottom = 96 * u, border = 20 * u
    let scale = (h - top - bottom - 2 * border) / h
    let shown = CGRect(x: (w - w * scale) / 2, y: bottom + border, width: w * scale, height: h * scale)
    let radius = 186 * u * scale // the screen's corner, 62 points at 3x
    let outer = shown.insetBy(dx: -border, dy: -border)
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -24 * u), blur: 60 * u, color: CGColor(gray: 0, alpha: 0.3))
    context.addPath(CGPath(roundedRect: outer, cornerWidth: radius + border, cornerHeight: radius + border, transform: nil))
    context.setFillColor(navy)
    context.fillPath()
    context.restoreGState()
    context.saveGState()
    context.addPath(CGPath(roundedRect: shown, cornerWidth: radius, cornerHeight: radius, transform: nil))
    context.clip()
    context.draw(screen, in: shown)
    let island = CGSize(width: 378 * u * scale, height: 111 * u * scale) // 126 x 37 points, 11 points from the top
    let islandRect = CGRect(x: shown.midX - island.width / 2, y: shown.maxY - 33 * u * scale - island.height,
                            width: island.width, height: island.height)
    context.addPath(CGPath(roundedRect: islandRect, cornerWidth: island.height / 2, cornerHeight: island.height / 2, transform: nil))
    context.setFillColor(CGColor(gray: 0, alpha: 1))
    context.fillPath()
    context.restoreGState()
    let string = text(words, size: 88 * u, color: navy) // as tall as the Android listing's captions: 6.7% of the width
    let box = measure(string, width: w - 180 * u)
    let area = CGRect(x: 0, y: outer.maxY, width: w, height: h - outer.maxY)
    // Drawn across the whole width (each line centered), since a box as narrow as the measured text can wrap it again.
    draw(string, in: CGRect(x: 90 * u, y: area.midY - box.height / 2 + 6 * u, width: w - 180 * u, height: box.height + 20 * u), on: context)
    save(context, out)
}

/// The screenshots in a row, each with its number under it, on white.
func sheet(out: String, paths: [String]) {
    let images = paths.map(load)
    guard let first = images.first else { fail("no screenshots") }
    let tileW: CGFloat = 440, tileH = tileW * CGFloat(first.height) / CGFloat(first.width), gap: CGFloat = 36, label: CGFloat = 70
    let width = gap + CGFloat(images.count) * (tileW + gap), height = gap + tileH + label
    let context = canvas(Int(width), Int(height), opaque: true)
    context.setFillColor(CGColor(gray: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    for (index, image) in images.enumerated() {
        let x = gap + CGFloat(index) * (tileW + gap)
        let rect = CGRect(x: x, y: label, width: tileW, height: tileH)
        context.draw(image, in: rect)
        context.setStrokeColor(CGColor(gray: 0, alpha: 0.15))
        context.setLineWidth(2)
        context.stroke(rect)
        let number = text("\(index + 1)", size: 34, color: navy)
        let box = measure(number, width: tileW)
        draw(number, in: CGRect(x: x, y: (label - box.height) / 2, width: tileW, height: box.height + 8), on: context)
    }
    save(context, out)
}

let args = CommandLine.arguments.dropFirst()
switch (args.first, args.count) {
case ("caption", 4): caption(out: args[2], width: Int(args[3]) ?? 886, words: args[4])
case ("tap", 3): tap(out: args[2], diameter: Int(args[3]) ?? 120)
case ("frame", 4): frame(screen: args[2], out: args[3], words: args[4])
case ("sheet", 3...): sheet(out: args[2], paths: Array(args.dropFirst(2)))
default: fail("usage: store-compose.swift caption <out.png> <width> <text> | tap <out.png> <diameter> | "
              + "frame <screen.png> <out.png> <text> | sheet <out.png> <frame.png>...")
}
