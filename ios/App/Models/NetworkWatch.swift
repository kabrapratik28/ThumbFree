import Foundation
import Network
import Observation

/// Whether the iPhone is online, and whether it is on Wi-Fi rather than mobile data. A background download waits
/// without a word when it cannot go on, so the model rows say "Waiting for Wi-Fi" or "Waiting for a connection".
@MainActor @Observable final class NetworkWatch {
    var online = true
    /// Online and not on mobile data: Wi-Fi, or a wired connection.
    var wifi = true
    @ObservationIgnored private var monitor: NWPathMonitor?

    /// `watching` false: tests set the two values themselves.
    init(watching: Bool = true) {
        guard watching else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            let wifi = online && !path.usesInterfaceType(.cellular)
            Task { @MainActor in
                self?.online = online
                self?.wifi = wifi
            }
        }
        monitor.start(queue: DispatchQueue(label: "io.github.kabrapratik28.thumbfree.network"))
        self.monitor = monitor
    }
}
