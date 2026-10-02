import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// App id and name live only in brand.properties (colours in res/values/brand.xml, icons in mipmap-*).
val brand = Properties().apply { rootProject.file("brand.properties").reader().use { load(it) } }

// The Play upload key stays outside the repository, in the keystore.properties that -Pthumbfree.keystore names (by
// default ~/.thumbfree-keys/keystore.properties). Without it the release build is unsigned,
// so anyone can still build it.
val keystoreFile = file(
    providers.gradleProperty("thumbfree.keystore")
        .getOrElse(System.getProperty("user.home") + "/.thumbfree-keys/keystore.properties"),
)
val keystore = keystoreFile.takeIf { it.isFile }?.let { f -> Properties().apply { f.reader().use { load(it) } } }
if (keystore == null && gradle.startParameter.taskNames.any { "release" in it.lowercase() }) {
    logger.warn("No upload key at $keystoreFile, so the release build is unsigned.")
}

android {
    namespace = "io.github.kabrapratik28.thumbfree"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = brand.getProperty("applicationId")
        // -Pthumbfree.benchApp installs as <applicationId>.bench next to the regular app, for phone benchmarks.
        if (providers.gradleProperty("thumbfree.benchApp").isPresent) applicationIdSuffix = ".bench"
        // LIVE: -Pthumbfree.wrongTargetFixture (android/tools/live-phone-check-selftest.sh only) names the test APK for
        // the bench app while it still instruments the regular app, the APK android/tools/live-phone-check.sh must
        // refuse before any device change.
        if (providers.gradleProperty("thumbfree.wrongTargetFixture").isPresent) {
            testApplicationId = brand.getProperty("applicationId") + ".bench.test"
        }
        resValue("string", "app_name", brand.getProperty("appName"))
        minSdk = 33
        targetSdk = 37
        versionCode = 4
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        // Several C++ .so files (transcribe, ggml, one per CPU variant) share one C++ runtime.
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }

    signingConfigs {
        if (keystore != null) {
            create("upload") {
                storeFile = keystore.getProperty("storeFile")?.let(::file)
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
        // Release's R8 and resource shrinking, signed with the debug key, for the device tests (android/AGENTS.md). The
        // build type is not debuggable, since AGP runs R8 in its debug mode, without release's optimizations, for a
        // debuggable one. Its own manifest makes the app debuggable, for run-as and the tests' debug-only switches.
        create("minifiedTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            proguardFile("minified-test-rules.pro")
            testProguardFile("minified-test-rules.pro")
        }
    }
    // Device tests run on debug; -Pthumbfree.testBuildType=minifiedTest runs them on R8's code.
    testBuildType = providers.gradleProperty("thumbfree.testBuildType").getOrElse("debug")

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    packaging {
        jniLibs {
            // Extract .so files at install: transcribe_init_backends() lists nativeLibraryDir to find the CPU modules.
            useLegacyPackaging = true
            // -Pthumbfree.onlyCpu=<variant> (for example armv8.2_2) ships that CPU module and the armv8.0_1 fallback only,
            // so a phone can measure one build against each module (docs/decisions/native-build.md).
            val onlyCpu = providers.gradleProperty("thumbfree.onlyCpu").orNull
            val variants = listOf("armv8.0_1", "armv8.2_1", "armv8.2_2", "armv8.6_1", "armv9.0_1", "armv9.2_1", "armv9.2_2")
            if (onlyCpu != null) {
                require(onlyCpu in variants) { "thumbfree.onlyCpu=$onlyCpu is not one of $variants" }
                excludes += (variants - setOf(onlyCpu, "armv8.0_1")).map { "**/libggml-cpu-android_$it.so" }
            } else {
                // Ship only CPU variants a supported phone can pick (docs/decisions/native-build.md):
                // 8.2_1 needs dotprod without fp16, and the 9.2 variants need SME, which the Pixel 10 lacks.
                excludes += listOf("**/libggml-cpu-android_armv8.2_1.so", "**/libggml-cpu-android_armv9.2_*.so")
                // Pixel 10 (Tensor G5): armv8.6_1 measured about 20% faster than armv9.0_1 (SVE2) with identical
                // transcripts, 2026-09-25. Drop armv9.0_1 by default; -Pthumbfree.armv9 opts it back in for comparison.
                if (!providers.gradleProperty("thumbfree.armv9").isPresent) excludes += "**/libggml-cpu-android_armv9.0_1.so"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
        aidl = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric (API 36+ ApplicationSharedMemory) pokes FileDescriptor internals; JDK 21 blocks that by default.
            all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // ui-test-junit4 pulls espresso-core 3.5.0, whose event injector reflects on InputManager.getInstance, removed in Android 16.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "minifiedTestImplementation"(libs.androidx.compose.ui.test.manifest)
}
