plugins { id("com.android.application") }
android {
    namespace = "probe"
    compileSdk = 37
    defaultConfig { applicationId = "io.github.kabrapratik28.genaiprobe"; minSdk = 33; targetSdk = 37; versionCode = 1; versionName = "1" }
}
dependencies {
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    implementation("com.google.mlkit:genai-rewriting:1.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
