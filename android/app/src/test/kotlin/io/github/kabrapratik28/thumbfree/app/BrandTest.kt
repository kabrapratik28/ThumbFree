package io.github.kabrapratik28.thumbfree.app

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.BuildConfig
import io.github.kabrapratik28.thumbfree.R
import java.io.File
import java.util.Properties
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BrandTest {
    // Unit tests run with the module directory (android/app/) as the working directory.
    private val brand = Properties().apply { File("../brand.properties").reader().use { load(it) } }

    @Test
    fun appNameAndIdComeFromBrandFiles() {
        val appName = RuntimeEnvironment.getApplication().getString(R.string.app_name)

        assertThat(appName).isEqualTo(brand.getProperty("appName"))
        assertThat(BuildConfig.APPLICATION_ID).isEqualTo(brand.getProperty("applicationId"))
    }

    @Test
    fun noBrandStringsOutsideBrandFiles() {
        val valuesXml = File("src/main/res").listFiles { f -> f.name.startsWith("values") }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
        val assets = File("src/main/assets").walk().filter { it.isFile }.toList()
        assertThat(valuesXml.map { it.name }).contains("brand.xml")

        val name = brand.getProperty("appName")
        val offenders = (valuesXml + assets).filter { it.name != "brand.xml" && name in it.readText() }
        assertThat(offenders).isEmpty()
    }
}
