import XCTest

/// The first usable slice on the Simulator: the ThumbFree keyboard in the box of ThumbFree's try screen (a keyboard may be
/// used in its containing app, so no app switch is needed). A tap starts the take, a tap stops it, the text is typed.
@MainActor final class EndToEndUITests: XCTestCase {
    func testTheKeyboardDictatesIntoAFieldWithTheFixedEngine() throws {
        KeyboardSetup.ensureReady()
        dictate(in: ThumbFreeUI.launchTry(), seconds: 3)
    }

    /// Runs only with the real model: `TEST_RUNNER_TF_MODELS_DIR="$HOME/Library/Application Support/FluidAudio/Models"`.
    func testTheKeyboardDictatesJFKWithTheRealModel() throws {
        guard ProcessInfo.processInfo.environment["TF_MODELS_DIR"] != nil else {
            throw XCTSkip("Set TEST_RUNNER_TF_MODELS_DIR to the folder that holds parakeet-tdt-0.6b-v2 to run this.")
        }
        KeyboardSetup.ensureReady()
        dictate(in: ThumbFreeUI.launchTry(realModel: true), seconds: 12)
    }

    private func dictate(in launched: (app: XCUIApplication, field: XCUIElement), seconds: UInt32) {
        let (app, field) = launched
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        let mic = app.buttons["keyboard.mic"]
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["keyboard.status"], toContain: "Recording", timeout: 15))
        sleep(seconds)
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "ask not what your country can do for you", timeout: 120))
        // The try screen covers the tab bar, so History is checked after a relaunch, which keeps it (no -TFResetState).
        app.terminate()
        let again = XCUIApplication()
        again.launchArguments = ["-TFWelcomeDone", "YES"]
        again.launch()
        again.tabBars.buttons["History"].tap()
        XCTAssertTrue(again.staticTexts["Typed"].waitForExistence(timeout: 5))
    }
}
