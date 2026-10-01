import UIKit

/// Hands a background download's events to the app: iOS wakes or relaunches ThumbFree when model
/// files finish while it is in the background. Asking for the transfers makes their session again, which takes the
/// events; iOS gets its completion handler back once they are handled.
final class AppDelegate: NSObject, UIApplicationDelegate {
    private static var pending: [String: () -> Void] = [:]

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        Self.pending[identifier] = completionHandler
        _ = ModelTransfers.background(identifier: identifier)
    }

    /// The session handled every event iOS woke the app for.
    static func backgroundEventsDone(_ identifier: String) {
        pending.removeValue(forKey: identifier)?()
    }
}
