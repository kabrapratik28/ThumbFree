package io.github.kabrapratik28.thumbfree.testing

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import org.junit.rules.ExternalResource

const val SERVICE = "io.github.kabrapratik28.thumbfree/io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService"

/**
 * Grants the mic, appends SERVICE to enabled_accessibility_services, sets accessibility_enabled 1, enables
 * and selects the device's own keyboard ([keyboard]), sets UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES before any UiDevice, waits up to 5 s for
 * instance. Afterwards, and when any of that fails, every secure setting it changes goes back exactly as it was (unset
 * ones deleted): the service list and accessibility_enabled, the enabled IMEs, the default IME and its subtype. The
 * mic stays granted: revoking a runtime permission kills the app's process (measured on the emulator), which is this
 * instrumentation's own.
 */
class A11yRule : ExternalResource() {
    /** enabled_accessibility_services as it was before the rule ran, as flattened component names. */
    lateinit var servicesBefore: List<String>
        private set

    /** The keyboard the device tests type with: the device's default one, or else the first one installed. */
    lateinit var keyboard: String
        private set

    private val saved = LinkedHashMap<String, String?>() // each secure setting it changes, in restore order; null: unset

    override fun before() {
        // A UiAutomation connected without this flag unbinds every accessibility service, ours included. UiDevice
        // connects with the Configurator flags, so they must be set before the first UiDevice exists.
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES

        val app = InstrumentationRegistry.getInstrumentation().targetContext
        automation().grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)

        for (key in SETTINGS) saved[key] = secure(key)
        servicesBefore = enabledServices()
        try {
            val others = servicesBefore.filter { it != SERVICE }
            // Still listed after an aborted run: the force stop at instrumentation start left it "crashed", and the
            // system rebinds a crashed service only after it has left the list.
            if (others != servicesBefore) putServices(others)
            putServices(others + SERVICE)
            shell("settings put secure accessibility_enabled 1")
            // Whatever keyboard the device has, so the tests never depend on one product being installed.
            keyboard = saved["default_input_method"]?.takeIf { it.isNotBlank() }
                ?: shell("ime list -a -s").lines().map { it.trim() }.first { it.isNotEmpty() }
            shell("ime enable $keyboard")
            shell("ime set $keyboard")

            check(waitFor(5_000) { DictationAccessibilityService.instance != null }) { "$SERVICE did not connect in 5 s" }
        } catch (e: Throwable) {
            restore()
            throw e
        }
    }

    // Unbind cleanly, so the next instrumentation start (any lane's) does not kill a bound service and mark it crashed.
    override fun after() = restore()

    private fun restore() {
        for ((key, value) in saved) putSecure(key, value)
        if (SERVICE !in servicesBefore) waitFor(5_000) { DictationAccessibilityService.instance == null }
    }

    private fun putServices(services: List<String>) {
        shell(
            if (services.isEmpty()) "settings delete secure enabled_accessibility_services"
            else "settings put secure enabled_accessibility_services ${services.joinToString(":")}",
        )
    }

    private companion object {
        val SETTINGS = listOf(
            "enabled_accessibility_services", "accessibility_enabled", "enabled_input_methods", "default_input_method",
            "selected_input_method_subtype",
        )
    }
}

/** A secure setting's value as stored, or null when it is unset. An empty value stays empty. */
fun secure(key: String): String? = shell("settings get secure $key").trim().takeIf { it != "null" }

/**
 * Writes a secure setting back exactly as [secure] read it: null deletes it, and an empty value goes in through the
 * settings provider, since `settings put` needs a value and [shell] splits arguments on spaces.
 */
fun putSecure(key: String, value: String?) {
    shell(
        when (value) {
            null -> "settings delete secure $key"
            "" -> "content insert --uri content://settings/secure --bind name:s:$key --bind value:s:"
            else -> "settings put secure $key $value"
        },
    )
}

/** The instrumentation's UiAutomation. Never call getUiAutomation() without the flag: it reconnects and unbinds us. */
fun automation(): UiAutomation =
    InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

/** Runs [command] as the shell user and returns its standard output. No shell parsing: arguments split on spaces. */
fun shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(automation().executeShellCommand(command)).use { it.reader().readText() }

/** enabled_accessibility_services as flattened component names (the system may store the short form). */
fun enabledServices(): List<String> =
    shell("settings get secure enabled_accessibility_services").trim().split(':')
        .mapNotNull { ComponentName.unflattenFromString(it)?.flattenToString() }

/** Polls [condition] every 50 ms for up to [timeoutMs]; true as soon as it holds. */
fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val end = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
        if (SystemClock.uptimeMillis() >= end) return false
        SystemClock.sleep(50)
    }
    return true
}
