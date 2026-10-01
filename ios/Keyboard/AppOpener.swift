import UIKit

/// Keyboards cannot use `UIApplication.shared`, but their responder chain still reaches the UIApplication object, and
/// `open(_:options:completionHandler:)` is not marked unavailable for extensions. Checked on the iOS 26.5 Simulator:
/// it opens ThumbFree from another app (running or not) and from ThumbFree's own text field.
enum AppOpener {
    @MainActor static func open(_ url: URL, from responder: UIResponder, completion: @escaping @MainActor (Bool) -> Void) {
        var next: UIResponder? = responder
        while let current = next {
            if let application = current as? UIApplication {
                application.open(url, options: [:], completionHandler: completion)
                return
            }
            next = current.next
        }
        completion(false)
    }
}
