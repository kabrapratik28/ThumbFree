import Foundation

/// Darwin notifications carry no data: they only tell the other process to read the App Group files again. Apple
/// does not document which thread delivers a notification (observed as the main thread on the iOS 26.5 Simulator,
/// app to keyboard, keyboard to app and in-process, but that is not a guarantee), so the callback below only calls
/// `action` directly when it is already on the main thread; otherwise it hops to the main queue through a weak
/// reference, so a released observer is never called and an unexpected delivery thread never traps the process.
final class DarwinObserver {
    private let action: @MainActor () -> Void

    @MainActor init(_ name: String, action: @escaping @MainActor () -> Void) {
        self.action = action
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), Unmanaged.passUnretained(self).toOpaque(),
                                        { _, observer, _, _, _ in
                                            guard let observer else { return }
                                            let me = Unmanaged<DarwinObserver>.fromOpaque(observer).takeUnretainedValue()
                                            guard Thread.isMainThread else {
                                                DispatchQueue.main.async { [weak me] in
                                                    guard let me else { return }
                                                    MainActor.assumeIsolated { me.action() }
                                                }
                                                return
                                            }
                                            MainActor.assumeIsolated { me.action() }
                                        }, name as CFString, nil, .deliverImmediately)
    }

    deinit {
        CFNotificationCenterRemoveEveryObserver(CFNotificationCenterGetDarwinNotifyCenter(), Unmanaged.passUnretained(self).toOpaque())
    }

    static func post(_ name: String) {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), CFNotificationName(name as CFString),
                                             nil, nil, true)
    }
}
