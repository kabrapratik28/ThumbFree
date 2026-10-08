package io.github.kabrapratik28.thumbfree.build

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class ManifestContractTest {
    private val manifest = parse("src/main/AndroidManifest.xml")

    // The system binds the service from its own process, so it is exported; the permission lets only the system bind.
    @Test
    fun accessibilityServiceIsExportedAndProtected() {
        val service = service(".a11y.DictationAccessibilityService")

        assertThat(service.android("exported")).isEqualTo("true")
        assertThat(service.android("permission")).isEqualTo("android.permission.BIND_ACCESSIBILITY_SERVICE")
        assertThat(service.children("intent-filter").flatMap { it.children("action") }.map { it.android("name") })
            .contains("android.accessibilityservice.AccessibilityService")
        val config = service.children("meta-data").single { it.android("name") == "android.accessibilityservice" }
        assertThat(config.android("resource")).isEqualTo("@xml/accessibility_service")
    }

    // The merged manifest, the one that ships (AGP names the unit tests' copy of it in test_config.properties), not
    // only ours. WorkManager adds WAKE_LOCK (it holds one while a job runs) and RECEIVE_BOOT_COMPLETED (it reschedules
    // work after a reboot), AndroidX core its own receiver permission, and ML Kit's Prompt API (Clean up, a test build)
    // the right to bind Android's AICore, which runs Gemini Nano; a library that brought in another one fails here.
    @Test
    fun mergedPermissionsAreExactlyTheWhitelist() {
        val merged = parse(testConfig("android_merged_manifest"))

        assertThat(merged.children("uses-permission").map { it.android("name") }).containsExactly(
            "android.permission.RECORD_AUDIO",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "com.google.android.apps.aicore.service.BIND_SERVICE",
            "${merged.getAttribute("package")}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
    }

    // The first run asks for the microphone only. The recording and download services run without the notification
    // permission (their notifications then show only in Android's list of active apps), so no source asks for it and,
    // above, no manifest declares it.
    @Test
    fun noSourceAsksForNotifications() {
        val sources = File("src/main").walk().filter { it.extension in setOf("kt", "java", "xml") }.toList()

        assertThat(sources.size).isAtLeast(10)
        assertThat(sources.filter { "POST_NOTIFICATIONS" in it.readText() }.map { it.name }).isEmpty()
    }

    // WorkManager's own manifest declares this service; ours must widen it to dataSync (Android 14+ requires the
    // foreground service type to be declared) without AGP's manifest merger treating it as a duplicate service.
    @Test
    fun workManagerForegroundIsDataSync() {
        val service = service("androidx.work.impl.foreground.SystemForegroundService")

        assertThat(service.android("foregroundServiceType")).isEqualTo("dataSync")
        assertThat(service.tools("node")).isEqualTo("merge")
    }

    // A background app records silence without a microphone foreground service; only this app starts it.
    @Test
    fun recordingServiceIsMicrophoneTypeAndNotExported() {
        val service = service(".audio.RecordingService")

        assertThat(service.android("foregroundServiceType")).isEqualTo("microphone")
        assertThat(service.android("exported")).isEqualTo("false")
    }

    // Recordings, transcripts and models never leave the phone: no cloud backup and no transfer to a new phone.
    @Test
    fun nothingIsBackedUp() {
        val application = manifest.children("application").single()
        assertThat(application.android("allowBackup")).isEqualTo("false")
        assertThat(application.android("dataExtractionRules")).isEqualTo("@xml/data_extraction_rules")

        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = rules.children(section).single().children("exclude")
                .filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }
            assertThat(excluded).containsExactly("root", "file", "database", "sharedpref", "external", "device_root")
        }
    }

    private fun testConfig(key: String): String {
        val config = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use { config.load(it) }
        return config.getProperty(key)
    }

    // Unit tests run with the module directory (app/) as the working directory.
    private fun parse(path: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private fun service(name: String) =
        manifest.children("application").single().children("service").single { it.android("name") == name }

    private fun Element.android(attribute: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", attribute)

    private fun Element.tools(attribute: String): String =
        getAttributeNS("http://schemas.android.com/tools", attribute)

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { it.tagName == tag }
}
