// Renders an SVG to a PNG of the given size in pixels with AppKit, which reads SVG natively.
// Usage: swift render-svg.swift IN.svg OUT.png WIDTH [HEIGHT]
import AppKit

let arguments = CommandLine.arguments
guard arguments.count >= 4, let image = NSImage(contentsOf: URL(fileURLWithPath: arguments[1])) else {
    FileHandle.standardError.write("usage: render-svg.swift IN.svg OUT.png WIDTH [HEIGHT]\n".data(using: .utf8)!)
    exit(1)
}
let width = Int(arguments[3])!
let height = arguments.count > 4 ? Int(arguments[4])! : width
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8,
                              samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                              bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
image.draw(in: NSRect(x: 0, y: 0, width: width, height: height))
NSGraphicsContext.restoreGraphicsState()
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: arguments[2]))
