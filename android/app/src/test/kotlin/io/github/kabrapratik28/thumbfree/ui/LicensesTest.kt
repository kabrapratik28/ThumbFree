package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.R
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The About screen's credits travel with the APK, so each license the app ships names itself there as it does in
 * THIRD_PARTY_NOTICES.md: the MIT code with its copyright line, the C++ runtime and the icons, the models under NVIDIA's
 * own terms, and the wordfreq list.
 */
@RunWith(RobolectricTestRunner::class)
class LicensesTest {
    private val context = RuntimeEnvironment.getApplication()

    // Unit tests run with the module directory (android/app/) as the working directory.
    private val notices = File("../../THIRD_PARTY_NOTICES.md").readText()

    @Test
    fun aboutListsEveryShippedLicense() {
        val lines = LICENSES.map(context::getString)

        assertThat(lines).containsAtLeast(
            "transcribe.cpp, ggml and miniz: MIT License",
            "Silero VAD: MIT License",
            "Text cleanup rules adapted from MIT-licensed code, Copyright (c) 2025 CJ Pais",
            "LLVM libc++, the C++ runtime from the Android NDK: Apache License 2.0 with LLVM Exceptions",
            "Material icons by Google: Apache License 2.0",
            "Parakeet speech model by NVIDIA: NVIDIA Open Model License",
            "Canary speech model by NVIDIA: CC BY 4.0",
            "wordfreq word frequency data (the common-word list): CC BY-SA 4.0",
        ).inOrder()
        assertThat(lines.single { it.startsWith("Multilingual Parakeet") }).contains("CC BY 4.0")
    }

    @Test
    fun aboutAndNoticesAgree() {
        // The copyright line the MIT terms ask for, and the licenses the notices file gives each model.
        assertThat(notices).contains("Copyright (c) 2025 CJ Pais")
        assertThat(notices).contains("NVIDIA Open Model License Agreement")
        assertThat(notices).contains("miniz")
        assertThat(notices).contains("LLVM libc++")
        assertThat(notices).contains("Material icons")
        assertThat(notices).contains("CC BY-SA 4.0")
    }
}
