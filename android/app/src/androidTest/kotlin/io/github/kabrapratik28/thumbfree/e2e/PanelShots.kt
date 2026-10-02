package io.github.kabrapratik28.thumbfree.e2e

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.automation
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.shell
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * For the owner's review set: the bubble's not-ready panel over another app, light and dark, after a tap while the chosen
 * model downloads (its download stood in for, at 42%); with the bubble at the left edge; right of the middle, where it
 * stands above the bubble; there near the top, where it opens below; at 200% font; and recorded as it comes with a tap
 * and goes with a second one. Only with
 * `-e owner_shots 1`, into filesDir/ui-shots/owner/ and /sdcard/thumbfree-owner-9-bubble-panel-light.mp4; the theme, the
 * font size, the bubble's place and the download state go back as they were.
 */
@RunWith(AndroidJUnit4::class)
class PanelShots {
    @get:Rule val a11y = A11yRule()

    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun notReadyPanel() {
        assumeTrue("pass -e owner_shots 1", InstrumentationRegistry.getArguments().getString("owner_shots") == "1")
        val dir = File(app.filesDir, "ui-shots/owner").apply { mkdirs() }
        val night = shell("cmd uimode night").substringAfter(":").trim() // "yes", "no" or "auto"
        val font = shell("settings get system font_scale").trim()
        val spot = AppGraph.settings.bubbleSpot
        val model = AppGraph.settings.model
        onMain { AppGraph.ports.downloadStates = { flowOf(mapOf(model to DownloadState.Downloading(model.sizeBytes * 42 / 100 + 1, model.sizeBytes))) } }
        try {
            for (theme in listOf("light", "dark")) {
                shell("cmd uimode night ${if (theme == "dark") "yes" else "no"}")
                SystemClock.sleep(1_500)
                launchInsertTargets(device)
                focusTarget(device, "empty")
                device.tapBubble()
                check(device.wait(Until.hasObject(By.text("Your speech model is still downloading (42%).")), 5_000)) { "no panel" }
                SystemClock.sleep(500)
                save(dir, "9-bubble-panel-$theme")
                if (theme == "light") {
                    device.tapBubble() // a second tap puts it away
                    SystemClock.sleep(1_000)
                    val recorder = thread { shell("screenrecord --bit-rate 16000000 --time-limit 6 /sdcard/thumbfree-owner-9-bubble-panel-light.mp4") }
                    SystemClock.sleep(800)
                    device.tapBubble()
                    SystemClock.sleep(2_000)
                    device.tapBubble()
                    SystemClock.sleep(1_200)
                    device.tapBubble()
                    recorder.join()
                    device.tapBubble()
                }
            }
            shell("cmd uimode night no")
            SystemClock.sleep(1_500)
            // At the left edge the panel opens to the bubble's right; dropped right of the middle, with too little room
            // beside it, it stands above the bubble, or below it near the top; at 200% its words wrap, never cut.
            val left = BubblePlacement.Spot(0f, 0.5f)
            for ((name, at, scale) in listOf(
                Triple("left", left, null), Triple("above", BubblePlacement.Spot(0.6f, 0.5f), null),
                Triple("below", BubblePlacement.Spot(0.6f, 0f), null), Triple("200-percent", left, "2.0"),
            )) {
                AppGraph.settings.bubbleSpot = at
                if (scale != null) shell("settings put system font_scale $scale")
                SystemClock.sleep(1_000)
                launchInsertTargets(device)
                focusTarget(device, "empty")
                device.tapBubble()
                check(device.wait(Until.hasObject(By.text("Your speech model is still downloading (42%).")), 5_000)) { "no panel" }
                SystemClock.sleep(500)
                save(dir, "9-bubble-panel-$name-light")
                device.tapBubble()
            }
        } finally {
            onMain { AppGraph.ports.downloadStates = { ModelDownloads.states(app) } }
            AppGraph.settings.bubbleSpot = spot
            shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")
            shell("cmd uimode night $night")
        }
    }

    private fun save(dir: File, name: String) {
        val shot = automation().takeScreenshot()
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
