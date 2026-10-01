import Testing
@testable import TFCore

@Test func brandIdentifiersMatchTheAndroidApp() {
    #expect(Brand.appName == "ThumbFree")
    #expect(Brand.bundleID == "io.github.kabrapratik28.thumbfree")
    #expect(Brand.keyboardBundleID == "io.github.kabrapratik28.thumbfree.keyboard")
    #expect(Brand.appGroupID == "group.io.github.kabrapratik28.thumbfree")
    #expect(Brand.urlScheme == "thumbfree")
}
