package io.github.kabrapratik28.thumbfree.testing

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** GGUF models installed on the device by android/tools/push-test-model.sh. */
object TestModels {
    const val PARAKEET_Q8 = "parakeet-unified-en-0.6b-Q8_0.gguf"
    const val CANARY_Q8 = "canary-180m-flash-Q8_0.gguf" // not in the IT command's push: push it by name

    /** The model in filesDir/models or, for tests only, the external files dir; null if it is not on the device. */
    fun find(name: String): File? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            .map { File(it, "models/$name") }
            .firstOrNull { it.isFile }
    }
}
