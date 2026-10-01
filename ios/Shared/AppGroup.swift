import Foundation
import TFCore

enum AppGroupError: Error { case unavailable }

enum AppGroup {
    /// The container shared by the app and the keyboard.
    static func containerURL() throws -> URL {
        guard let url = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Brand.appGroupID) else {
            throw AppGroupError.unavailable
        }
        return url
    }

    /// Where the keyboard's commands and the app's status and outbox live (SharedStore). The outbox holds transcript
    /// text, so the folder is excluded from device backups.
    static func ipcDirectory() throws -> URL {
        var url = try containerURL().appendingPathComponent("IPC", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
        return url
    }
}
