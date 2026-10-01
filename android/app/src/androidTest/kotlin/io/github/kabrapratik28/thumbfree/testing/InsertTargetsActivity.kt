package io.github.kabrapratik28.thumbfree.testing

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService

/**
 * Test targets with content descriptions "empty", "prefilled" (text "Hello world"), "password", "second", and "web": a
 * WebView whose one plain <input> fills it, from inline HTML (no network). Pads for system bars.
 *
 * The test APK's manifest declares it, so it runs in the test package's own process, like a real target app. That
 * process has no Kotlin runtime (the test APK leaves out what the app APK already ships), so this class must not
 * call into kotlin.*: no lambdas with non-null parameters, no !!, no companion object. Check with javap after edits.
 */
class InsertTargetsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        // targetSdk 35+ draws edge to edge: without padding the first field sits under the status bar.
        root.fitsSystemWindows = true
        addField(root, "empty", "", InputType.TYPE_CLASS_TEXT)
        addField(root, "prefilled", "Hello world", InputType.TYPE_CLASS_TEXT)
        addField(root, "password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        addField(root, "second", "", InputType.TYPE_CLASS_TEXT)
        val web = WebView(this)
        web.contentDescription = "web"
        web.loadDataWithBaseURL(
            null,
            "<html style='height:100%'><body style='margin:0;height:100%'>" +
                "<input style='width:100%;height:100%;font-size:32px'></body></html>",
            "text/html", "utf-8", null,
        )
        root.addView(web, MATCH_PARENT, 360)
        setContentView(root)
    }

    private fun addField(root: LinearLayout, desc: String, text: String, inputType: Int) {
        val field = EditText(this)
        field.contentDescription = desc
        field.setText(text)
        field.inputType = inputType
        root.addView(field, MATCH_PARENT, WRAP_CONTENT)
    }
}

/** Starts a fresh [InsertTargetsActivity] in its own task and waits until its fields show. */
fun launchInsertTargets(device: UiDevice) {
    // A copy left on screen by the previous test would satisfy the wait below while it is being replaced.
    device.pressHome()
    check(device.wait(Until.gone(By.desc("empty")), 5_000)) { "the previous InsertTargetsActivity did not leave" }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val intent = Intent().setClassName(instrumentation.context.packageName, InsertTargetsActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    instrumentation.targetContext.startActivity(intent)
    check(device.wait(Until.hasObject(By.desc("empty")), 5_000)) { "InsertTargetsActivity did not show" }
}

/** The WebView's one input: it has no content description, so it is found by class under the WebView. */
fun webField(): BySelector = By.clazz("android.widget.EditText").hasAncestor(By.clazz("android.webkit.WebView"))

/** Clicks the field described [desc] and waits until it has input focus and the keyboard is up (awaitKeyboard). */
fun focusTarget(device: UiDevice, desc: String) {
    checkNotNull(device.wait(Until.findObject(By.desc(desc)), 5_000)) { "no field $desc" }.click()
    check(device.wait(Until.hasObject(By.desc(desc).focused(true)), 5_000)) { "$desc did not take focus" }
    awaitKeyboard()
}

/**
 * Waits until the keyboard window is all the way up, as the bubble sees it (DictationAccessibilityService.imeBounds),
 * and has held still for 300 ms. Until then the bubble can still move: it waits at its no-keyboard spot for a keyboard
 * slow to come up, then follows the window as it slides in, whose bounds reach below the screen at full height until
 * it is up. A pause at any of those spots can pass settledBubble's 300 ms, and the tap then lands where the bubble is
 * about to leave ("no take"). Every field of InsertTargetsActivity brings the keyboard (stateAlwaysVisible).
 */
fun awaitKeyboard() {
    val screen = checkNotNull(DictationAccessibilityService.instance) { "the service is not connected" }
        .getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
    var last: Rect? = null
    var since = 0L
    val up = waitFor(5_000) {
        // Bounds that reach below the screen: still sliding in.
        val now = DictationAccessibilityService.instance?.imeBounds()?.takeIf { it.bottom <= screen.bottom }
        if (now != last) {
            last = now
            since = SystemClock.uptimeMillis()
        }
        now != null && SystemClock.uptimeMillis() - since >= 300
    }
    check(up) { "the keyboard was not up and still within 5 s: ${DictationAccessibilityService.instance?.imeBounds()}" }
}
