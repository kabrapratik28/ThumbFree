import AppIntents
import SwiftUI
import WidgetKit

/// The Control Center control (and Lock Screen and Action Button choice) that runs Start ThumbFree.
@main
struct ThumbFreeControls: WidgetBundle {
    var body: some Widget { StartControl() }
}

struct StartControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "io.github.kabrapratik28.thumbfree.start") {
            ControlWidgetButton(action: StartSessionIntent()) {
                Label("Start ThumbFree", systemImage: "mic.fill")
            }
        }
        .displayName("Start ThumbFree")
        .description("Turns on the microphone for dictation.")
    }
}
